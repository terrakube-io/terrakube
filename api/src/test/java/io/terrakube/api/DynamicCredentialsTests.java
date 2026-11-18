package io.terrakube.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.plugin.token.dynamic.DynamicCredentialsService;
import io.terrakube.api.plugin.token.dynamic.JwksController;
import io.terrakube.api.plugin.token.dynamic.OpenIdConfigurationController;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.project.Project;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

public class DynamicCredentialsTests extends ServerApplicationTests {

    @Autowired
    DynamicCredentialsService dynamicCredentialsService;

    @Autowired
    OpenIdConfigurationController openIdConfigurationController;

    @Autowired
    JwksController jwksController;

    private static Path publicKeyFile;
    private static Path privateKeyFile;

    @TempDir
    static Path tempDir;

    @BeforeAll
    public void generateKeyPairAndConfigureService() throws NoSuchAlgorithmException, IOException {
        // Generate RSA key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();

        // Convert keys to PEM format
        String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(publicKey.getEncoded()) +
                "\n-----END PUBLIC KEY-----";

        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(privateKey.getEncoded()) +
                "\n-----END PRIVATE KEY-----";

        // Write keys to temp files
        publicKeyFile = tempDir.resolve("public.pem");
        privateKeyFile = tempDir.resolve("private.pem");

        Files.writeString(publicKeyFile, publicKeyPem);
        Files.writeString(privateKeyFile, privateKeyPem);
    }

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Inject the key paths into the service using ReflectionTestUtils
        ReflectionTestUtils.setField(dynamicCredentialsService, "publicKeyPath", publicKeyFile.toString());
        ReflectionTestUtils.setField(dynamicCredentialsService, "privateKeyPath", privateKeyFile.toString());
        ReflectionTestUtils.setField(dynamicCredentialsService, "kid", "03446895-220d-47e1-9564-4eeaa3691b42");
        ReflectionTestUtils.setField(dynamicCredentialsService, "dynamicCredentialTtl", 60);
        ReflectionTestUtils.setField(dynamicCredentialsService, "runPhaseEnabled", true);
        ReflectionTestUtils.setField(openIdConfigurationController, "runPhaseEnabled", true);
        ReflectionTestUtils.setField(openIdConfigurationController, "openIdConfiguration", null);
    }

    @Test
    void testGetPublicKeyReturnsValidKey() {
        String publicKey = dynamicCredentialsService.getPublicKey();

        assertNotNull(publicKey);
        assertFalse(publicKey.isEmpty(), "Public key should not be empty");
        assertFalse(publicKey.contains("-----BEGIN"), "PEM headers should be stripped");
    }

    @Test
    void testGenerateDynamicCredentialsAzureAddsOidcToken() {
        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AZURE", "api://AzureADTokenExchange");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsAzure(job, envVariables, null);

        assertTrue(result.containsKey("ARM_OIDC_TOKEN"));
        assertFalse(result.get("ARM_OIDC_TOKEN").isEmpty(), "JWT token should be generated");
        assertEquals(3, result.get("ARM_OIDC_TOKEN").split("\\.").length, "Should be a valid JWT with 3 parts");
    }

    @Test
    void testGenerateDynamicCredentialsAwsAddsRequiredVariables() {
        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        envVariables.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/test-role");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsAws(job, envVariables, null);

        assertTrue(result.containsKey("TERRAKUBE_AWS_CREDENTIALS_FILE"));
        assertTrue(result.containsKey("AWS_ROLE_ARN"));
        assertEquals("arn:aws:iam::123456789012:role/test-role", result.get("AWS_ROLE_ARN"));
        assertFalse(result.get("TERRAKUBE_AWS_CREDENTIALS_FILE").isEmpty(), "JWT token should be generated");
    }

    @Test
    void testGenerateDynamicCredentialsGcpAddsRequiredVariables() {
        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_GCP", "//iam.googleapis.com/projects/123/locations/global/workloadIdentityPools/pool/providers/provider");
        envVariables.put("WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL", "sa@project.iam.gserviceaccount.com");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsGcp(job, envVariables, null);

        assertTrue(result.containsKey("TERRAKUBE_GCP_CREDENTIALS_FILE"));
        assertTrue(result.containsKey("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE"));
        assertTrue(result.get("TERRAKUBE_GCP_CREDENTIALS_FILE").contains("access_token"));
    }

    private Job createMockJob() {
        var organization = organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        var workspace = workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        workspace.setProject(null);

        Job job = new Job();
        job.setId(999);
        job.setOrganization(organization);
        job.setWorkspace(workspace);

        return job;
    }

    private JsonNode decodeJwtClaims(String jwt) throws IOException {
        String payload = jwt.split("\\.")[1];
        byte[] decoded = Base64.getUrlDecoder().decode(payload);
        return new ObjectMapper().readTree(decoded);
    }

    @Test
    void testAwsTokenContainsSessionTags() throws IOException {
        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        envVariables.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/test-role");
        envVariables.put("ENABLE_AWS_SESSION_TAGS", "true");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsAws(job, envVariables, null);

        JsonNode claims = decodeJwtClaims(result.get("TERRAKUBE_AWS_CREDENTIALS_FILE"));
        JsonNode tags = claims.get("https://aws.amazon.com/tags");
        assertNotNull(tags, "AWS token must carry the session-tags claim");

        JsonNode principalTags = tags.get("principal_tags");
        assertTrue(principalTags.has("terrakube:org"), "principal_tags must contain terrakube:org");
        assertTrue(principalTags.has("terrakube:workspace"), "principal_tags must contain terrakube:workspace");

        assertTrue(principalTags.get("terrakube:org").isArray());
        assertEquals(1, principalTags.get("terrakube:org").size());
        assertEquals(1, principalTags.get("terrakube:workspace").size());

        JsonNode transitive = tags.get("transitive_tag_keys");
        assertTrue(transitive.isArray());
    }

    @Test
    void testAwsTokenHasNoSessionTagsWhenFlagDisabled() throws IOException {
        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        envVariables.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/test-role");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsAws(job, envVariables, null);

        assertFalse(decodeJwtClaims(result.get("TERRAKUBE_AWS_CREDENTIALS_FILE")).has("https://aws.amazon.com/tags"),
                "AWS token must not carry the session-tags claim when ENABLE_AWS_SESSION_TAGS is not set");
    }

    @Test
    void testAwsTokenOmitsProjectTagWhenNoProject() throws IOException {
        Job job = createMockJob();
        job.getWorkspace().setProject(null);

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        envVariables.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/test-role");
        envVariables.put("ENABLE_AWS_SESSION_TAGS", "true");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsAws(job, envVariables, null);

        JsonNode principalTags = decodeJwtClaims(result.get("TERRAKUBE_AWS_CREDENTIALS_FILE"))
                .get("https://aws.amazon.com/tags").get("principal_tags");
        assertFalse(principalTags.has("terrakube:project"),
                "terrakube:project must be absent when the workspace has no project");
    }

    @Test
    void testAwsTokenIncludesProjectTagWhenPresent() throws IOException {
        Job job = createMockJob();
        Project project = new Project();
        project.setName("my-project");
        job.getWorkspace().setProject(project);

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        envVariables.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/test-role");
        envVariables.put("ENABLE_AWS_SESSION_TAGS", "true");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsAws(job, envVariables, null);

        JsonNode principalTags = decodeJwtClaims(result.get("TERRAKUBE_AWS_CREDENTIALS_FILE"))
                .get("https://aws.amazon.com/tags").get("principal_tags");
        assertTrue(principalTags.has("terrakube:project"),
                "terrakube:project must be present when the workspace has a project");
        assertEquals("my-project", principalTags.get("terrakube:project").get(0).asText());
    }

    @Test
    void testNonAwsTokensDoNotContainAwsTagsClaim() throws IOException {
        Job job = createMockJob();

        HashMap<String, String> azureEnv = new HashMap<>();
        azureEnv.put("WORKLOAD_IDENTITY_AUDIENCE_AZURE", "api://AzureADTokenExchange");
        String azureToken = dynamicCredentialsService.generateDynamicCredentialsAzure(job, azureEnv, null).get("ARM_OIDC_TOKEN");
        assertFalse(decodeJwtClaims(azureToken).has("https://aws.amazon.com/tags"),
                "Azure token must not carry the AWS session-tags claim");

        HashMap<String, String> gcpEnv = new HashMap<>();
        gcpEnv.put("WORKLOAD_IDENTITY_AUDIENCE_GCP", "//iam.googleapis.com/pool/provider");
        gcpEnv.put("WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL", "sa@project.iam.gserviceaccount.com");
        String gcpFile = dynamicCredentialsService.generateDynamicCredentialsGcp(job, gcpEnv, null).get("TERRAKUBE_GCP_CREDENTIALS_FILE");
        String gcpToken = new ObjectMapper().readTree(gcpFile).get("access_token").asText();
        assertFalse(decodeJwtClaims(gcpToken).has("https://aws.amazon.com/tags"),
                "GCP token must not carry the AWS session-tags claim");

        String vaultToken = ReflectionTestUtils.invokeMethod(dynamicCredentialsService, "generateJwt",
                job, "vault.example.com", null, null, false);
        assertFalse(decodeJwtClaims(vaultToken).has("https://aws.amazon.com/tags"),
                "Vault token must not carry the AWS session-tags claim");
    }

    @Test
    void testTokenIncludesProjectNameClaimWhenPresent() throws IOException {
        Job job = createMockJob();
        Project project = new Project();
        project.setName("my-project");
        job.getWorkspace().setProject(project);

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AZURE", "api://AzureADTokenExchange");

        String token = dynamicCredentialsService.generateDynamicCredentialsAzure(job, envVariables, null).get("ARM_OIDC_TOKEN");

        JsonNode claims = decodeJwtClaims(token);
        assertTrue(claims.has("terrakube_project_name"), "token must carry terrakube_project_name when a project is set");
        assertEquals("my-project", claims.get("terrakube_project_name").asText());
    }

    @Test
    void testTokenOmitsProjectNameClaimWhenNoProject() throws IOException {
        Job job = createMockJob(); // createMockJob sets the workspace project to null

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AZURE", "api://AzureADTokenExchange");

        String token = dynamicCredentialsService.generateDynamicCredentialsAzure(job, envVariables, null).get("ARM_OIDC_TOKEN");

        assertFalse(decodeJwtClaims(token).has("terrakube_project_name"),
                "terrakube_project_name must be absent when the workspace has no project");
    }

    @Test
    void testSanitizeTagNeutralizesForbiddenCharacters() {
        String sanitized = ReflectionTestUtils.invokeMethod(dynamicCredentialsService, "sanitizeTag", "my*org#name");
        assertEquals("my_org_name", sanitized);
    }

    @Test
    void testJwksEndpointReturnsValidJwkSet() {
        given()
                .when()
                .get("/.well-known/jwks")
                .then()
                .log().all()
                .statusCode(HttpStatus.OK.value())
                .contentType("application/json")
                .body("keys", hasSize(1))
                .body("keys[0].kty", org.hamcrest.Matchers.equalTo("RSA"))
                .body("keys[0].kid", org.hamcrest.Matchers.equalTo("03446895-220d-47e1-9564-4eeaa3691b42"))
                .body("keys[0].use", org.hamcrest.Matchers.equalTo("sig"))
                .body("keys[0].alg", org.hamcrest.Matchers.equalTo("RS512"))
                .body("keys[0].n", notNullValue())
                .body("keys[0].e", notNullValue());
    }

    @Test
    void testJwksEndpointReturnsEmptyKeysWhenNoPublicKey() {
        // Set an invalid public key path
        ReflectionTestUtils.setField(dynamicCredentialsService, "publicKeyPath", "/nonexistent/path.pem");
        ReflectionTestUtils.setField(jwksController, "jwkData", null);

        given()
                .when()
                .get("/.well-known/jwks")
                .then()
                .log().all()
                .statusCode(HttpStatus.OK.value())
                .contentType("application/json")
                .body("keys", anyOf(nullValue(), hasSize(0)));
    }

    @Test
    void testOpenIdConfigurationContainsTerrakubeClaims() {
        given()
                .when()
                .get("/.well-known/openid-configuration")
                .then()
                .log().all()
                .statusCode(HttpStatus.OK.value())
                .body("claims_supported", hasItems(
                        "sub", "aud", "exp", "iat", "iss", "jti",
                        "terrakube_workspace_id",
                        "terrakube_organization_id",
                        "terrakube_job_id",
                        "terrakube_workspace_name",
                        "terrakube_organization_name",
                        "terrakube_project_name",
                        "terrakube_run_phase"
                ));
    }

    @Test
    void testOpenIdConfigurationOmitsRunPhaseClaimWhenDisabled() {
        ReflectionTestUtils.setField(openIdConfigurationController, "runPhaseEnabled", false);
        ReflectionTestUtils.setField(openIdConfigurationController, "openIdConfiguration", null);

        given()
                .when()
                .get("/.well-known/openid-configuration")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("claims_supported", not(hasItem("terrakube_run_phase")));
    }

    @Test
    void testOpenIdConfigurationEndpointReturnsValidConfiguration() {
        given()
                .when()
                .get("/.well-known/openid-configuration")
                .then()
                .log().all()
                .statusCode(HttpStatus.OK.value())
                .contentType("application/json")
                .body("issuer", org.hamcrest.Matchers.equalTo("https://localhost:8080"))
                .body("jwks_uri", org.hamcrest.Matchers.equalTo("https://localhost:8080/.well-known/jwks"))
                .body("response_types_supported", hasItem("id_token"))
                .body("id_token_signing_alg_values_supported", hasItem("RS512"))
                .body("scopes_supported", hasItem("openid"))
                .body("subject_types_supported", hasItem("public"));
    }

    @Test
    void testOpenIdConfigurationUsesOverrideHostname() {
        // Set override hostname
        ReflectionTestUtils.setField(openIdConfigurationController, "openIdConfiguration", null);
        ReflectionTestUtils.setField(openIdConfigurationController, "overrideHostname", "custom.terrakube.io");

        given()
                .when()
                .get("/.well-known/openid-configuration")
                .then()
                .log().all()
                .statusCode(HttpStatus.OK.value())
                .body("issuer", org.hamcrest.Matchers.equalTo("https://custom.terrakube.io"))
                .body("jwks_uri", org.hamcrest.Matchers.equalTo("https://custom.terrakube.io/.well-known/jwks"));
    }

    @Test
    void testGenerateDynamicCredentialsVaultAddsVaultToken() {
        // Setup WireMock to mock Vault's JWT login endpoint
        String mockVaultToken = "hvs.mock-vault-token-12345";

        wireMockServer.stubFor(post(urlEqualTo("/v1/auth/jwt/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format("""
                                {
                                    "auth": {
                                        "client_token": "%s",
                                        "accessor": "mock-accessor",
                                        "policies": ["default"],
                                        "token_policies": ["default"],
                                        "lease_duration": 3600,
                                        "renewable": true
                                    }
                                }
                                """, mockVaultToken))));

        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_VAULT_AUDIENCE", "vault.example.com");
        envVariables.put("VAULT_ADDR", wireMockServer.baseUrl());
        envVariables.put("WORKLOAD_IDENTITY_VAULT_ROLE", "terrakube-role");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsVault(job, envVariables, null);

        assertTrue(result.containsKey("VAULT_TOKEN"));
        assertEquals(mockVaultToken, result.get("VAULT_TOKEN"));

        // Verify the request was made to Vault
        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/auth/jwt/login"))
                .withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    void testGenerateDynamicCredentialsVaultWithCustomAuthPath() {
        String mockVaultToken = "hvs.custom-path-token";

        wireMockServer.stubFor(post(urlEqualTo("/v1/auth/custom-jwt/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format("""
                                {
                                    "auth": {
                                        "client_token": "%s",
                                        "accessor": "mock-accessor",
                                        "policies": ["default"],
                                        "lease_duration": 3600,
                                        "renewable": true
                                    }
                                }
                                """, mockVaultToken))));

        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_VAULT_AUDIENCE", "vault.example.com");
        envVariables.put("VAULT_ADDR", wireMockServer.baseUrl());
        envVariables.put("WORKLOAD_IDENTITY_VAULT_ROLE", "terrakube-role");
        envVariables.put("WORKLOAD_IDENTITY_VAULT_AUTH_PATH", "custom-jwt");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsVault(job, envVariables, null);

        assertEquals(mockVaultToken, result.get("VAULT_TOKEN"));

        // Verify request used custom auth path
        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/auth/custom-jwt/login")));
    }

    @Test
    void testGenerateDynamicCredentialsVaultWithNamespace() {
        String mockVaultToken = "hvs.namespace-token";

        wireMockServer.stubFor(post(urlEqualTo("/v1/auth/jwt/login"))
                .withHeader("X-Vault-Namespace", equalTo("my-namespace"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format("""
                                {
                                    "auth": {
                                        "client_token": "%s",
                                        "accessor": "mock-accessor",
                                        "policies": ["default"],
                                        "lease_duration": 3600,
                                        "renewable": true
                                    }
                                }
                                """, mockVaultToken))));

        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_VAULT_AUDIENCE", "vault.example.com");
        envVariables.put("VAULT_ADDR", wireMockServer.baseUrl());
        envVariables.put("WORKLOAD_IDENTITY_VAULT_ROLE", "terrakube-role");
        envVariables.put("VAULT_NAMESPACE", "my-namespace");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsVault(job, envVariables, null);

        assertEquals(mockVaultToken, result.get("VAULT_TOKEN"));

        // Verify namespace header was included
        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/auth/jwt/login"))
                .withHeader("X-Vault-Namespace", equalTo("my-namespace")));
    }

    @Test
    void testGenerateDynamicCredentialsVaultFailsTheJobWhenVaultDeniesTheLogin() {
        wireMockServer.stubFor(post(urlEqualTo("/v1/auth/jwt/login"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withBody("{\"errors\": [\"permission denied\"]}")));

        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_VAULT_AUDIENCE", "vault.example.com");
        envVariables.put("VAULT_ADDR", wireMockServer.baseUrl());
        envVariables.put("WORKLOAD_IDENTITY_VAULT_ROLE", "terrakube-role");

        // The job must fail before an executor is started, not carry on with an empty VAULT_TOKEN.
        assertThrows(io.terrakube.api.plugin.token.dynamic.DynamicCredentialsException.class,
                () -> dynamicCredentialsService.generateDynamicCredentialsVault(job, envVariables, null));
        assertFalse(envVariables.containsKey("VAULT_TOKEN"));
    }

    @Test
    void testRunPhaseIsNullWhenDisabled() {
        ReflectionTestUtils.setField(dynamicCredentialsService, "runPhaseEnabled", false);
        for (FlowType flowType : FlowType.values()) {
            assertNull(dynamicCredentialsService.runPhase(flowType.name()));
        }
        assertNull(dynamicCredentialsService.runPhase(null));
    }

    @Test
    void testRunPhaseOfEachStepWhenEnabled() {
        assertNull(dynamicCredentialsService.runPhase(null));
        for (FlowType flowType : FlowType.values()) {
            String expected = (flowType == FlowType.terraformApply || flowType == FlowType.terraformDestroy)
                    ? "apply"
                    : "plan";
            assertEquals(expected, dynamicCredentialsService.runPhase(flowType.name()),
                    "Unexpected runPhase for " + flowType);
        }
    }

    @Test
    void testTokenCarriesRunPhaseClaimWithoutModifyingSubjectByDefault() throws IOException {
        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        envVariables.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/apply-role");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsAws(job, envVariables, "plan");
        JsonNode claims = decodeJwtClaims(result.get("TERRAKUBE_AWS_CREDENTIALS_FILE"));

        assertEquals("plan", claims.get("terrakube_run_phase").asText());
        assertFalse(claims.get("sub").asText().contains(":run_phase:"),
                "Subject must remain backward-compatible unless workspace opts in");
    }

    @Test
    void testTokenCarriesSubjectSuffixWhenWorkspaceOptedIn() throws IOException {
        Job job = createMockJob();

        HashMap<String, String> planEnv = new HashMap<>();
        planEnv.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        planEnv.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/apply-role");
        planEnv.put("ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE", "true");

        HashMap<String, String> applyEnv = new HashMap<>(planEnv);
        applyEnv.put("ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE", "true");
        JsonNode applyClaims = decodeJwtClaims(
                dynamicCredentialsService.generateDynamicCredentialsAws(job, applyEnv, "apply")
                        .get("TERRAKUBE_AWS_CREDENTIALS_FILE"));

        JsonNode planClaims = decodeJwtClaims(
                dynamicCredentialsService.generateDynamicCredentialsAws(job, planEnv, "plan")
                        .get("TERRAKUBE_AWS_CREDENTIALS_FILE"));
        assertEquals("plan", planClaims.get("terrakube_run_phase").asText());
        assertTrue(planClaims.get("sub").asText().endsWith(":run_phase:plan"));

        assertEquals("apply", applyClaims.get("terrakube_run_phase").asText());
        assertTrue(applyClaims.get("sub").asText().endsWith(":run_phase:apply"));
    }

    @Test
    void testTokenRejectsInvalidRunPhase() {
        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        envVariables.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/apply-role");

        assertThrows(IllegalArgumentException.class,
                () -> dynamicCredentialsService.generateDynamicCredentialsAws(job, envVariables, "bogus"));
    }

    @Test
    void testKillSwitchSuppressesExplicitRunPhase() throws IOException {
        ReflectionTestUtils.setField(dynamicCredentialsService, "runPhaseEnabled", false);
        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        envVariables.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/apply-role");
        envVariables.put("WORKLOAD_IDENTITY_ROLE_AWS_PLAN", "arn:aws:iam::123456789012:role/plan-role");
        envVariables.put("ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE", "true");
        envVariables.put("ENABLE_AWS_SESSION_TAGS", "true");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsAws(job, envVariables, "plan");
        assertEquals("arn:aws:iam::123456789012:role/apply-role", result.get("AWS_ROLE_ARN"));

        JsonNode claims = decodeJwtClaims(result.get("TERRAKUBE_AWS_CREDENTIALS_FILE"));
        assertFalse(claims.has("terrakube_run_phase"));
        assertFalse(claims.get("sub").asText().contains(":run_phase:"));
        assertFalse(claims.get("https://aws.amazon.com/tags").get("principal_tags").has("terrakube:run_phase"));
    }

    @Test
    void testAwsPlanAndApplyRoleSelectionAndSessionTag() throws IOException {
        Job job = createMockJob();

        HashMap<String, String> planEnv = new HashMap<>();
        planEnv.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        planEnv.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/apply-role");
        planEnv.put("WORKLOAD_IDENTITY_ROLE_AWS_PLAN", "arn:aws:iam::123456789012:role/plan-role");
        planEnv.put("ENABLE_AWS_SESSION_TAGS", "true");

        HashMap<String, String> optedInPlanEnv = new HashMap<>(planEnv);
        optedInPlanEnv.put("ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE", "true");
        HashMap<String, String> applyEnv = new HashMap<>(optedInPlanEnv);

        HashMap<String, String> planResultDefault = dynamicCredentialsService.generateDynamicCredentialsAws(job, planEnv, "plan");
        assertEquals("arn:aws:iam::123456789012:role/plan-role", planResultDefault.get("AWS_ROLE_ARN"));
        JsonNode defaultTags = decodeJwtClaims(planResultDefault.get("TERRAKUBE_AWS_CREDENTIALS_FILE"))
                .get("https://aws.amazon.com/tags").get("principal_tags");
        assertFalse(defaultTags.has("terrakube:run_phase"),
                "terrakube:run_phase session tag must require ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE");

        HashMap<String, String> optedInPlanResult = dynamicCredentialsService.generateDynamicCredentialsAws(job, optedInPlanEnv, "plan");
        JsonNode optedInTags = decodeJwtClaims(optedInPlanResult.get("TERRAKUBE_AWS_CREDENTIALS_FILE"))
                .get("https://aws.amazon.com/tags").get("principal_tags");
        assertEquals("plan", optedInTags.get("terrakube:run_phase").get(0).asText());

        HashMap<String, String> applyResult = dynamicCredentialsService.generateDynamicCredentialsAws(job, applyEnv, "apply");
        assertEquals("arn:aws:iam::123456789012:role/apply-role", applyResult.get("AWS_ROLE_ARN"));
        JsonNode applyTags = decodeJwtClaims(applyResult.get("TERRAKUBE_AWS_CREDENTIALS_FILE"))
                .get("https://aws.amazon.com/tags").get("principal_tags");
        assertEquals("apply", applyTags.get("terrakube:run_phase").get(0).asText());

        HashMap<String, String> missingBaseEnv = new HashMap<>();
        missingBaseEnv.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        HashMap<String, String> missingBaseResult = dynamicCredentialsService.generateDynamicCredentialsAws(job, missingBaseEnv, "apply");
        assertFalse(missingBaseResult.containsKey("AWS_ROLE_ARN"));
    }

    @Test
    void testGcpPlanAndApplyServiceAccountSelection() {
        Job job = createMockJob();

        HashMap<String, String> planEnv = new HashMap<>();
        planEnv.put("WORKLOAD_IDENTITY_AUDIENCE_GCP", "//iam.googleapis.com/projects/123/locations/global/workloadIdentityPools/pool/providers/provider");
        planEnv.put("WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL", "apply-sa@project.iam.gserviceaccount.com");
        planEnv.put("WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL_PLAN", "plan-sa@project.iam.gserviceaccount.com");
        HashMap<String, String> applyEnv = new HashMap<>(planEnv);

        HashMap<String, String> planResult = dynamicCredentialsService.generateDynamicCredentialsGcp(job, planEnv, "plan");
        assertTrue(planResult.get("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE").contains("plan-sa@project.iam.gserviceaccount.com"));

        HashMap<String, String> applyResult = dynamicCredentialsService.generateDynamicCredentialsGcp(job, applyEnv, "apply");
        assertTrue(applyResult.get("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE").contains("apply-sa@project.iam.gserviceaccount.com"));

        HashMap<String, String> fallbackEnv = new HashMap<>();
        fallbackEnv.put("WORKLOAD_IDENTITY_AUDIENCE_GCP", "//iam.googleapis.com/projects/123/locations/global/workloadIdentityPools/pool/providers/provider");
        fallbackEnv.put("WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL", "apply-sa@project.iam.gserviceaccount.com");
        HashMap<String, String> fallbackResult = dynamicCredentialsService.generateDynamicCredentialsGcp(job, fallbackEnv, "plan");
        assertTrue(fallbackResult.get("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE").contains("apply-sa@project.iam.gserviceaccount.com"));
    }

    @Test
    void testAzurePlanAndApplyClientIdSelection() throws IOException {
        Job job = createMockJob();

        HashMap<String, String> planEnv = new HashMap<>();
        planEnv.put("WORKLOAD_IDENTITY_AUDIENCE_AZURE", "api://AzureADTokenExchange");
        planEnv.put("ARM_CLIENT_ID", "apply-client-id");
        planEnv.put("ARM_CLIENT_ID_PLAN", "plan-client-id");
        planEnv.put("ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE", "true");
        HashMap<String, String> applyEnv = new HashMap<>(planEnv);

        HashMap<String, String> planResult = dynamicCredentialsService.generateDynamicCredentialsAzure(job, planEnv, "plan");
        assertEquals("plan-client-id", planResult.get("ARM_CLIENT_ID"));
        assertTrue(decodeJwtClaims(planResult.get("ARM_OIDC_TOKEN")).get("sub").asText().endsWith(":run_phase:plan"));

        HashMap<String, String> applyResult = dynamicCredentialsService.generateDynamicCredentialsAzure(job, applyEnv, "apply");
        assertEquals("apply-client-id", applyResult.get("ARM_CLIENT_ID"));
        assertTrue(decodeJwtClaims(applyResult.get("ARM_OIDC_TOKEN")).get("sub").asText().endsWith(":run_phase:apply"));
    }

    @Test
    void testVaultPlanAndApplyRoleSelection() {
        wireMockServer.stubFor(post(urlEqualTo("/v1/auth/jwt/login"))
                .withRequestBody(matchingJsonPath("$.role", equalTo("plan-role")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"auth\": {\"client_token\": \"plan-token\", \"lease_duration\": 3600}}")));
        wireMockServer.stubFor(post(urlEqualTo("/v1/auth/jwt/login"))
                .withRequestBody(matchingJsonPath("$.role", equalTo("apply-role")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"auth\": {\"client_token\": \"apply-token\", \"lease_duration\": 3600}}")));

        Job job = createMockJob();

        HashMap<String, String> planEnv = new HashMap<>();
        planEnv.put("WORKLOAD_IDENTITY_VAULT_AUDIENCE", "vault.example.com");
        planEnv.put("VAULT_ADDR", wireMockServer.baseUrl());
        planEnv.put("WORKLOAD_IDENTITY_VAULT_ROLE", "apply-role");
        planEnv.put("WORKLOAD_IDENTITY_VAULT_ROLE_PLAN", "plan-role");
        HashMap<String, String> applyEnv = new HashMap<>(planEnv);

        HashMap<String, String> planResult = dynamicCredentialsService.generateDynamicCredentialsVault(job, planEnv, "plan");
        assertEquals("plan-token", planResult.get("VAULT_TOKEN"));

        HashMap<String, String> applyResult = dynamicCredentialsService.generateDynamicCredentialsVault(job, applyEnv, "apply");
        assertEquals("apply-token", applyResult.get("VAULT_TOKEN"));
    }

    @Test
    void testRunPhaseOptInOnlyEnablesForTrue() throws IOException {
        Job job = createMockJob();

        for (String disabledValue : new String[] {"no", "off", "disabled", "0", "1", "false", "", "   "}) {
            HashMap<String, String> env = new HashMap<>();
            env.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
            env.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/apply-role");
            env.put("ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE", disabledValue);
            env.put("ENABLE_AWS_SESSION_TAGS", "true");

            JsonNode claims = decodeJwtClaims(
                    dynamicCredentialsService.generateDynamicCredentialsAws(job, env, "plan")
                            .get("TERRAKUBE_AWS_CREDENTIALS_FILE"));
            assertFalse(claims.get("sub").asText().contains(":run_phase:"),
                    "Expected subject unchanged when ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE='" + disabledValue + "'");
            assertFalse(claims.get("https://aws.amazon.com/tags").get("principal_tags").has("terrakube:run_phase"),
                    "Expected session tag omitted when ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE='" + disabledValue + "'");
        }

        for (String enabledValue : new String[] {"true", " TRUE "}) {
            HashMap<String, String> env = new HashMap<>();
            env.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
            env.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/apply-role");
            env.put("ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE", enabledValue);
            env.put("ENABLE_AWS_SESSION_TAGS", "true");

            JsonNode claims = decodeJwtClaims(
                    dynamicCredentialsService.generateDynamicCredentialsAws(job, env, "apply")
                            .get("TERRAKUBE_AWS_CREDENTIALS_FILE"));
            assertTrue(claims.get("sub").asText().endsWith(":run_phase:apply"),
                    "Expected subject suffix when ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE='" + enabledValue + "'");
            assertEquals("apply",
                    claims.get("https://aws.amazon.com/tags").get("principal_tags").get("terrakube:run_phase").get(0).asText());
        }
    }

    @Test
    void testCustomScriptsRunPhaseFollowsPrecedingApplyOrDestroy() {
        String applyTemplateYaml = """
                flow:
                  - type: customScripts
                    step: 50
                  - type: terraformPlan
                    step: 100
                  - type: customScripts
                    step: 150
                  - type: approval
                    step: 200
                    team: ops
                  - type: terraformApply
                    step: 300
                  - type: customScripts
                    step: 400
                """;
        Job applyJob = createMockJob();
        applyJob.setTcl(Base64.getEncoder().encodeToString(applyTemplateYaml.getBytes(StandardCharsets.UTF_8)));

        assertEquals("plan", dynamicCredentialsService.runPhase(applyJob, flow(FlowType.customScripts, 50)));
        assertEquals("plan", dynamicCredentialsService.runPhase(applyJob, flow(FlowType.customScripts, 150)));
        assertEquals("apply", dynamicCredentialsService.runPhase(applyJob, flow(FlowType.customScripts, 400)));

        String destroyTemplateYaml = """
                flow:
                  - type: terraformPlanDestroy
                    step: 100
                  - type: customScripts
                    step: 150
                  - type: terraformDestroy
                    step: 200
                  - type: customScripts
                    step: 300
                """;
        Job destroyJob = createMockJob();
        destroyJob.setTcl(Base64.getEncoder().encodeToString(destroyTemplateYaml.getBytes(StandardCharsets.UTF_8)));

        assertEquals("plan", dynamicCredentialsService.runPhase(destroyJob, flow(FlowType.customScripts, 150)));
        assertEquals("apply", dynamicCredentialsService.runPhase(destroyJob, flow(FlowType.customScripts, 300)));

        String standaloneYaml = """
                flow:
                  - type: customScripts
                    step: 100
                """;
        Job standaloneJob = createMockJob();
        standaloneJob.setTcl(Base64.getEncoder().encodeToString(standaloneYaml.getBytes(StandardCharsets.UTF_8)));
        assertEquals("plan", dynamicCredentialsService.runPhase(standaloneJob, flow(FlowType.customScripts, 100)));

        ReflectionTestUtils.setField(dynamicCredentialsService, "runPhaseEnabled", false);
        assertNull(dynamicCredentialsService.runPhase(applyJob, flow(FlowType.customScripts, 400)));
    }

    private static Flow flow(FlowType type, int step) {
        Flow flow = new Flow();
        flow.setType(type.name());
        flow.setStep(step);
        return flow;
    }

}