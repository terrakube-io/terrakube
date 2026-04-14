package io.terrakube.api;

import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventPathType;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

class WebhookTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String ATOMIC_CONTENT_TYPE = "application/vnd.api+json;ext=\"https://jsonapi.org/ext/atomic\"";
    private static final String TEMPLATE_ID = "42201234-a5e2-4c62-b2fc-9729ca6b4515";

    @Autowired
    private WebhookRepository webhookRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    @Test
    void createWebhookFromAtomicOperationsAfterCommit() {
        String workspaceId = createWorkspace();
        stubGithubWebhookCreation();

        String webhookId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        given()
                .headers(
                        "Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),
                        "Content-Type", ATOMIC_CONTENT_TYPE,
                        "Accept", ATOMIC_CONTENT_TYPE
                )
                .body(buildRequestBody(workspaceId, webhookId, eventId, "bang/*", "\"pathType\": \"PATTERN\","))
                .when()
                .post("/api/v1/operations")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        Webhook persistedWebhook = webhookRepository.findById(UUID.fromString(webhookId))
                .orElseThrow(() -> new IllegalStateException("Webhook was not persisted"));

        Assertions.assertEquals("123456", persistedWebhook.getRemoteHookId());

        List<WebhookEvent> persistedEvents = webhookEventRepository
                .findByWebhookAndEventOrderByPriorityAsc(persistedWebhook, WebhookEventType.PUSH);

        Assertions.assertEquals(1, persistedEvents.size());
        Assertions.assertEquals("bang/*", persistedEvents.get(0).getPath());
        Assertions.assertEquals(WebhookEventPathType.PATTERN, persistedEvents.get(0).getPathType());

        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/repos/acme/repo/hooks")));
    }

    @Test
    void createWebhookFromAtomicOperationsWithoutPathTypeDefaultsToRegex() {
        String workspaceId = createWorkspace();
        stubGithubWebhookCreation();

        String webhookId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        given()
                .headers(
                        "Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),
                        "Content-Type", ATOMIC_CONTENT_TYPE,
                        "Accept", ATOMIC_CONTENT_TYPE
                )
                .body(buildRequestBody(workspaceId, webhookId, eventId, "^bang/.+", ""))
                .when()
                .post("/api/v1/operations")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        Webhook persistedWebhook = webhookRepository.findById(UUID.fromString(webhookId))
                .orElseThrow(() -> new IllegalStateException("Webhook was not persisted"));

        Assertions.assertEquals("123456", persistedWebhook.getRemoteHookId());

        List<WebhookEvent> persistedEvents = webhookEventRepository
                .findByWebhookAndEventOrderByPriorityAsc(persistedWebhook, WebhookEventType.PUSH);

        Assertions.assertEquals(1, persistedEvents.size());
        Assertions.assertEquals("^bang/.+", persistedEvents.get(0).getPath());
        Assertions.assertEquals(WebhookEventPathType.REGEX, persistedEvents.get(0).getPathType());

        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/repos/acme/repo/hooks")));
    }

    private void stubGithubWebhookCreation() {
        wireMockServer.stubFor(post(urlEqualTo("/repos/acme/repo/hooks"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.CREATED.value())
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"123456\"}")));
    }

    private String buildRequestBody(String workspaceId, String webhookId, String eventId, String path, String pathTypeField) {
        return """
                {
                  "atomic:operations": [
                    {
                      "op": "add",
                      "href": "/organization/%s/workspace/%s/webhook",
                      "data": {
                        "type": "webhook",
                        "id": "%s"
                      },
                      "relationships": {
                        "events": {
                          "data": [
                            {
                              "type": "webhook_event",
                              "id": "%s"
                            }
                          ]
                        }
                      }
                    },
                    {
                      "op": "add",
                      "href": "/organization/%s/workspace/%s/webhook/%s/events",
                      "data": {
                        "type": "webhook_event",
                        "id": "%s",
                        "attributes": {
                          "priority": 1,
                          "event": "PUSH",
                          "branch": "development",
                          "path": "%s",
                          %s
                          "templateId": "%s",
                          "prWorkflowEnabled": false
                        }
                      }
                    }
                  ]
                }
                """.formatted(
                ORGANIZATION_ID,
                workspaceId,
                webhookId,
                eventId,
                ORGANIZATION_ID,
                workspaceId,
                webhookId,
                eventId,
                path,
                pathTypeField,
                TEMPLATE_ID
        );
    }

    private String createWorkspace() {
        String workspaceId = given()
                .headers(
                        "Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),
                        "Content-Type", "application/vnd.api+json"
                )
                .body("""
                        {
                          "data": {
                            "type": "workspace",
                            "attributes": {
                              "name": "WebhookTestWorkspace-%s",
                              "source": "https://github.com/acme/repo.git",
                              "branch": "main",
                              "terraformVersion": "1.0.11"
                            }
                          }
                        }
                        """.formatted(UUID.randomUUID()))
                .when()
                .post("/api/v1/organization/" + ORGANIZATION_ID + "/workspace")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path("data.id");

        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID))
                .orElseThrow(() -> new IllegalStateException("Organization not found"));

        Vcs vcs = new Vcs();
        vcs.setName("github-vcs-" + UUID.randomUUID());
        vcs.setDescription("Webhook test VCS");
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setApiUrl("http://localhost:" + wireMockServer.port());
        vcs.setAccessToken("token-123");
        vcs.setOrganization(organization);
        vcs = vcsRepository.saveAndFlush(vcs);

        Workspace workspace = workspaceRepository.findById(UUID.fromString(workspaceId))
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        workspace.setVcs(vcs);
        workspaceRepository.saveAndFlush(workspace);

        return workspaceId;
    }
}
