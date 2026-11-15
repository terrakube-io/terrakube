package io.terrakube.api.plugin.token.dynamic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Exceptions;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import io.terrakube.api.plugin.http.ReactorNettyWebClientFactory;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
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

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    WebClient.Builder webClientBuilder;

    // Global kill switch (default true); see io.terrakube.dynamic.credentials.run-phase.enabled.
    @Value("${io.terrakube.dynamic.credentials.run-phase.enabled:true}")
    boolean runPhaseEnabled = true;

    static final String RUN_PHASE_PLAN = "plan";
    static final String RUN_PHASE_APPLY = "apply";
    private static final Set<String> APPLY_FLOWS = Set.of(FlowType.terraformApply.name(),
            FlowType.terraformDestroy.name());

    /**
     * Returns the run phase of a step of the given flow type: "apply" for steps that change
     * infrastructure, "plan" for every other step. Returns null when run-phase credentials are
     * disabled or {@code flowType} is null.
     */
    public String runPhase(String flowType) {
        if (!runPhaseEnabled || flowType == null) {
            return null;
        }
        return APPLY_FLOWS.contains(flowType) ? RUN_PHASE_APPLY : RUN_PHASE_PLAN;
    }

    /**
     * Returns the run phase for a step within a job's TCL flow. Apply and destroy steps receive
     * "apply", and customScripts steps receive "apply" when preceded by an apply or destroy step
     * in the same flow; all other steps receive "plan".
     */
    public String runPhase(Job job, Flow flow) {
        if (!runPhaseEnabled || flow == null || flow.getType() == null) {
            return null;
        }
        if (FlowType.customScripts.name().equals(flow.getType())
                && TclService.hasPrecedingApplyOrDestroy(job, flow)) {
            return RUN_PHASE_APPLY;
        }
        return runPhase(flow.getType());
    }

    private String normalizeRunPhase(String runPhase) {
        if (runPhase != null && !RUN_PHASE_PLAN.equals(runPhase) && !RUN_PHASE_APPLY.equals(runPhase)) {
            throw new IllegalArgumentException("Invalid run phase: " + runPhase);
        }
        return runPhaseEnabled ? runPhase : null;
    }

    private static String buildSubject(String organizationName, String workspaceName, String effectiveRunPhase,
            boolean includeRunPhaseInSubject) {
        String subject = String.format("organization:%s:workspace:%s", organizationName, workspaceName);
        if (effectiveRunPhase != null && includeRunPhaseInSubject) {
            return subject + ":run_phase:" + effectiveRunPhase;
        }
        return subject;
    }

    // The "_PLAN" variant of a setting for a plan-phase step when it is set, the setting itself otherwise.
    private static String forRunPhase(Map<String, String> workspaceEnvVariables, String key, String runPhase) {
        if (RUN_PHASE_PLAN.equals(runPhase)) {
            String planValue = workspaceEnvVariables.get(key + "_PLAN");
            if (planValue != null && !planValue.isBlank()) {
                return planValue;
            }
        }
        return workspaceEnvVariables.get(key);
    }

    private static boolean isRunPhaseSubjectEnabled(Map<String, String> workspaceEnvVariables) {
        String value = workspaceEnvVariables.get("ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE");
        return value != null && Boolean.parseBoolean(value.trim());
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsAzure(Job job, HashMap<String, String> workspaceEnvVariables,
            String runPhase) {
        String effectiveRunPhase = normalizeRunPhase(runPhase);
        String jwtToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_AZURE"), null,
                effectiveRunPhase, isRunPhaseSubjectEnabled(workspaceEnvVariables));

        workspaceEnvVariables.put("ARM_OIDC_TOKEN", jwtToken);
        String clientId = forRunPhase(workspaceEnvVariables, "ARM_CLIENT_ID", effectiveRunPhase);
        if (clientId != null) {
            workspaceEnvVariables.put("ARM_CLIENT_ID", clientId);
        }

        return workspaceEnvVariables;
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsVault(Job job, HashMap<String, String> workspaceEnvVariables,
            String runPhase) {
        String effectiveRunPhase = normalizeRunPhase(runPhase);
        String jwtToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_VAULT_AUDIENCE"), null,
                effectiveRunPhase, isRunPhaseSubjectEnabled(workspaceEnvVariables));
        if (jwtToken.isEmpty()) {
            throw new DynamicCredentialsException("Vault dynamic credentials: could not generate the workload identity JWT, check the dynamic credentials key configuration");
        }

        String vaultAddress = workspaceEnvVariables.get("VAULT_ADDR");
        String vaultRole = forRunPhase(workspaceEnvVariables, "WORKLOAD_IDENTITY_VAULT_ROLE", effectiveRunPhase);
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

        String vaultToken = loginToVault(url, headers, jsonPayload);
        workspaceEnvVariables.put("VAULT_TOKEN", vaultToken);

        return workspaceEnvVariables;
    }

    // Everything here is bounded by vaultDeadlineSeconds in total. A connect or read timeout is
    // per socket operation, so on their own they do not stop a slow responder from holding the
    // caller (the scheduler holds the job execution lock while this runs). The deadline is a
    // Reactor timeout, so when it expires the subscription is cancelled and the channel closed,
    // there is no abandoned thread left blocked on the socket. Transient failures (I/O errors,
    // 5xx) are retried inside the deadline; a 4xx is Vault saying no and is not. Nothing from
    // the request or response (JWT, client token, body) is logged or put in the exception message.
    private String loginToVault(String url, HttpHeaders headers, String jsonPayload) {
        WebClient webClient = webClientBuilder
                .clone()
                .clientConnector(ReactorNettyWebClientFactory.connector(
                        Duration.ofSeconds(vaultConnectTimeoutSeconds), Duration.ofSeconds(vaultReadTimeoutSeconds)))
                .build();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(vaultDeadlineSeconds);
        DynamicCredentialsException lastFailure = null;
        for (int attempt = 1; attempt <= VAULT_MAX_ATTEMPTS; attempt++) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMillis <= 0) {
                break;
            }
            try {
                ResponseEntity<String> response = webClient.post()
                        .uri(url)
                        .headers(h -> h.addAll(headers))
                        .bodyValue(jsonPayload)
                        .retrieve()
                        .toEntity(String.class)
                        .timeout(Duration.ofMillis(remainingMillis))
                        .block();
                return extractVaultToken(response);
            } catch (DynamicCredentialsException failure) {
                throw failure;
            } catch (RuntimeException e) {
                Throwable cause = Exceptions.unwrap(e);
                if (cause instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new DynamicCredentialsException("Vault dynamic credentials: interrupted while waiting for Vault", cause);
                }
                if (cause instanceof TimeoutException) {
                    lastFailure = new DynamicCredentialsException(String.format(
                            "Vault dynamic credentials: no response from Vault within %d seconds", vaultDeadlineSeconds));
                    break;
                }
                if (cause instanceof WebClientResponseException http && !http.getStatusCode().is5xxServerError()) {
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

    private String extractVaultToken(ResponseEntity<String> response) {
        if (response == null) {
            throw new DynamicCredentialsException("Vault dynamic credentials: Vault returned an empty response");
        }
        // Redirects are not followed, so a 3xx is Vault (or something in front of it) not
        // answering the login and must not be read as an empty or malformed body.
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) {
            throw new DynamicCredentialsException(String.format(
                    "Vault dynamic credentials: Vault answered the login with HTTP %d, check VAULT_ADDR and the auth path",
                    status.value()));
        }
        String vaultResponse = response.getBody();
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

    // Class name and HTTP status only, never getMessage(): a WebClientResponseException message
    // carries the response body.
    private static String describe(Throwable cause) {
        if (cause instanceof WebClientResponseException http) {
            return "HTTP " + http.getStatusCode().value();
        }
        return cause.getClass().getSimpleName();
    }

    private String generateJwt(Job job, String tokenAudience, Map<String, Object> extraClaims, String effectiveRunPhase,
            boolean includeRunPhaseInSubject) {
        if (privateKeyPath == null || privateKeyPath.isEmpty()) {
            log.error("DynamicCredentialPrivateKeyPath not set, to generate Dynamic Credentials the value is need it");
            return "";
        }
        try {
            String organizationName = job.getOrganization().getName();
            String workspaceName = job.getWorkspace().getName();
            var project = job.getWorkspace().getProject();
            Instant now = Instant.now();
            var builder = Jwts.builder()
                    .subject(buildSubject(organizationName, workspaceName, effectiveRunPhase, includeRunPhaseInSubject))
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
            if (effectiveRunPhase != null) {
                builder.claim("terrakube_run_phase", effectiveRunPhase);
            }
            if (extraClaims != null) {
                extraClaims.forEach(builder::claim);
            }
            return builder
                    .signWith(getPrivateKey(), Jwts.SIG.RS512)
                    .compact();
        } catch (Exception e) {
            log.error(e.getMessage());
            return "";
        }
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsAws(Job job, HashMap<String, String> workspaceEnvVariables,
            String runPhase) {
        String effectiveRunPhase = normalizeRunPhase(runPhase);
        boolean includeRunPhaseInSubject = isRunPhaseSubjectEnabled(workspaceEnvVariables);
        Map<String, Object> extraClaims = null;
        if (Boolean.parseBoolean(workspaceEnvVariables.get("ENABLE_AWS_SESSION_TAGS"))) {
            extraClaims = Map.of("https://aws.amazon.com/tags",
                    buildAwsSessionTags(job, includeRunPhaseInSubject ? effectiveRunPhase : null));
        }

        String awsWebIdentityToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_AWS"),
                extraClaims, effectiveRunPhase, includeRunPhaseInSubject);

        workspaceEnvVariables.put("TERRAKUBE_AWS_CREDENTIALS_FILE", awsWebIdentityToken);
        String awsRoleArn = forRunPhase(workspaceEnvVariables, "WORKLOAD_IDENTITY_ROLE_AWS", effectiveRunPhase);
        if (awsRoleArn != null) {
            workspaceEnvVariables.put("AWS_ROLE_ARN", awsRoleArn);
        }
        // AWS_WEB_IDENTITY_TOKEN_FILE is set by the executor once it knows the absolute
        // path of the file it wrote (the workspace clone dir is only known there).

        return workspaceEnvVariables;
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsGcp(Job job, HashMap<String, String> workspaceEnvVariables,
            String runPhase) {
        String effectiveRunPhase = normalizeRunPhase(runPhase);
        String jwtToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_GCP"), null,
                effectiveRunPhase, isRunPhaseSubjectEnabled(workspaceEnvVariables));

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
        String serviceAccountEmail = forRunPhase(workspaceEnvVariables, "WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL",
                effectiveRunPhase);

        googleCredentialConfigFile = String.format(googleCredentialConfigFile, audience, serviceAccountEmail);

        workspaceEnvVariables.put("TERRAKUBE_GCP_CREDENTIALS_FILE", googleCredentialsFile);
        workspaceEnvVariables.put("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE", googleCredentialConfigFile);
        // GOOGLE_APPLICATION_CREDENTIALS is set by the executor once it knows the absolute
        // path of the file it wrote (the workspace clone dir is only known there).

        return workspaceEnvVariables;
    }

    private Map<String, Object> buildAwsSessionTags(Job job, String runPhase) {
        Map<String, List<String>> principalTags = new LinkedHashMap<>();
        principalTags.put("terrakube:org",       List.of(sanitizeTag(job.getOrganization().getName())));
        principalTags.put("terrakube:workspace", List.of(sanitizeTag(job.getWorkspace().getName())));
        var project = job.getWorkspace().getProject();
        if (project != null) {
            principalTags.put("terrakube:project", List.of(sanitizeTag(project.getName())));
        }
        if (runPhase != null) {
            principalTags.put("terrakube:run_phase", List.of(runPhase));
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
