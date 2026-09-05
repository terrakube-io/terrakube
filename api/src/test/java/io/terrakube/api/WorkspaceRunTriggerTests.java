package io.terrakube.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

/**
 * Covers the persistence and permission surface of run triggers. The dispatch behaviour
 * (a completed apply enqueueing downstream runs) arrives in a later phase and is not
 * exercised here.
 */
public class WorkspaceRunTriggerTests extends ServerApplicationTests {

    private static final String ORGANIZATION = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_SOURCE = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String WORKSPACE_TAG3 = "24480d33-2649-4c34-aabd-cbc988eb6265";
    private static final String WORKSPACE_TAG2 = "5a7873bd-9fd3-4193-b3df-33ba586fb146";
    // Both are real workspaces of the organization above (simple.xml / simple-tag.xml).
    // A previous revision used a team id here by mistake, which made the denial test pass
    // on a 404 for a non-existent entity rather than on the permission check.
    private static final String WORKSPACE_DESTINATION = "c20633b2-82cc-4105-9806-16e23ad0e1df";

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private String triggerPayload(String sourceWorkspaceId, String destinationWorkspaceId) {
        return "{\"data\":{\"type\":\"runTrigger\",\"attributes\":{\"enabled\":true},"
                + "\"relationships\":{"
                + "\"sourceWorkspace\":{\"data\":{\"type\":\"workspace\",\"id\":\"" + sourceWorkspaceId + "\"}},"
                + "\"destinationWorkspace\":{\"data\":{\"type\":\"workspace\",\"id\":\"" + destinationWorkspaceId + "\"}}"
                + "}}}";
    }

    private String payloadWithoutOrganization(String sourceWorkspaceId, String destinationWorkspaceId) {
        return triggerPayload(sourceWorkspaceId, destinationWorkspaceId);
    }

    @Test
    void adminCanListRunTriggers() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .get("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    /**
     * Reads are open to any member of the organization, so the dependency graph stays
     * discoverable even for people who cannot change it.
     */
    @Test
    void organizationMemberCanReadRunTriggers() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    /** Someone outside the organization must not see its edges at all. */
    @Test
    void nonMemberCannotReadRunTriggers() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("NOT_A_MEMBER"))
                .when()
                .get("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value())
                .body("data.size()", equalTo(0));
    }

    /** The happy path: an admin wires two real workspaces of the same organization. */
    @Test
    void adminCanCreateRunTrigger() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/vnd.api+json")
                .body(triggerPayload(WORKSPACE_SOURCE, WORKSPACE_DESTINATION))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value())
                .body("data.attributes.enabled", equalTo(true));
    }

    /**
     * Writing requires manage rights on the destination: a member without them is refused
     * even though both workspaces exist and are visible to the organization.
     */
    @Test
    void memberWithoutManageCannotCreateRunTrigger() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .contentType("application/vnd.api+json")
                .body(triggerPayload(WORKSPACE_SOURCE, WORKSPACE_DESTINATION))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    /**
     * Deletion is governed by TeamDeleteWorkspaceTrigger, which asks only for manage rights
     * on the destination - so an admin can always detach a trigger from their own workspace.
     */
    @Test
    void adminCanDeleteRunTrigger() {
        String triggerId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/vnd.api+json")
                .body(triggerPayload(WORKSPACE_SOURCE, WORKSPACE_TAG2))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/runTrigger/" + triggerId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .get("/api/v1/runTrigger/" + triggerId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    /** A workspace triggering itself would re-run on every apply until the cascade limit. */
    @Test
    void selfTriggerIsRejected() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/vnd.api+json")
                .body(triggerPayload(WORKSPACE_SOURCE, WORKSPACE_SOURCE))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    /**
     * The organization is derived from the destination workspace by the lifecycle hook, so
     * omitting it must succeed rather than fail on the NOT NULL column.
     */
    @Test
    void organizationIsDerivedWhenOmitted() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/vnd.api+json")
                .body(payloadWithoutOrganization(WORKSPACE_SOURCE, WORKSPACE_TAG3))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());
    }

    /** An anonymous caller must never reach the resource. */
    @Test
    void anonymousCannotReadRunTriggers() {
        given()
                .when()
                .get("/api/v1/runTrigger")
                .then()
                .assertThat()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }
}
