package io.terrakube.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookV1MigratedSecurityIntegrationTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @Autowired
    private WebhookRepository webhookRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private WireMockServer attackerServer;
    private final List<UUID> createdWebhooks = new ArrayList<>();
    private final List<UUID> createdWorkspaces = new ArrayList<>();
    private final List<UUID> createdVcs = new ArrayList<>();

    @BeforeEach
    void setUpAttackServers() {
        wireMockServer.resetAll();
        attackerServer = new WireMockServer(options().dynamicPort());
        attackerServer.start();
        attackerServer.stubFor(WireMock.any(urlPathMatching(".*"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));
    }

    @AfterEach
    void cleanUpFixtures() {
        attackerServer.stop();
        for (UUID webhookId : createdWebhooks) {
            webhookRepository.findById(webhookId).ifPresent(webhookRepository::delete);
        }
        createdWebhooks.clear();

        for (UUID workspaceId : createdWorkspaces) {
            workspaceRepository.findById(workspaceId).ifPresent(workspace -> {
                workspace.setVcs(null);
                workspace.setDeleted(true);
                workspaceRepository.saveAndFlush(workspace);
            });
        }
        createdWorkspaces.clear();

        for (UUID vcsId : createdVcs) {
            vcsRepository.findById(vcsId).ifPresent(vcsRepository::delete);
        }
        createdVcs.clear();
    }

    @Test
    void migratedV2WorkspaceReturnsUniformOkAndNeverContactsAttackerOrVcs() throws Exception {
        Fixture fixture = createWorkspaceWithWebhook(VcsType.GITHUB, "https://github.com/acme/repo.git", true);
        String payload = buildSignedRoundTripGithubPrPayload(attackerBaseUrl() + "/repos/acme/repo/pulls/1");
        String signature = signHmacSha256(fixture.base64Secret(), payload);

        given()
                .contentType("application/json")
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", signature)
                .body(payload)
                .when()
                .post("/webhook/v1/" + fixture.webhook().getId())
                .then()
                .statusCode(HttpStatus.OK.value());

        assertThat(attackerServer.getAllServeEvents()).isEmpty();
        assertThat(wireMockServer.getAllServeEvents()).isEmpty();
    }

    @Test
    void missingWebhookUuidReturnsUniformOkWithoutOracle() {
        given()
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/webhook/v1/" + UUID.randomUUID())
                .then()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void bitbucketWorkspaceWithMigratedV2StillProcessesV1AndBlocksAttackerDiff() throws Exception {
        Fixture fixture = createWorkspaceWithWebhook(VcsType.BITBUCKET, "https://bitbucket.org/acme/repo.git", true);

        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("pullrequest", Map.of(
                "id", 15,
                "source", Map.of("branch", Map.of("name", "feature"), "commit", Map.of("hash", "abc123")),
                "destination", Map.of("branch", Map.of("name", "main")),
                "links", Map.of("diff", Map.of("href", attackerBaseUrl() + "/steal-bitbucket-token"))));
        String payload = objectMapper.writeValueAsString(payloadMap);
        String signature = signHmacSha256(fixture.base64Secret(), payload);

        given()
                .contentType("application/json")
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", signature)
                .body(payload)
                .when()
                .post("/webhook/v1/" + fixture.webhook().getId())
                .then()
                .statusCode(HttpStatus.OK.value());

        assertThat(attackerServer.getAllServeEvents()).isEmpty();
    }

    @Test
    void unmigratedGithubV1WebhookBlocksAttackerPullRequestUrlOverHttp() throws Exception {
        Fixture fixture = createWorkspaceWithWebhook(VcsType.GITHUB, "https://github.com/acme/repo.git", false);
        String payload = buildSignedRoundTripGithubPrPayload(attackerBaseUrl() + "/repos/acme/repo/pulls/1");
        String signature = signHmacSha256(fixture.base64Secret(), payload);

        given()
                .contentType("application/json")
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", signature)
                .body(payload)
                .when()
                .post("/webhook/v1/" + fixture.webhook().getId())
                .then()
                .statusCode(HttpStatus.OK.value());

        assertThat(attackerServer.getAllServeEvents()).isEmpty();
    }

    @Test
    void unmigratedBitbucketV1WebhookBlocksAttackerDiffUrlOverHttp() throws Exception {
        Fixture fixture = createWorkspaceWithWebhook(VcsType.BITBUCKET, "https://bitbucket.org/acme/repo.git", false);

        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("pullrequest", Map.of(
                "id", 15,
                "source", Map.of("branch", Map.of("name", "feature"), "commit", Map.of("hash", "abc123")),
                "destination", Map.of("branch", Map.of("name", "main")),
                "links", Map.of("diff", Map.of("href", attackerBaseUrl() + "/steal-bitbucket-token"))));
        String payload = objectMapper.writeValueAsString(payloadMap);
        String signature = signHmacSha256(fixture.base64Secret(), payload);

        given()
                .contentType("application/json")
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", signature)
                .body(payload)
                .when()
                .post("/webhook/v1/" + fixture.webhook().getId())
                .then()
                .statusCode(HttpStatus.OK.value());

        assertThat(attackerServer.getAllServeEvents()).isEmpty();
    }

    @Test
    void invalidUuidReturnsUnauthorized() {
        given()
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/webhook/v1/not-a-uuid")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    private String attackerBaseUrl() {
        return "http://localhost:" + attackerServer.port();
    }

    private String buildSignedRoundTripGithubPrPayload(String pullRequestUrl) throws Exception {
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("action", "opened");
        payloadMap.put("number", 1);
        payloadMap.put("repository", Map.of("owner", Map.of("login", "acme"), "name", "repo"));
        payloadMap.put("pull_request", Map.of(
                "user", Map.of("login", "attacker"),
                "head", Map.of("sha", "abc123", "ref", "feature"),
                "base", Map.of("ref", "main"),
                "url", pullRequestUrl));
        return objectMapper.writeValueAsString(payloadMap);
    }

    private Fixture createWorkspaceWithWebhook(VcsType vcsType, String source, boolean migratedV2) {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();

        Vcs vcs = new Vcs();
        vcs.setName("ghsa-test-vcs-" + UUID.randomUUID());
        vcs.setDescription("Security test VCS");
        vcs.setVcsType(vcsType);
        vcs.setApiUrl("http://localhost:" + wireMockServer.port());
        vcs.setAccessToken("secret-vcs-token");
        vcs.setOrganization(organization);
        vcs = vcsRepository.saveAndFlush(vcs);
        createdVcs.add(vcs.getId());

        Workspace workspace = new Workspace();
        workspace.setName("ghsa-test-ws-" + UUID.randomUUID());
        workspace.setOrganization(organization);
        workspace.setSource(source);
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.13.1");
        workspace.setVcs(vcs);
        workspace = workspaceRepository.saveAndFlush(workspace);
        createdWorkspaces.add(workspace.getId());

        Webhook webhook = new Webhook();
        webhook.setId(UUID.randomUUID());
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(migratedV2);
        webhook = webhookRepository.saveAndFlush(webhook);
        createdWebhooks.add(webhook.getId());

        String secret = Base64.getEncoder().encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));
        return new Fixture(workspace, webhook, secret);
    }

    private static String signHmacSha256(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private record Fixture(Workspace workspace, Webhook webhook, String base64Secret) {
    }
}
