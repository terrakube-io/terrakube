package io.terrakube.api.plugin.token.dynamic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import io.terrakube.api.plugin.proxy.RestTemplateFactory;
import io.terrakube.api.rs.job.Job;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class DynamicCredentialsService {

    @Value("${io.terrakube.hostname}")
    String hostname;

    @Value("${io.terrakube.dynamic.credentials.hostname}")
    String overrideHostname;

    @Value("${io.terrakube.dynamic.credentials.public-key-path}")
    String publicKeyPath;

    @Value("${io.terrakube.dynamic.credentials.private-key-path}")
    String privateKeyPath;

    @Value("${io.terrakube.dynamic.credentials.kid}")
    String kid;

    @Value("${io.terrakube.dynamic.credentials.ttl}")
    int dynamicCredentialTtl;

    @Value("${io.terrakube.dynamic.credentials.vault.connect-timeout-seconds:3}")
    int vaultConnectTimeoutSeconds;

    @Value("${io.terrakube.dynamic.credentials.vault.read-timeout-seconds:10}")
    int vaultReadTimeoutSeconds;

    @Value("${io.terrakube.dynamic.credentials.vault.deadline-seconds:20}")
    int vaultDeadlineSeconds;

    private static final int VAULT_MAX_ATTEMPTS = 2;

    // Daemon threads so a call abandoned at the deadline can never keep the JVM alive.
    private static final ExecutorService VAULT_CALLS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "vault-login");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    ObjectMapper objectMapper;


    @Transactional
    public HashMap<String, String> generateDynamicCredentialsAzure(Job job, HashMap<String, String> workspaceEnvVariables) {
        String jwtToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_AZURE"));

        workspaceEnvVariables.put("ARM_OIDC_TOKEN", jwtToken);

        return workspaceEnvVariables;
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsVault(Job job, HashMap<String, String> workspaceEnvVariables) {
        String jwtToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_VAULT_AUDIENCE"));
        if (jwtToken.isEmpty()) {
            throw new DynamicCredentialsException("Vault dynamic credentials: could not generate the workload identity JWT, check the dynamic credentials key configuration");
        }

        String vaultAddress = workspaceEnvVariables.get("VAULT_ADDR");
        String vaultRole = workspaceEnvVariables.get("WORKLOAD_IDENTITY_VAULT_ROLE");
        if (vaultAddress == null || vaultAddress.isBlank()) {
            throw new DynamicCredentialsException("Vault dynamic credentials: VAULT_ADDR is not set");
        }
        if (vaultRole == null || vaultRole.isBlank()) {
            throw new DynamicCredentialsException("Vault dynamic credentials: WORKLOAD_IDENTITY_VAULT_ROLE is not set");
        }
        String vaultJwtPath = workspaceEnvVariables.getOrDefault("WORKLOAD_IDENTITY_VAULT_AUTH_PATH", "jwt");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Find if vault have namespace then add special header to request
        if (workspaceEnvVariables.containsKey("VAULT_NAMESPACE")) {
            headers.add("X-Vault-Namespace", workspaceEnvVariables.get("VAULT_NAMESPACE"));
        }

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(Map.of("role", vaultRole, "jwt", jwtToken));
        } catch (JsonProcessingException e) {
            throw new DynamicCredentialsException("Vault dynamic credentials: could not build the login request", e);
        }
        String url = String.format("%s/v1/auth/%s/login", vaultAddress, vaultJwtPath);

        String vaultToken = loginToVault(url, new HttpEntity<>(jsonPayload, headers));
        workspaceEnvVariables.put("VAULT_TOKEN", vaultToken);

        return workspaceEnvVariables;
    }

    // Everything here is bounded by vaultDeadlineSeconds in total. A connect or read timeout is
    // per socket operation, so on their own they do not stop a slow responder from holding the
    // caller (the scheduler holds the job execution lock while this runs). Transient failures
    // (I/O errors, 5xx) are retried inside the deadline; a 4xx is Vault saying no and is not.
    // Nothing from the request or response (JWT, client token, body) is logged or put in the
    // exception message.
    private String loginToVault(String url, HttpEntity<String> request) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(vaultDeadlineSeconds);
        DynamicCredentialsException lastFailure = null;
        for (int attempt = 1; attempt <= VAULT_MAX_ATTEMPTS; attempt++) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMillis <= 0) {
                break;
            }
            Future<String> call = VAULT_CALLS.submit(() -> {
                RestTemplate restTemplate = RestTemplateFactory.build(
                        Duration.ofSeconds(vaultConnectTimeoutSeconds), Duration.ofSeconds(vaultReadTimeoutSeconds));
                return restTemplate.postForObject(url, request, String.class);
            });
            try {
                return extractVaultToken(call.get(remainingMillis, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                call.cancel(true);
                lastFailure = new DynamicCredentialsException(String.format(
                        "Vault dynamic credentials: no response from Vault within %d seconds", vaultDeadlineSeconds));
                break;
            } catch (InterruptedException e) {
                call.cancel(true);
                Thread.currentThread().interrupt();
                throw new DynamicCredentialsException("Vault dynamic credentials: interrupted while waiting for Vault", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof DynamicCredentialsException failure) {
                    throw failure;
                }
                if (cause instanceof HttpStatusCodeException http && !http.getStatusCode().is5xxServerError()) {
                    throw new DynamicCredentialsException(String.format(
                            "Vault dynamic credentials: Vault rejected the login with HTTP %d, check the role, audience and auth path",
                            http.getStatusCode().value()));
                }
                lastFailure = new DynamicCredentialsException(String.format(
                        "Vault dynamic credentials: Vault login failed (%s)", describe(cause)));
                log.warn("Vault login attempt {} of {} failed: {}", attempt, VAULT_MAX_ATTEMPTS, describe(cause));
            }
        }
        throw lastFailure != null ? lastFailure : new DynamicCredentialsException(String.format(
                "Vault dynamic credentials: no response from Vault within %d seconds", vaultDeadlineSeconds));
    }

    private String extractVaultToken(String vaultResponse) {
        if (vaultResponse == null || vaultResponse.isBlank()) {
            throw new DynamicCredentialsException("Vault dynamic credentials: Vault returned an empty response");
        }
        try {
            JsonNode token = objectMapper.readTree(vaultResponse).path("auth").path("client_token");
            if (token.isTextual() && !token.asText().isBlank()) {
                return token.asText();
            }
        } catch (JsonProcessingException e) {
            throw new DynamicCredentialsException("Vault dynamic credentials: Vault returned a response that is not valid JSON");
        }
        throw new DynamicCredentialsException("Vault dynamic credentials: Vault response does not contain auth.client_token");
    }

    // Class name and HTTP status only, never getMessage(): an HttpStatusCodeException message
    // carries the response body.
    private static String describe(Throwable cause) {
        if (cause instanceof HttpStatusCodeException http) {
            return "HTTP " + http.getStatusCode().value();
        }
        return cause.getClass().getSimpleName();
    }

    private String generateJwt(Job job, String tokenAudience) {
        return generateJwt(job, tokenAudience, null);
    }

    private String generateJwt(Job job, String tokenAudience, Map<String, Object> extraClaims) {
        String jwtToken = "";
        if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
            try {
                String organizationName = job.getOrganization().getName();
                String workspaceName = job.getWorkspace().getName();
                var project = job.getWorkspace().getProject();
                Instant now = Instant.now();
                var builder = Jwts.builder()
                        .subject(String.format("organization:%s:workspace:%s", organizationName, workspaceName))
                        //.audience().add(tokenAudience).and()
                        .setAudience(tokenAudience)
                        .id(UUID.randomUUID().toString())
                        .header().add("kid", kid).and()
                        .claim("terrakube_workspace_id", job.getWorkspace().getId().toString())
                        .claim("terrakube_organization_id", job.getOrganization().getId().toString())
                        .claim("terrakube_workspace_name", workspaceName)
                        .claim("terrakube_organization_name", organizationName)
                        .claim("terrakube_job_id", String.valueOf(job.getId()))
                        .issuedAt(Date.from(now))
                        .issuer(String.format("https://%s", overrideHostname.isEmpty() ? hostname : overrideHostname))
                        .expiration(Date.from(now.plus(dynamicCredentialTtl, ChronoUnit.MINUTES)));
                // The workspace project is optional; expose its name as a claim so federated
                // trust policies (GCP/Azure/AWS/Vault) can scope by project when one is set.
                if (project != null) {
                    builder.claim("terrakube_project_name", project.getName());
                }
                if (extraClaims != null) extraClaims.forEach(builder::claim);
                jwtToken = builder
                        .signWith(getPrivateKey(), Jwts.SIG.RS512)
                        .compact();
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        } else {
            log.error("DynamicCredentialPrivateKeyPath not set, to generate Dynamic Credentials the value is need it");
        }

        return jwtToken;
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsAws(Job job, HashMap<String, String> workspaceEnvVariables) {
        Map<String, Object> extraClaims = null;
        if (Boolean.parseBoolean(workspaceEnvVariables.get("ENABLE_AWS_SESSION_TAGS"))) {
            extraClaims = Map.of("https://aws.amazon.com/tags", buildAwsSessionTags(job));
        }

        String awsWebIdentityToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_AWS"), extraClaims);

        workspaceEnvVariables.put("TERRAKUBE_AWS_CREDENTIALS_FILE", awsWebIdentityToken);
        workspaceEnvVariables.put("AWS_ROLE_ARN", workspaceEnvVariables.get("WORKLOAD_IDENTITY_ROLE_AWS"));
        // AWS_WEB_IDENTITY_TOKEN_FILE is set by the executor once it knows the absolute
        // path of the file it wrote (the workspace clone dir is only known there).

        return workspaceEnvVariables;
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsGcp(Job job, HashMap<String, String> workspaceEnvVariables) {
        String jwtToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_GCP"));

        String googleCredentialsFile = "{\n" +
                "    \"access_token\": \"%s\"\n" +
                "} ";

        googleCredentialsFile = String.format(googleCredentialsFile, jwtToken);

        String googleCredentialConfigFile = "{\n" +
                "    \"type\": \"external_account\",\n" +
                "    \"audience\": \"%s\",\n" +
                "    \"subject_token_type\": \"urn:ietf:params:oauth:token-type:jwt\",\n" +
                "    \"token_url\": \"https://sts.googleapis.com/v1/token\",\n" +
                "    \"service_account_impersonation_url\": \"https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/%s:generateAccessToken\",\n" +
                "    \"credential_source\": {\n" +
                "      \"file\": \"${WORKSPACE_DIRECTORY}/terrakube_dynamic_credentials.json\",\n" +
                "      \"format\": {\n" +
                "        \"type\": \"json\",\n" +
                "        \"subject_token_field_name\": \"access_token\"\n" +
                "      }\n" +
                "    }\n" +
                "  }";

        // The ${WORKSPACE_DIRECTORY} placeholder is substituted by the executor when
        // it writes the config to disk (only the executor knows the workspace clone path).
        String audience = workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_GCP");
        String serviceAccountEmail = workspaceEnvVariables.get("WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL");

        googleCredentialConfigFile = String.format(googleCredentialConfigFile, audience, serviceAccountEmail);

        workspaceEnvVariables.put("TERRAKUBE_GCP_CREDENTIALS_FILE", googleCredentialsFile);
        workspaceEnvVariables.put("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE", googleCredentialConfigFile);
        // GOOGLE_APPLICATION_CREDENTIALS is set by the executor once it knows the absolute
        // path of the file it wrote (the workspace clone dir is only known there).

        return workspaceEnvVariables;
    }

    private Map<String, Object> buildAwsSessionTags(Job job) {
        Map<String, List<String>> principalTags = new LinkedHashMap<>();
        principalTags.put("terrakube:org",       List.of(sanitizeTag(job.getOrganization().getName())));
        principalTags.put("terrakube:workspace", List.of(sanitizeTag(job.getWorkspace().getName())));
        var project = job.getWorkspace().getProject();
        if (project != null) {
            principalTags.put("terrakube:project", List.of(sanitizeTag(project.getName())));
        }
        return Map.of(
                "principal_tags",      principalTags,
                "transitive_tag_keys", new ArrayList<>(principalTags.keySet()));
    }

    private String sanitizeTag(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9 _.:/=+@-]", "_");
        return sanitized.length() > 256 ? sanitized.substring(0, 256) : sanitized;
    }

    private PrivateKey getPrivateKey() throws Exception {
        String rsaPrivateKey = FileUtils.readFileToString(new File(privateKeyPath), StandardCharsets.UTF_8);

        rsaPrivateKey = rsaPrivateKey.replace("-----BEGIN PRIVATE KEY-----", "");
        rsaPrivateKey = rsaPrivateKey.replace("-----END PRIVATE KEY-----", "");

        String privateKeyPEMFinal = "";
        String line;
        BufferedReader bufReader = new BufferedReader(new StringReader(rsaPrivateKey));
        while ((line = bufReader.readLine()) != null) {
            privateKeyPEMFinal += line;
        }
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyPEMFinal));
        KeyFactory kf = KeyFactory.getInstance("RSA");

        return kf.generatePrivate(keySpec);
    }

    public String getPublicKey() {
        String publicKeyPEM = "";
        try {
            publicKeyPEM = FileUtils.readFileToString(new File(publicKeyPath), StandardCharsets.UTF_8);

            publicKeyPEM = publicKeyPEM.replace("-----BEGIN PUBLIC KEY-----", "");
            publicKeyPEM = publicKeyPEM.replace("-----END PUBLIC KEY-----", "");

            String publicKeyPEMFinal = "";
            String line;
            BufferedReader bufReader = new BufferedReader(new StringReader(publicKeyPEM));
            while ((line = bufReader.readLine()) != null) {
                publicKeyPEMFinal += line;
            }

            log.info("Dynamic Credentials Public Key: {}", publicKeyPEMFinal);
            return publicKeyPEMFinal;
        } catch (Exception ex) {
            publicKeyPEM = "";
            log.error(ex.getMessage());
        }

        return publicKeyPEM;
    }
}
