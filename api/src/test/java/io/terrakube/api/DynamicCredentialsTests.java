package io.terrakube.api;

import io.terrakube.api.plugin.token.dynamic.DynamicCredentialsService;
import io.terrakube.api.plugin.token.dynamic.JwksController;
import io.terrakube.api.plugin.token.dynamic.OpenIdConfigurationController;
import io.terrakube.api.rs.job.Job;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;


import java.io.IOException;
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

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsAzure(job, envVariables);

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

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsAws(job, envVariables);

        assertTrue(result.containsKey("TERRAKUBE_AWS_CREDENTIALS_FILE"));
        assertTrue(result.containsKey("AWS_ROLE_ARN"));
        assertTrue(result.containsKey("AWS_WEB_IDENTITY_TOKEN_FILE"));
        assertEquals("arn:aws:iam::123456789012:role/test-role", result.get("AWS_ROLE_ARN"));
        assertFalse(result.get("TERRAKUBE_AWS_CREDENTIALS_FILE").isEmpty(), "JWT token should be generated");
    }

    @Test
    void testGenerateDynamicCredentialsGcpAddsRequiredVariables() {
        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_AUDIENCE_GCP", "//iam.googleapis.com/projects/123/locations/global/workloadIdentityPools/pool/providers/provider");
        envVariables.put("WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL", "sa@project.iam.gserviceaccount.com");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsGcp(job, envVariables);

        assertTrue(result.containsKey("TERRAKUBE_GCP_CREDENTIALS_FILE"));
        assertTrue(result.containsKey("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE"));
        assertTrue(result.containsKey("GOOGLE_APPLICATION_CREDENTIALS"));
        assertTrue(result.get("TERRAKUBE_GCP_CREDENTIALS_FILE").contains("access_token"));
    }

    private Job createMockJob() {
        var organization = organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        var workspace = workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();

        Job job = new Job();
        job.setId(999);
        job.setOrganization(organization);
        job.setWorkspace(workspace);

        return job;
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
                .body("keys[0].alg", org.hamcrest.Matchers.equalTo("RS256"))
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
                        "terrakube_organization_name"
                ));
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
                .body("id_token_signing_alg_values_supported", hasItem("RS256"))
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

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsVault(job, envVariables);

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

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsVault(job, envVariables);

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

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsVault(job, envVariables);

        assertEquals(mockVaultToken, result.get("VAULT_TOKEN"));

        // Verify namespace header was included
        wireMockServer.verify(postRequestedFor(urlEqualTo("/v1/auth/jwt/login"))
                .withHeader("X-Vault-Namespace", equalTo("my-namespace")));
    }

    @Test
    void testGenerateDynamicCredentialsVaultHandlesErrorGracefully() {
        // Simulate Vault returning an error
        wireMockServer.stubFor(post(urlEqualTo("/v1/auth/jwt/login"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withBody("{\"errors\": [\"permission denied\"]}")));

        Job job = createMockJob();

        HashMap<String, String> envVariables = new HashMap<>();
        envVariables.put("WORKLOAD_IDENTITY_VAULT_AUDIENCE", "vault.example.com");
        envVariables.put("VAULT_ADDR", wireMockServer.baseUrl());
        envVariables.put("WORKLOAD_IDENTITY_VAULT_ROLE", "terrakube-role");

        HashMap<String, String> result = dynamicCredentialsService.generateDynamicCredentialsVault(job, envVariables);

        // Should still contain VAULT_TOKEN key but with empty value on error
        assertTrue(result.containsKey("VAULT_TOKEN"));
        assertEquals("", result.get("VAULT_TOKEN"));
    }


}