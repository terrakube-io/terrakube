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


    @Transactional
    public HashMap<String, String> generateDynamicCredentialsAzure(Job job, HashMap<String, String> workspaceEnvVariables) {
        String jwtToken = generateJwt(
                job.getOrganization().getName(),
                job.getWorkspace().getName(),
                workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_AZURE"),
                job.getOrganization().getId().toString(),
                job.getWorkspace().getId().toString(),
                job.getId()
        );

        log.debug("ARM_OIDC_TOKEN: {}", jwtToken);
        workspaceEnvVariables.put("ARM_OIDC_TOKEN", jwtToken);

        return workspaceEnvVariables;
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsVault(Job job, HashMap<String, String> workspaceEnvVariables) {
        String jwtToken = generateJwt(
                job.getOrganization().getName(),
                job.getWorkspace().getName(),
                workspaceEnvVariables.get("WORKLOAD_IDENTITY_VAULT_AUDIENCE"),
                job.getOrganization().getId().toString(),
                job.getWorkspace().getId().toString(),
                job.getId()
        );

        String vaultAddress = workspaceEnvVariables.get("VAULT_ADDR");
        String vaultRole = workspaceEnvVariables.get("WORKLOAD_IDENTITY_VAULT_ROLE");
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

    private String generateJwt(String organizationName, String workspaceName, String tokenAudience, String organizationId, String workspaceId, int jobId) {
        String jwtToken = "";
        if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
            try {
                Instant now = Instant.now();
                jwtToken = Jwts.builder()
                        .setSubject(String.format("organization:%s:workspace:%s", organizationName, workspaceName))
                        .setAudience(tokenAudience)
                        .setId(UUID.randomUUID().toString())
                        .setHeaderParam("kid", kid)
                        .claim("terrakube_workspace_id", workspaceId)
                        .claim("terrakube_organization_id", organizationId)
                        .claim("terrakube_workspace_name", workspaceName)
                        .claim("terrakube_organization_name", organizationName)
                        .claim("terrakube_job_id", String.valueOf(jobId))
                        .setIssuedAt(Date.from(now))
                        .setIssuer(String.format("https://%s", overrideHostname.isEmpty() ? hostname : overrideHostname))
                        .setExpiration(Date.from(now.plus(dynamicCredentialTtl, ChronoUnit.MINUTES)))
                        .signWith(getPrivateKey())
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
        String awsWebIdentityToken = generateJwt(
                job.getOrganization().getName(),
                job.getWorkspace().getName(),
                workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_AWS"),
                job.getOrganization().getId().toString(),
                job.getWorkspace().getId().toString(),
                job.getId()
        );

        log.debug("TERRAKUBE_AWS_CREDENTIALS_FILE: {}", awsWebIdentityToken);

        workspaceEnvVariables.put("TERRAKUBE_AWS_CREDENTIALS_FILE", awsWebIdentityToken);
        workspaceEnvVariables.put("AWS_ROLE_ARN", workspaceEnvVariables.get("WORKLOAD_IDENTITY_ROLE_AWS"));
        workspaceEnvVariables.put("AWS_WEB_IDENTITY_TOKEN_FILE", getDefaultExecutorPath(job) + "/terrakube_config_dynamic_credentials_aws.txt");

        return workspaceEnvVariables;
    }

    @Transactional
    public HashMap<String, String> generateDynamicCredentialsGcp(Job job, HashMap<String, String> workspaceEnvVariables) {
        String jwtToken = generateJwt(
                job.getOrganization().getName(),
                job.getWorkspace().getName(),
                workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_GCP"),
                job.getOrganization().getId().toString(),
                job.getWorkspace().getId().toString(),
                job.getId()
        );

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
                "      \"file\": \"%s/terrakube_dynamic_credentials.json\",\n" +
                "      \"format\": {\n" +
                "        \"type\": \"json\",\n" +
                "        \"subject_token_field_name\": \"access_token\"\n" +
                "      }\n" +
                "    }\n" +
                "  }";

        String executorDirectory = getDefaultExecutorPath(job);

        String audience = workspaceEnvVariables.get("WORKLOAD_IDENTITY_AUDIENCE_GCP");
        String serviceAccountEmail = workspaceEnvVariables.get("WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL");

        googleCredentialConfigFile = String.format(googleCredentialConfigFile, audience, serviceAccountEmail, executorDirectory);

        log.debug("TERRAKUBE_GCP_CREDENTIALS_FILE: {}", googleCredentialsFile);
        log.debug("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE: {}", googleCredentialConfigFile);

        workspaceEnvVariables.put("TERRAKUBE_GCP_CREDENTIALS_FILE", googleCredentialsFile);
        workspaceEnvVariables.put("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE", googleCredentialConfigFile);
        workspaceEnvVariables.put("GOOGLE_APPLICATION_CREDENTIALS", executorDirectory + "/terrakube_config_dynamic_credentials.json");

        return workspaceEnvVariables;
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

    private static String getDefaultExecutorPath(Job job) {
        return String.format(
                "%s/.terraform-spring-boot/executor/%s/%s",
                FileUtils.getUserDirectoryPath(),
                job.getOrganization().getId().toString(),
                job.getWorkspace().getId().toString()
        );
    }
}
