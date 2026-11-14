package io.terrakube.api.plugin.token.dynamic;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Vault login runs while the scheduler holds a job's execution lock, so it has to finish or
 * fail in bounded time, fail the job instead of continuing without a token, and keep the JWT,
 * the Vault response and the client token out of logs and messages.
 */
class DynamicCredentialsVaultTest {

    private static final String LOGIN = "/v1/auth/jwt/login";
    private static final String CLIENT_TOKEN = "hvs.secret-client-token";

    @TempDir
    static Path tempDir;

    private static Path privateKeyFile;

    private WireMockServer vault;
    private DynamicCredentialsService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKeyFile = tempDir.resolve("private.pem");
        Files.writeString(privateKeyFile, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----");
    }

    @BeforeEach
    void setUp() {
        vault = new WireMockServer(options().dynamicPort());
        vault.start();

        service = new DynamicCredentialsService();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "webClientBuilder", WebClient.builder());
        ReflectionTestUtils.setField(service, "hostname", "terrakube.example.com");
        ReflectionTestUtils.setField(service, "overrideHostname", "");
        ReflectionTestUtils.setField(service, "privateKeyPath", privateKeyFile.toString());
        ReflectionTestUtils.setField(service, "kid", "kid");
        ReflectionTestUtils.setField(service, "dynamicCredentialTtl", 30);
        ReflectionTestUtils.setField(service, "vaultConnectTimeoutSeconds", 3);
        ReflectionTestUtils.setField(service, "vaultReadTimeoutSeconds", 10);
        ReflectionTestUtils.setField(service, "vaultDeadlineSeconds", 20);

        logs = new ListAppender<>();
        logs.start();
        serviceLogger = (Logger) LoggerFactory.getLogger(DynamicCredentialsService.class);
        serviceLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
        vault.stop();
    }

    @Test
    void successfulLoginPutsTheClientTokenInTheEnvironment() {
        vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson(loginResponse(CLIENT_TOKEN))));

        HashMap<String, String> result = service.generateDynamicCredentialsVault(job(), env(), null);

        assertThat(result).containsEntry("VAULT_TOKEN", CLIENT_TOKEN);
        vault.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    void authenticationDenialFailsTheJobAndIsNotRetried() {
        vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(403)
                .withBody("{\"errors\": [\"permission denied\"]}")));
        HashMap<String, String> env = env();

        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), env, null))
                .isInstanceOf(DynamicCredentialsException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageNotContaining("permission denied");

        assertThat(env).doesNotContainKey("VAULT_TOKEN");
        vault.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    void aServerErrorIsRetriedOnceThenFails() {
        vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(503).withBody("sealed")));

        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), env(), null))
                .isInstanceOf(DynamicCredentialsException.class)
                .hasMessageNotContaining("sealed");

        vault.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    void aTransientServerErrorThenSuccessReturnsTheToken() {
        vault.stubFor(post(urlEqualTo(LOGIN)).inScenario("flaky").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("up"));
        vault.stubFor(post(urlEqualTo(LOGIN)).inScenario("flaky").whenScenarioStateIs("up")
                .willReturn(okJson(loginResponse(CLIENT_TOKEN))));

        assertThat(service.generateDynamicCredentialsVault(job(), env(), null)).containsEntry("VAULT_TOKEN", CLIENT_TOKEN);
        vault.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    void aDelayedResponseFailsAtTheReadTimeoutNotAfterTheLongDelay() {
        ReflectionTestUtils.setField(service, "vaultReadTimeoutSeconds", 1);
        ReflectionTestUtils.setField(service, "vaultDeadlineSeconds", 5);
        vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson(loginResponse(CLIENT_TOKEN)).withFixedDelay(30_000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), env(), null))
                .isInstanceOf(DynamicCredentialsException.class);

        assertThat(secondsSince(start)).isLessThan(8);
    }

    @Test
    void theOverallDeadlineBoundsTheLoginIncludingRetries() {
        ReflectionTestUtils.setField(service, "vaultReadTimeoutSeconds", 10);
        ReflectionTestUtils.setField(service, "vaultDeadlineSeconds", 2);
        vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson(loginResponse(CLIENT_TOKEN)).withFixedDelay(30_000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), env(), null))
                .isInstanceOf(DynamicCredentialsException.class)
                .hasMessageContaining("within 2 seconds");

        assertThat(secondsSince(start)).isLessThan(5);
    }

    @Test
    void aRedirectIsNotFollowedAndFailsTheJobWithItsStatus() {
        vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(307)
                .withHeader("Location", "http://127.0.0.1:1/elsewhere")));
        HashMap<String, String> env = env();

        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), env, null))
                .isInstanceOf(DynamicCredentialsException.class)
                .hasMessageContaining("HTTP 307");

        assertThat(env).doesNotContainKey("VAULT_TOKEN");
        vault.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    void aConnectionThatCannotBeEstablishedFailsAtTheConnectTimeout() {
        ReflectionTestUtils.setField(service, "vaultConnectTimeoutSeconds", 1);
        ReflectionTestUtils.setField(service, "vaultDeadlineSeconds", 5);
        HashMap<String, String> env = env();
        // 10.255.255.1 is not routable from a test machine, so the TCP handshake stalls instead of failing fast.
        env.put("VAULT_ADDR", "http://10.255.255.1:8200");

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), env, null))
                .isInstanceOf(DynamicCredentialsException.class);

        assertThat(secondsSince(start)).isLessThan(8);
    }

    @Test
    void aConnectionResetFailsTheJob() {
        vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), env(), null))
                .isInstanceOf(DynamicCredentialsException.class);
    }

    @Test
    void malformedAndEmptyResponsesFailTheJobWithoutAToken() {
        for (String body : new String[] {"", "not json", "{}", "{\"auth\": null}", "{\"auth\": {}}",
                "{\"auth\": {\"client_token\": \"\"}}", "{\"auth\": {\"client_token\": \"  \"}}",
                "{\"auth\": {\"client_token\": null}}", "{\"auth\": {\"client_token\": 42}}"}) {
            vault.resetAll();
            vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson(body)));
            HashMap<String, String> env = env();

            assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), env, null))
                    .as("response body: '%s'", body)
                    .isInstanceOf(DynamicCredentialsException.class);
            assertThat(env).as("response body: '%s'", body).doesNotContainKey("VAULT_TOKEN");
        }
    }

    @Test
    void missingConfigurationFailsBeforeAnyRequest() {
        HashMap<String, String> noAddress = env();
        noAddress.remove("VAULT_ADDR");
        HashMap<String, String> noRole = env();
        noRole.remove("WORKLOAD_IDENTITY_VAULT_ROLE");

        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), noAddress, null))
                .isInstanceOf(DynamicCredentialsException.class).hasMessageContaining("VAULT_ADDR");
        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), noRole, null))
                .isInstanceOf(DynamicCredentialsException.class).hasMessageContaining("WORKLOAD_IDENTITY_VAULT_ROLE");

        vault.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    void aMissingSigningKeyFailsInsteadOfSendingAnEmptyJwt() {
        ReflectionTestUtils.setField(service, "privateKeyPath", "");

        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), env(), null))
                .isInstanceOf(DynamicCredentialsException.class);

        vault.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    void roleAndJwtAreSentAsJsonNotInterpolatedIntoIt() {
        vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson(loginResponse(CLIENT_TOKEN))));
        HashMap<String, String> env = env();
        env.put("WORKLOAD_IDENTITY_VAULT_ROLE", "role\",\"admin\":\"true");

        service.generateDynamicCredentialsVault(job(), env, null);

        vault.verify(postRequestedFor(urlEqualTo(LOGIN))
                .withRequestBody(matchingJsonPath("$.role", equalTo("role\",\"admin\":\"true")))
                .withRequestBody(notMatching(".*\"admin\":\"true\"\\s*[,}].*")));
    }

    @Test
    void credentialsAreNeverLogged() {
        vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson(loginResponse(CLIENT_TOKEN))));
        HashMap<String, String> env = env();
        env.put("WORKLOAD_IDENTITY_AUDIENCE_AZURE", "api://AzureADTokenExchange");
        env.put("WORKLOAD_IDENTITY_AUDIENCE_AWS", "sts.amazonaws.com");
        env.put("WORKLOAD_IDENTITY_ROLE_AWS", "arn:aws:iam::123456789012:role/test-role");
        env.put("WORKLOAD_IDENTITY_AUDIENCE_GCP", "//iam.googleapis.com/pool/provider");
        env.put("WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL", "sa@project.iam.gserviceaccount.com");

        service.generateDynamicCredentialsVault(job(), env, null);
        service.generateDynamicCredentialsAzure(job(), env, null);
        service.generateDynamicCredentialsAws(job(), env, null);
        service.generateDynamicCredentialsGcp(job(), env, null);

        String everything = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        for (String secret : new String[] {CLIENT_TOKEN, env.get("ARM_OIDC_TOKEN"),
                env.get("TERRAKUBE_AWS_CREDENTIALS_FILE"), env.get("TERRAKUBE_GCP_CREDENTIALS_FILE")}) {
            assertThat(secret).isNotBlank();
            assertThat(everything).doesNotContain(secret);
        }
        assertThat(everything).doesNotContain("client_token");
    }

    @Test
    void failureLogsAndMessagesDoNotContainTheResponseBody() {
        vault.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(500)
                .withBody("{\"auth\":{\"client_token\":\"" + CLIENT_TOKEN + "\"}} stack trace")));

        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(job(), env(), null))
                .isInstanceOf(DynamicCredentialsException.class)
                .hasMessageNotContaining(CLIENT_TOKEN);

        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(CLIENT_TOKEN).doesNotContain("stack trace");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void usesPlanRoleOnPlanAndBaseRoleWhenPlanRoleIsBlank() {
        vault.stubFor(post(urlEqualTo(LOGIN))
                .withRequestBody(matchingJsonPath("$.role", equalTo("terrakube-plan-role")))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(loginResponse("plan-vault-token"))));
        vault.stubFor(post(urlEqualTo(LOGIN))
                .withRequestBody(matchingJsonPath("$.role", equalTo("terrakube-role")))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(loginResponse(CLIENT_TOKEN))));

        HashMap<String, String> withPlanRole = env();
        withPlanRole.put("WORKLOAD_IDENTITY_VAULT_ROLE_PLAN", "terrakube-plan-role");
        service.generateDynamicCredentialsVault(job(), withPlanRole, "plan");
        assertThat(withPlanRole).containsEntry("VAULT_TOKEN", "plan-vault-token");

        HashMap<String, String> withBlankPlanRole = env();
        withBlankPlanRole.put("WORKLOAD_IDENTITY_VAULT_ROLE_PLAN", "   ");
        service.generateDynamicCredentialsVault(job(), withBlankPlanRole, "plan");
        assertThat(withBlankPlanRole).containsEntry("VAULT_TOKEN", CLIENT_TOKEN);
    }

    @Test
    void applyFailsBeforeHttpWhenBaseRoleUnsetEvenIfPlanRoleConfigured() {
        Job testJob = job();
        HashMap<String, String> env = env();
        env.remove("WORKLOAD_IDENTITY_VAULT_ROLE");
        env.put("WORKLOAD_IDENTITY_VAULT_ROLE_PLAN", "terrakube-plan-role");

        assertThatThrownBy(() -> service.generateDynamicCredentialsVault(testJob, env, "apply"))
                .isInstanceOf(DynamicCredentialsException.class);
        assertThat(vault.getAllServeEvents()).isEmpty();
    }

    @Test
    void planWithOptInNeverLogsCredentials() {
        vault.stubFor(post(urlEqualTo(LOGIN))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(loginResponse(CLIENT_TOKEN))));

        HashMap<String, String> env = env();
        env.put("WORKLOAD_IDENTITY_VAULT_ROLE_PLAN", "terrakube-plan-role");
        env.put("ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE", "true");

        service.generateDynamicCredentialsVault(job(), env, "plan");

        assertThat(env).containsEntry("VAULT_TOKEN", CLIENT_TOKEN);
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(CLIENT_TOKEN);
            assertThat(event.getFormattedMessage()).doesNotContain("eyJ");
        });
    }

    private HashMap<String, String> env() {
        HashMap<String, String> env = new HashMap<>();
        env.put("WORKLOAD_IDENTITY_VAULT_AUDIENCE", "vault.example.com");
        env.put("VAULT_ADDR", vault.baseUrl());
        env.put("WORKLOAD_IDENTITY_VAULT_ROLE", "terrakube-role");
        return env;
    }

    private static Job job() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        organization.setName("org");
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("workspace");
        Job job = new Job();
        job.setId(1);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        return job;
    }

    private static String loginResponse(String token) {
        return "{\"auth\": {\"client_token\": \"" + token + "\", \"lease_duration\": 3600}}";
    }

    private static long secondsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000L;
    }
}
