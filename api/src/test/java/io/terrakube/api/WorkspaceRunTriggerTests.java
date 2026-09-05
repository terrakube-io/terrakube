package io.terrakube.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

/**
 * Covers the persistence and permission surface of run triggers. The dispatch behaviour
 * (a completed apply enqueueing downstream runs) arrives in a later phase and is not
 * exercised here.
 */
public class WorkspaceRunTriggerTests extends ServerApplicationTests {

    private static final String ORGANIZATION = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_SOURCE = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String WORKSPACE_DESTINATION = "58529721-425e-44d7-8b0d-1d515043c2f7";

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private String triggerPayload(String sourceWorkspaceId) {
        return "{\"data\":{\"type\":\"runTrigger\",\"attributes\":{\"enabled\":true},"
                + "\"relationships\":{"
                + "\"sourceWorkspace\":{\"data\":{\"type\":\"workspace\",\"id\":\"" + sourceWorkspaceId + "\"}},"
                + "\"destinationWorkspace\":{\"data\":{\"type\":\"workspace\",\"id\":\"" + WORKSPACE_DESTINATION + "\"}},"
                + "\"organization\":{\"data\":{\"type\":\"organization\",\"id\":\"" + ORGANIZATION + "\"}}"
                + "}}}";
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
                .body("data.size()", org.hamcrest.Matchers.equalTo(0));
    }

    /**
     * Writing requires manage rights on the destination. A member without them is refused;
     * Elide answers 404 rather than 403 when the caller cannot see the referenced
     * workspaces, which is the safer of the two since it does not confirm they exist.
     */
    @Test
    void memberWithoutManageCannotCreateRunTrigger() {
        int status = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .contentType("application/vnd.api+json")
                .body(triggerPayload(WORKSPACE_SOURCE))
                .when()
                .post("/api/v1/runTrigger")
                .then()
                .assertThat()
                .log()
                .all()
                .extract()
                .statusCode();

        org.junit.jupiter.api.Assertions.assertTrue(
                status == HttpStatus.FORBIDDEN.value() || status == HttpStatus.NOT_FOUND.value(),
                "creating a run trigger without manage rights must be denied, got " + status);
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
