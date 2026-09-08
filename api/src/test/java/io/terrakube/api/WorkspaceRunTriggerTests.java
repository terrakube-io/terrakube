package io.terrakube.api;

import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
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

    // Member of the organization with manage_workspace, but not the instance owner group -
    // the ordinary user this feature exists for.
    private static final String GROUP_MANAGER = "TERRAKUBE_DEVELOPERS";

    // Member of the same organization whose team has manage_workspace = false and which
    // holds no project or workspace level access (project-access-demo-data.xml). Every
    // other team of this organization can manage workspaces, so this is the only fixture
    // group that genuinely exercises the denial path.
    private static final String GROUP_NO_MANAGE = "PROJECT_TEAM_MEMBER";

    @Autowired
    private WorkspaceRunTriggerRepository triggerRepository;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /**
     * Edges created by one test are visible to the next - they share a database and the
     * cycle check reads the whole organization graph - so a test could otherwise fail for
     * an edge it never created. Cleaning here keeps every case independent of ordering
     * without touching the shared XML fixtures.
     */
    @AfterEach
    public void cleanupTriggers() {
        triggerRepository.deleteAll();
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
     * The case the permission model exists for: an ordinary member with manage rights on
     * the destination wires an edge, without being the instance owner.
     *
     * This is a regression test. An earlier revision guarded the inverse collections on
     * Workspace with "user is a superuser", and because Elide maintains the bidirectional
     * relationship on create, that rule fired on this ordinary path - no non-superuser
     * could create a trigger at all, and the three tiers of WorkspaceTriggerPermissions
     * were unreachable.
     */
    @Test
    void memberWithManageCanCreateRunTrigger() {
        given()
                .headers("Authorization", "Bearer " + generatePAT(GROUP_MANAGER))
                .contentType("application/vnd.api+json")
                .body(triggerPayload(WORKSPACE_SOURCE, WORKSPACE_DESTINATION))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());
    }

    /**
     * Writing requires manage rights on the destination: a member without them is refused
     * even though both workspaces exist and are visible to the organization.
     */
    @Test
    void memberWithoutManageCannotCreateRunTrigger() {
        given()
                .headers("Authorization", "Bearer " + generatePAT(GROUP_NO_MANAGE))
                .contentType("application/vnd.api+json")
                .body(triggerPayload(WORKSPACE_SOURCE, WORKSPACE_DESTINATION))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value())
                // The denial must come from the trigger's own check. A previous revision
                // passed this test on "UpdatePermission Denied" raised by an unrelated
                // guard on Workspace, which hid the defect above.
                .body("errors[0].detail", equalTo("CreatePermission Denied"));
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

    /**
     * The graph must stay acyclic: with source -> destination in place, the reverse edge
     * closes a loop and is refused by the validation hook with 400.
     */
    @Test
    void cyclicTriggerIsRejected() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/vnd.api+json")
                .body(triggerPayload(WORKSPACE_TAG3, WORKSPACE_TAG2))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/vnd.api+json")
                .body(triggerPayload(WORKSPACE_TAG2, WORKSPACE_TAG3))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.BAD_REQUEST.value());
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

    /**
     * Both ends of an edge are immutable once set: repointing one is deleting a dependency
     * and declaring another, and each deserves its own permission check.
     *
     * The PATCH of enabled comes first on purpose. Without it the two denials below would
     * also pass if this caller simply could not update the resource at all, which is the
     * failure mode that made an earlier version of this suite green for the wrong reason.
     */
    @Test
    void runTriggerEndpointsAreImmutableForNonSuperUsers() {
        String triggerId = createTriggerAsAdmin(WORKSPACE_SOURCE, WORKSPACE_DESTINATION);

        given()
                .headers("Authorization", "Bearer " + generatePAT(GROUP_MANAGER))
                .contentType("application/vnd.api+json")
                .body("{\"data\":{\"type\":\"runTrigger\",\"id\":\"" + triggerId + "\","
                        + "\"attributes\":{\"enabled\":false}}}")
                .when()
                .patch("/api/v1/runTrigger/" + triggerId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        assertRelationshipIsImmutable(triggerId, "sourceWorkspace", WORKSPACE_TAG2);
        assertRelationshipIsImmutable(triggerId, "destinationWorkspace", WORKSPACE_TAG3);
    }

    private String createTriggerAsAdmin(String sourceWorkspaceId, String destinationWorkspaceId) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/vnd.api+json")
                .body(triggerPayload(sourceWorkspaceId, destinationWorkspaceId))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path("data.id");
    }

    private void assertRelationshipIsImmutable(String triggerId, String relationship, String newWorkspaceId) {
        given()
                .headers("Authorization", "Bearer " + generatePAT(GROUP_MANAGER))
                .contentType("application/vnd.api+json")
                .body("{\"data\":{\"type\":\"runTrigger\",\"id\":\"" + triggerId + "\","
                        + "\"relationships\":{\"" + relationship + "\":{\"data\":"
                        + "{\"type\":\"workspace\",\"id\":\"" + newWorkspaceId + "\"}}}}}")
                .when()
                .patch("/api/v1/runTrigger/" + triggerId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value())
                .body("errors[0].detail", equalTo("UpdatePermission Denied"));
    }

    /**
     * Clearing the inverse collection through the workspace must not destroy the edge.
     *
     * The trigger's own DeletePermission is the only sanctioned way to remove one, and it
     * is not consulted when Hibernate drops an orphan - so the relationship deliberately
     * carries neither cascade nor orphanRemoval. Run as the instance owner: if the strongest
     * caller cannot delete an edge this way, no one can.
     */
    @Test
    void clearingTriggersThroughWorkspaceDoesNotDeleteThem() {
        String triggerId = createTriggerAsAdmin(WORKSPACE_SOURCE, WORKSPACE_DESTINATION);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspace\",\"id\":\"" + WORKSPACE_SOURCE + "\","
                        + "\"relationships\":{\"sourceRunTriggers\":{\"data\":[]}}}}")
                .when()
                .patch("/api/v1/organization/" + ORGANIZATION + "/workspace/" + WORKSPACE_SOURCE)
                .then()
                .log()
                .all();

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .get("/api/v1/runTrigger/" + triggerId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
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
