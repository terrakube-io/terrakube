package io.terrakube.api.plugin.token.dynamic;

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
import org.springframework.web.client.RestTemplate;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.rs.job.Job;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

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

    @Autowired
    ObjectMapper objectMapper;

    // Opt-in, off by default. When enabled, every token says which run phase it was issued for
    // (terrakube_run_phase claim and a ":run_phase:<phase>" suffix on the subject), and steps that
    // do not change infrastructure use the "_PLAN" variant of the identity settings when one is set
    // (WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL_PLAN, WORKLOAD_IDENTITY_ROLE_AWS_PLAN,
    // WORKLOAD_IDENTITY_VAULT_ROLE_PLAN, ARM_CLIENT_ID_PLAN). A plan runs the code under review, so
    // it can then get a read-only identity while apply keeps the one that can make changes. The
    // cloud or Vault side has to enforce it: the plan step receives its own token, so only a trust
    // condition on the run phase stops that token from being exchanged for the apply identity.
    @Value("${io.terrakube.dynamic.credentials.run-phase.enabled:false}")
    boolean runPhaseEnabled;

    static final String RUN_PHASE_PLAN = "plan";
    static final String RUN_PHASE_APPLY = "apply";
    private static final Set<String> APPLY_FLOWS = Set.of(FlowType.terraformApply.name(),
            FlowType.terraformDestroy.name());

    /**
     * Returns the run phase of a step of the given flow type: "apply" for steps that change
     * infrastructure, "plan" for every other step. Returns null when run-phase credentials are
     * disabled.
     */
    public String runPhase(String flowType) {
        if (!runPhaseEnabled || flowType == null) {
            return null;
        }
        return APPLY_FLOWS.contains(flowType) ? RUN_PHASE_APPLY : RUN_PHASE_PLAN;
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

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsAzure(Job job, HashMap<String, String> workspaceEnvVariables) {
        return generateDynamicCredentialsAzure(job, workspaceEnvVariables, null);
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsAzure(Job job, HashMap<String, String> workspaceEnvVariables,
            String runPhase) {
        String jwtToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_AZURE"), null, runPhase);

        log.debug("ARM_OIDC_TOKEN: {}", jwtToken);
        workspaceEnvVariables.put("ARM_OIDC_TOKEN", jwtToken);
        String clientId = forRunPhase(workspaceEnvVariables, "ARM_CLIENT_ID", runPhase);
        if (clientId != null) {
            workspaceEnvVariables.put("ARM_CLIENT_ID", clientId);
        }

        return workspaceEnvVariables;
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsVault(Job job, HashMap<String, String> workspaceEnvVariables) {
        return generateDynamicCredentialsVault(job, workspaceEnvVariables, null);
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsVault(Job job, HashMap<String, String> workspaceEnvVariables,
            String runPhase) {
        String jwtToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_VAULT_AUDIENCE"), null, runPhase);

        String vaultAddress = workspaceEnvVariables.get("VAULT_ADDR");
        String vaultRole = forRunPhase(workspaceEnvVariables, "WORKLOAD_IDENTITY_VAULT_ROLE", runPhase);
        String vaultToken = "";
        // Work with vault jwt path
        String vaultJwtPath;
        if (workspaceEnvVariables.containsKey("WORKLOAD_IDENTITY_VAULT_AUTH_PATH")) {
            vaultJwtPath = workspaceEnvVariables.get("WORKLOAD_IDENTITY_VAULT_AUTH_PATH");
        } else {
            vaultJwtPath = "jwt";
        }

        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Find if vault have namespace then add special header to request
            if (workspaceEnvVariables.containsKey("VAULT_NAMESPACE")) {
                headers.add("X-Vault-Namespace", workspaceEnvVariables.get("VAULT_NAMESPACE"));
            }
            
            String jsonPayload = String.format("{\"role\":\"%s\",\"jwt\":\"%s\"}", vaultRole, jwtToken);

            HttpEntity<String> request = new HttpEntity<>(jsonPayload, headers);
            String url = String.format("%s/v1/auth/%s/login",vaultAddress, vaultJwtPath);
            String vaultResponse = restTemplate.postForObject(url, request, String.class);

            log.debug("Vault Response: {}", vaultResponse);
            JsonNode rootNode = objectMapper.readTree(vaultResponse);
            vaultToken = rootNode.get("auth").get("client_token").asText();
        } catch (Exception e) {
            log.error("Error processing vault token", e);
        }

        log.debug("TERRAKUBE_VAULT_TOKEN: {}", jwtToken);
        log.debug("VAULT_TOKEN: {}", vaultToken);
        workspaceEnvVariables.put("VAULT_TOKEN", vaultToken);

        return workspaceEnvVariables;
    }

    private String generateJwt(Job job, String tokenAudience) {
        return generateJwt(job, tokenAudience, null, null);
    }

    private String generateJwt(Job job, String tokenAudience, Map<String, Object> extraClaims, String runPhase) {
        String jwtToken = "";
        if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
            try {
                String organizationName = job.getOrganization().getName();
                String workspaceName = job.getWorkspace().getName();
                var project = job.getWorkspace().getProject();
                String subject = String.format("organization:%s:workspace:%s", organizationName, workspaceName);
                if (runPhase != null) {
                    subject = subject + ":run_phase:" + runPhase;
                }
                Instant now = Instant.now();
                var builder = Jwts.builder()
                        .subject(subject)
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
                if (runPhase != null) {
                    builder.claim("terrakube_run_phase", runPhase);
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
        return generateDynamicCredentialsAws(job, workspaceEnvVariables, null);
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsAws(Job job, HashMap<String, String> workspaceEnvVariables,
            String runPhase) {
        Map<String, Object> extraClaims = null;
        if (Boolean.parseBoolean(workspaceEnvVariables.get("ENABLE_AWS_SESSION_TAGS"))) {
            extraClaims = Map.of("https://aws.amazon.com/tags", buildAwsSessionTags(job));
        }

        String awsWebIdentityToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_AWS"),
                extraClaims, runPhase);

        log.debug("TERRAKUBE_AWS_CREDENTIALS_FILE: {}", awsWebIdentityToken);

        workspaceEnvVariables.put("TERRAKUBE_AWS_CREDENTIALS_FILE", awsWebIdentityToken);
        workspaceEnvVariables.put("AWS_ROLE_ARN", forRunPhase(workspaceEnvVariables, "WORKLOAD_IDENTITY_ROLE_AWS", runPhase));
        // AWS_WEB_IDENTITY_TOKEN_FILE is set by the executor once it knows the absolute
        // path of the file it wrote (the workspace clone dir is only known there).

        return workspaceEnvVariables;
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsGcp(Job job, HashMap<String, String> workspaceEnvVariables) {
        return generateDynamicCredentialsGcp(job, workspaceEnvVariables, null);
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsGcp(Job job, HashMap<String, String> workspaceEnvVariables,
            String runPhase) {
        String jwtToken = generateJwt(job, workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_GCP"), null, runPhase);

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
        String serviceAccountEmail = forRunPhase(workspaceEnvVariables, "WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL", runPhase);

        googleCredentialConfigFile = String.format(googleCredentialConfigFile, audience, serviceAccountEmail);

        log.debug("TERRAKUBE_GCP_CREDENTIALS_FILE: {}", googleCredentialsFile);
        log.debug("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE: {}", googleCredentialConfigFile);

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
