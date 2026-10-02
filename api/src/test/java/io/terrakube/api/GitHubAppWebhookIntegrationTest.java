package io.terrakube.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import io.terrakube.api.plugin.vcs.RepoUrlNormalizer;
import io.terrakube.api.plugin.vcs.RepoWebhookDeliveryTransactions;
import io.terrakube.api.plugin.vcs.RepoWebhookDispatchService;
import io.terrakube.api.repository.RepoWebhookDeliveryRepository;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.RepoWebhookDelivery;
import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.workspace.Workspace;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// End-to-end through the real HTTP endpoint (security filter chain, raw-body binding, H2 schema
// from Liquibase), complementing the scoping/lifecycle unit tests in RepoWebhookServiceTest.
class GitHubAppWebhookIntegrationTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String TEMPLATE_ID = "42201234-a5e2-4c62-b2fc-9729ca6b4515";
    private static final String ATOMIC_CONTENT_TYPE = "application/vnd.api+json;ext=\"https://jsonapi.org/ext/atomic\"";
    private static final String SECRET = "app-webhook-secret";

    @Autowired
    RepoWebhookRepository repoWebhookRepository;

    @Autowired
    RepoWebhookDeliveryRepository repoWebhookDeliveryRepository;

    @Autowired
    WebhookRepository webhookRepository;

    @Autowired
    RepoWebhookDeliveryTransactions repoWebhookDeliveryTransactions;

    @Autowired
    RepoWebhookDispatchService repoWebhookDispatchService;

    private String repoUrl;
    private RepoWebhook repoWebhook;
    private final List<Vcs> createdVcs = new ArrayList<>();
    private final List<String> createdRepoUrls = new ArrayList<>();
    private final List<Workspace> createdWorkspaces = new ArrayList<>();

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();

        repoUrl = freshRepoUrl();
        repoWebhook = new RepoWebhook();
        repoWebhook.setRepositoryUrl(repoUrl);
        repoWebhook.setWebhookSecret(UUID.randomUUID().toString());
        repoWebhook.setVcs(saveVcs(VcsConnectionType.OAUTH, false));
        repoWebhook = repoWebhookRepository.saveAndFlush(repoWebhook);
    }

    // Leaves no VCS, RepoWebhook or delivery behind for the other tests sharing this database.
    @AfterEach
    void cleanup() {
        for (Workspace workspace : createdWorkspaces) {
            workspaceRepository.findById(workspace.getId()).ifPresent(ws -> {
                ws.setDeleted(true);
                ws.setVcs(null);
                workspaceRepository.save(ws);
            });
        }
        for (String url : createdRepoUrls) {
            repoWebhookRepository.findByRepositoryUrl(url).ifPresent(rw -> {
                repoWebhookDeliveryRepository.deleteAll(repoWebhookDeliveryRepository.findAll().stream()
                        .filter(d -> d.getRepoWebhook().getId().equals(rw.getId())).toList());
                repoWebhookRepository.delete(rw);
            });
        }
        createdVcs.forEach(vcs -> vcsRepository.deleteById(vcs.getId()));
        createdWorkspaces.clear();
        createdRepoUrls.clear();
        createdVcs.clear();
    }

    @Test
    void signedPushIsEnqueuedForTheVcsAndProcessed() throws Exception {
        Vcs vcs = saveVcs(VcsConnectionType.STANDALONE, true);
        String guid = UUID.randomUUID().toString();

        post(vcs.getId().toString(), pushPayload(), sign(SECRET, pushPayload()), "push", guid)
                .statusCode(HttpStatus.OK.value());

        List<RepoWebhookDelivery> deliveries = deliveriesForRepo();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getDedupeKey()).isEqualTo(sign(SECRET, pushPayload()));
        assertThat(deliveries.get(0).getVcs().getId()).isEqualTo(vcs.getId());
        // Claim + fan-out run outside any session: a lazy-loading slip on the delivery VCS would
        // surface here as a PENDING/FAILED row instead of PROCESSED.
        assertThat(awaitStatus(deliveries.get(0).getId())).isEqualTo(RepoWebhookDeliveryStatus.PROCESSED);
    }

    @Test
    void redeliveriesAndReplaysUnderANewDeliveryGuidProduceOneDelivery() throws Exception {
        Vcs vcs = saveVcs(VcsConnectionType.STANDALONE, true);
        String guid = UUID.randomUUID().toString();

        for (String deliveryGuid : List.of(guid, guid, UUID.randomUUID().toString())) {
            post(vcs.getId().toString(), pushPayload(), sign(SECRET, pushPayload()), "push", deliveryGuid)
                    .statusCode(HttpStatus.OK.value());
        }

        assertThat(deliveriesForRepo()).hasSize(1);
    }

    @Test
    void appDeliveryIsDroppedWhenItsVcsLeftAppModeBeforeProcessing() throws Exception {
        Vcs vcs = saveVcs(VcsConnectionType.STANDALONE, true);
        UUID deliveryId = repoWebhookDeliveryTransactions.enqueue(repoWebhook, pushPayload(),
                "{\"x-github-event\":\"push\"}", vcs, sign(SECRET, pushPayload()));
        vcs.setAppWebhookEnabled(false);
        vcsRepository.saveAndFlush(vcs);

        repoWebhookDispatchService.attemptDelivery(deliveryId);

        assertThat(awaitStatus(deliveryId)).isEqualTo(RepoWebhookDeliveryStatus.PROCESSED);
        assertThat(repoWebhookDeliveryRepository.findById(deliveryId).orElseThrow().getLastError())
                .contains("App webhook mode");
    }

    @Test
    void prFilesAreFetchedFromTheVcsApiUrlNeverFromAForgedPayloadUrl() throws Exception {
        Vcs vcs = saveVcs(VcsConnectionType.STANDALONE, true);
        wireMockServer.stubFor(get(urlPathMatching("/repos/acme/.*/pulls/1/files")).willReturn(okJson("[]")));
        String forgedUrl = "http://127.0.0.1:" + wireMockServer.port() + "/forged/repos/acme/x/pulls/1";
        String payload = "{\"action\":\"opened\",\"number\":1,"
                + "\"pull_request\":{\"url\":\"" + forgedUrl + "\",\"head\":{\"sha\":\"abc\",\"ref\":\"f\"},"
                + "\"user\":{\"login\":\"octo\"}},"
                + "\"repository\":{\"clone_url\":\"" + repoUrl + ".git\",\"name\":\"x\",\"owner\":{\"login\":\"acme\"}}}";

        post(vcs.getId().toString(), payload, sign(SECRET, payload), "pull_request", UUID.randomUUID().toString())
                .statusCode(HttpStatus.OK.value());

        assertThat(awaitStatus(deliveriesForRepo().get(0).getId())).isEqualTo(RepoWebhookDeliveryStatus.PROCESSED);
        wireMockServer.verify(0, anyRequestedFor(urlMatching("/forged.*")));
        wireMockServer.verify(moreThanOrExactly(1), getRequestedFor(urlPathMatching(
                "/repos" + java.net.URI.create(repoUrl).getPath() + "/pulls/1/files")));
    }

    @Test
    void unauthenticatedRequestsAreRejectedWithoutWritingADelivery() throws Exception {
        Vcs enabled = saveVcs(VcsConnectionType.STANDALONE, true);
        Vcs flagOff = saveVcs(VcsConnectionType.STANDALONE, false);
        Vcs notStandalone = saveVcs(VcsConnectionType.OAUTH, true);
        String signature = sign(SECRET, pushPayload());

        post(enabled.getId().toString(), pushPayload(), sign("wrong-secret", pushPayload()), "push", "g1")
                .statusCode(HttpStatus.UNAUTHORIZED.value());
        post(enabled.getId().toString(), pushPayload(), null, "push", "g2")
                .statusCode(HttpStatus.UNAUTHORIZED.value());
        post(flagOff.getId().toString(), pushPayload(), signature, "push", "g3")
                .statusCode(HttpStatus.UNAUTHORIZED.value());
        post(notStandalone.getId().toString(), pushPayload(), signature, "push", "g4")
                .statusCode(HttpStatus.UNAUTHORIZED.value());
        post(UUID.randomUUID().toString(), pushPayload(), signature, "push", "g5")
                .statusCode(HttpStatus.UNAUTHORIZED.value());
        post("not-a-uuid", pushPayload(), signature, "push", "g6")
                .statusCode(HttpStatus.UNAUTHORIZED.value());

        assertThat(deliveriesForRepo()).isEmpty();
    }

    @Test
    void appLifecycleEventsAreAcknowledgedWithoutWritingADelivery() throws Exception {
        Vcs vcs = saveVcs(VcsConnectionType.STANDALONE, true);

        for (String event : List.of("ping", "installation", "installation_repositories")) {
            post(vcs.getId().toString(), pushPayload(), sign(SECRET, pushPayload()), event, UUID.randomUUID().toString())
                    .statusCode(HttpStatus.OK.value());
        }
        post(vcs.getId().toString(), pushPayload(), sign(SECRET, pushPayload()), null, UUID.randomUUID().toString())
                .statusCode(HttpStatus.BAD_REQUEST.value());

        assertThat(deliveriesForRepo()).isEmpty();
    }

    @Test
    void deletingTheVcsDeletesItsDeliveries() throws Exception {
        Vcs vcs = saveVcs(VcsConnectionType.STANDALONE, true);
        post(vcs.getId().toString(), pushPayload(), sign(SECRET, pushPayload()), "push", UUID.randomUUID().toString())
                .statusCode(HttpStatus.OK.value());
        UUID deliveryId = deliveriesForRepo().get(0).getId();
        awaitStatus(deliveryId);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORGANIZATION_ID + "/vcs/" + vcs.getId())
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        assertThat(vcsRepository.findById(vcs.getId())).isEmpty();
        assertThat(repoWebhookDeliveryRepository.findById(deliveryId)).isEmpty();
    }

    @Test
    void enablingAWebhookOnAnAppModeWorkspaceNeverTouchesRepositoryHooks() throws Exception {
        Vcs vcs = saveVcs(VcsConnectionType.STANDALONE, true);
        String appOnlyRepoUrl = freshRepoUrl();
        Workspace workspace = new Workspace();
        workspace.setName("app-webhook-" + UUID.randomUUID());
        workspace.setSource(appOnlyRepoUrl + ".git");
        workspace.setBranch("main");
        workspace.setIacType("terraform");
        workspace.setTerraformVersion("1.0.11");
        workspace.setOrganization(organization());
        workspace.setVcs(vcs);
        workspace = workspaceRepository.saveAndFlush(workspace);
        createdWorkspaces.add(workspace);
        String webhookId = UUID.randomUUID().toString();

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),
                        "Content-Type", ATOMIC_CONTENT_TYPE, "Accept", ATOMIC_CONTENT_TYPE)
                .body(createWebhookBody(workspace.getId().toString(), webhookId, UUID.randomUUID().toString()))
                .when()
                .post("/api/v1/operations")
                .then()
                .statusCode(HttpStatus.OK.value());

        Webhook webhook = webhookRepository.findById(UUID.fromString(webhookId)).orElseThrow();
        assertThat(webhook.isMigratedV2()).isTrue();
        assertThat(webhook.getRemoteHookId()).isNull();
        // The async repo-webhook sync has run once this App-only repo has its row; only then is
        // "no /hooks call" meaningful.
        Optional<RepoWebhook> synced = Optional.empty();
        for (int i = 0; i < 50 && synced.isEmpty(); i++) {
            Thread.sleep(200);
            synced = repoWebhookRepository.findByRepositoryUrl(RepoUrlNormalizer.normalize(appOnlyRepoUrl));
        }
        assertThat(synced).isPresent();
        assertThat(synced.get().getRemoteHookId()).isNull();
        wireMockServer.verify(0, anyRequestedFor(urlMatching("/repos/.*/hooks.*")));
    }

    @Test
    void enablingAppWebhookModeOnTheVcsResyncsTheRepositoriesOfItsMigratedWorkspaces() throws Exception {
        Vcs vcs = saveVcs(VcsConnectionType.STANDALONE, false);
        String url = freshRepoUrl();
        Workspace workspace = new Workspace();
        workspace.setName("app-webhook-" + UUID.randomUUID());
        workspace.setSource(url + ".git");
        workspace.setBranch("main");
        workspace.setIacType("terraform");
        workspace.setTerraformVersion("1.0.11");
        workspace.setOrganization(organization());
        workspace.setVcs(vcs);
        workspace = workspaceRepository.saveAndFlush(workspace);
        createdWorkspaces.add(workspace);
        Webhook webhook = new Webhook();
        webhook.setId(UUID.randomUUID());
        webhook.setWorkspace(workspace);
        webhook.setMigratedV2(true);
        webhookRepository.saveAndFlush(webhook);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),
                        "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"vcs\",\"id\":\"" + vcs.getId()
                        + "\",\"attributes\":{\"appWebhookEnabled\":true}}}")
                .when()
                .patch("/api/v1/organization/" + ORGANIZATION_ID + "/vcs/" + vcs.getId())
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // Only the VCS update scheduled a sync for this repository; the row is its footprint.
        Optional<RepoWebhook> synced = Optional.empty();
        for (int i = 0; i < 50 && synced.isEmpty(); i++) {
            Thread.sleep(200);
            synced = repoWebhookRepository.findByRepositoryUrl(url);
        }
        assertThat(synced).isPresent();
    }

    private io.restassured.response.ValidatableResponse post(String vcsId, String payload, String signature,
            String event, String guid) {
        var request = given().contentType("application/json").body(payload);
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        if (event != null) {
            request.header("X-GitHub-Event", event);
        }
        return request.header("X-GitHub-Delivery", guid)
                .when()
                .post("/webhook/github-app/" + vcsId)
                .then();
    }

    private String freshRepoUrl() {
        String url = "https://github.com/acme/app-webhook-" + UUID.randomUUID();
        createdRepoUrls.add(url);
        return url;
    }

    private String pushPayload() {
        return "{\"ref\":\"refs/heads/main\",\"installation\":{\"id\":1},"
                + "\"repository\":{\"clone_url\":\"" + repoUrl + ".git\"},"
                + "\"head_commit\":{\"id\":\"abc\"},\"commits\":[]}";
    }

    private List<RepoWebhookDelivery> deliveriesForRepo() {
        return repoWebhookDeliveryRepository.findAll().stream()
                .filter(d -> d.getRepoWebhook().getId().equals(repoWebhook.getId()))
                .toList();
    }

    private RepoWebhookDeliveryStatus awaitStatus(UUID deliveryId) throws InterruptedException {
        RepoWebhookDeliveryStatus status = null;
        for (int i = 0; i < 50; i++) {
            status = repoWebhookDeliveryRepository.findById(deliveryId).orElseThrow().getStatus();
            if (status != RepoWebhookDeliveryStatus.PENDING && status != RepoWebhookDeliveryStatus.PROCESSING) {
                break;
            }
            Thread.sleep(200);
        }
        return status;
    }

    private Organization organization() {
        return organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
    }

    private Vcs saveVcs(VcsConnectionType connectionType, boolean appWebhookEnabled) {
        Vcs vcs = new Vcs();
        vcs.setName("app-webhook-vcs-" + UUID.randomUUID());
        vcs.setDescription("GitHub App webhook test VCS");
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setConnectionType(connectionType);
        vcs.setClientId("424242");
        vcs.setApiUrl("http://localhost:" + wireMockServer.port());
        vcs.setAccessToken("token-123");
        vcs.setAppWebhookEnabled(appWebhookEnabled);
        vcs.setWebhookSecret(SECRET);
        vcs.setOrganization(organization());
        vcs = vcsRepository.saveAndFlush(vcs);
        createdVcs.add(vcs);
        return vcs;
    }

    private String sign(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder("sha256=");
        for (byte b : mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private String createWebhookBody(String workspaceId, String webhookId, String eventId) {
        return """
                {
                  "atomic:operations": [
                    {
                      "op": "add",
                      "href": "/organization/%1$s/workspace/%2$s/webhook",
                      "data": {
                        "type": "webhook",
                        "id": "%3$s",
                        "attributes": { "migratedV2": false }
                      },
                      "relationships": {
                        "events": { "data": [ { "type": "webhook_event", "id": "%4$s" } ] }
                      }
                    },
                    {
                      "op": "add",
                      "href": "/organization/%1$s/workspace/%2$s/webhook/%3$s/events",
                      "data": {
                        "type": "webhook_event",
                        "id": "%4$s",
                        "attributes": {
                          "priority": 1,
                          "event": "PUSH",
                          "branch": "main",
                          "path": ".*",
                          "templateId": "%5$s",
                          "prWorkflowEnabled": false
                        }
                      }
                    }
                  ]
                }
                """.formatted(ORGANIZATION_ID, workspaceId, webhookId, eventId, TEMPLATE_ID);
    }
}
