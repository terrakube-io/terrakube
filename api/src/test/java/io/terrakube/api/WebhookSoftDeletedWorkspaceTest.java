package io.terrakube.api;

import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventPathType;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Soft deleting a workspace keeps its webhook rows. The flat webhook and webhook event collections
 * must skip them instead of failing the whole request on the hidden workspace.
 */
class WebhookSoftDeletedWorkspaceTest extends ServerApplicationTests {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @Autowired
    private WebhookRepository webhookRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Team team;
    private String token;
    private Workspace liveWorkspace;
    private Workspace deletedWorkspace;
    private Webhook liveWebhook;
    private Webhook orphanWebhook;
    private WebhookEvent liveEvent;
    private WebhookEvent orphanEvent;

    @BeforeEach
    void createFixtures() {
        Organization organization = organizationRepository.findById(UUID.fromString(ORG_ID)).orElseThrow();

        team = new Team();
        team.setName("WEBHOOK_SOFT_DELETE_" + UUID.randomUUID());
        team.setOrganization(organization);
        team.setManageWorkspace(true);
        team = teamRepository.saveAndFlush(team);
        token = generatePAT(team.getName());

        liveWorkspace = newWorkspace(organization);
        deletedWorkspace = newWorkspace(organization);
        liveWebhook = newWebhook(liveWorkspace);
        orphanWebhook = newWebhook(deletedWorkspace);
        liveEvent = newEvent(liveWebhook);
        orphanEvent = newEvent(orphanWebhook);

        // What deleting a workspace does: the row stays, flagged deleted, with its webhook.
        deletedWorkspace.setDeleted(true);
        deletedWorkspace = workspaceRepository.saveAndFlush(deletedWorkspace);
    }

    @AfterEach
    void removeFixtures() {
        // The orphan rows are hidden from JPA now, so remove them with SQL.
        jdbcTemplate.update("DELETE FROM webhook_event WHERE id IN (?, ?)", liveEvent.getId().toString(), orphanEvent.getId().toString());
        jdbcTemplate.update("DELETE FROM webhook WHERE id IN (?, ?)", liveWebhook.getId().toString(), orphanWebhook.getId().toString());
        for (Workspace workspace : new Workspace[] {liveWorkspace, deletedWorkspace}) {
            jdbcTemplate.update("UPDATE workspace SET deleted = true WHERE id = ?", workspace.getId().toString());
        }
        teamRepository.delete(team);
    }

    @Test
    void webhookListSkipsWebhooksOfDeletedWorkspaces() {
        given()
                .headers("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/webhook")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", hasItem(liveWebhook.getId().toString()))
                .body("data.id", not(hasItem(orphanWebhook.getId().toString())));
    }

    @Test
    void webhookEventListSkipsEventsOfDeletedWorkspaces() {
        given()
                .headers("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/webhook_event")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", hasItem(liveEvent.getId().toString()))
                .body("data.id", not(hasItem(orphanEvent.getId().toString())));
    }

    @Test
    void webhookOfDeletedWorkspaceIsNotFoundById() {
        given()
                .headers("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/webhook/" + orphanWebhook.getId())
                .then()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    private Workspace newWorkspace(Organization organization) {
        Workspace workspace = new Workspace();
        workspace.setName("ws-webhook-soft-delete-" + UUID.randomUUID());
        workspace.setOrganization(organization);
        workspace.setSource("https://github.com/terrakube-io/terrakube.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.13.1");
        return workspaceRepository.saveAndFlush(workspace);
    }

    private Webhook newWebhook(Workspace workspace) {
        Webhook webhook = new Webhook();
        webhook.setId(UUID.randomUUID());
        webhook.setWorkspace(workspace);
        webhook.setRemoteHookId("hook-" + UUID.randomUUID());
        return webhookRepository.saveAndFlush(webhook);
    }

    private WebhookEvent newEvent(Webhook webhook) {
        WebhookEvent event = new WebhookEvent();
        event.setId(UUID.randomUUID());
        event.setWebhook(webhook);
        event.setBranch("main");
        event.setPath("/*");
        event.setTemplateId("42201234-a5e2-4c62-b2fc-9729ca6b4515");
        event.setEvent(WebhookEventType.PUSH);
        event.setPathType(WebhookEventPathType.REGEX);
        return webhookEventRepository.saveAndFlush(event);
    }
}
