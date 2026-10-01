package io.terrakube.api;

import io.restassured.response.Response;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

/**
 * Who may change the tags of a workspace.
 *
 * <p>The demo organization binds the tags "development" and "networking" to simple_tag1, and PROJECT_TEAM_MEMBER
 * is a team of that organization without manage_workspace.
 */
class WorkspaceTagPermissionTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String SIMPLE_TAG_1 = "c20633b2-82cc-4105-9806-16e23ad0e1df";
    private static final String SIMPLE_TAG_2 = "5a7873bd-9fd3-4193-b3df-33ba586fb146";
    private static final String DEVELOPMENT_TAG = "58529721-425e-44d7-8b0d-1d515043c2f7";
    private static final String STAGING_TAG = "0d7e4a6a-560e-40f1-a6dd-c7433a04f088";
    private static final String PRODUCTION_TAG = "2edcb0b0-3d25-453b-bcbc-21baa7780cf7";

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void anOrganizationMemberWithoutManageWorkspaceCannotChangeTags() {
        String memberToken = generatePAT("PROJECT_TEAM_MEMBER");
        String bindingId = bindingId(SIMPLE_TAG_1, DEVELOPMENT_TAG);

        given()
                .headers("Authorization", "Bearer " + memberToken)
                .when()
                .get(workspaceTagPath(SIMPLE_TAG_1))
                .then()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + memberToken, "Content-Type", "application/vnd.api+json")
                .body(newBinding(STAGING_TAG))
                .when()
                .post(workspaceTagPath(SIMPLE_TAG_1))
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + memberToken, "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspacetag\",\"id\":\"" + bindingId
                        + "\",\"attributes\":{\"tagId\":\"" + STAGING_TAG + "\"}}}")
                .when()
                .patch(workspaceTagPath(SIMPLE_TAG_1) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + memberToken, "Content-Type", "application/vnd.api+json")
                .body(valuePatch(bindingId, "prod"))
                .when()
                .patch(workspaceTagPath(SIMPLE_TAG_1) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + memberToken)
                .when()
                .delete(workspaceTagPath(SIMPLE_TAG_1) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // The workspace still carries the tag it started with, as a key-only tag.
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get(workspaceTagPath(SIMPLE_TAG_1) + "/" + bindingId)
                .then()
                .body("data.attributes.tagId", Matchers.equalTo(DEVELOPMENT_TAG))
                .body("data.attributes.value", Matchers.nullValue())
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void aTeamThatManagesTheWorkspaceStillChangesItsTags() {
        String developerToken = generatePAT("TERRAKUBE_DEVELOPERS");
        String bindingId = given()
                .headers("Authorization", "Bearer " + developerToken, "Content-Type", "application/vnd.api+json")
                .body(newBinding(STAGING_TAG))
                .when()
                .post(workspaceTagPath(SIMPLE_TAG_2))
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + developerToken, "Content-Type", "application/vnd.api+json")
                // A key the workspace does not carry yet: one binding per key per workspace is a DB constraint.
                .body("{\"data\":{\"type\":\"workspacetag\",\"id\":\"" + bindingId
                        + "\",\"attributes\":{\"tagId\":\"" + PRODUCTION_TAG + "\"}}}")
                .when()
                .patch(workspaceTagPath(SIMPLE_TAG_2) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + developerToken, "Content-Type", "application/vnd.api+json")
                .body(valuePatch(bindingId, "prod"))
                .when()
                .patch(workspaceTagPath(SIMPLE_TAG_2) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + developerToken)
                .when()
                .get(workspaceTagPath(SIMPLE_TAG_2) + "/" + bindingId)
                .then()
                .body("data.attributes.tagId", Matchers.equalTo(PRODUCTION_TAG))
                .body("data.attributes.value", Matchers.equalTo("prod"))
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + developerToken)
                .when()
                .delete(workspaceTagPath(SIMPLE_TAG_2) + "/" + bindingId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    private String bindingId(String workspaceId, String tagId) {
        Response response = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get(workspaceTagPath(workspaceId));
        List<Map<String, Object>> data = response.jsonPath().getList("data");
        return data.stream()
                .filter(item -> tagId.equals(((Map<?, ?>) item.get("attributes")).get("tagId")))
                .map(item -> (String) item.get("id"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no binding for " + tagId + ": " + response.asPrettyString()));
    }

    private static String workspaceTagPath(String workspaceId) {
        return "/api/v1/organization/" + ORGANIZATION_ID + "/workspace/" + workspaceId + "/workspaceTag";
    }

    private static String newBinding(String tagId) {
        return "{\"data\":{\"type\":\"workspacetag\",\"attributes\":{\"tagId\":\"" + tagId + "\"}}}";
    }

    private static String valuePatch(String bindingId, String value) {
        return "{\"data\":{\"type\":\"workspacetag\",\"id\":\"" + bindingId
                + "\",\"attributes\":{\"value\":\"" + value + "\"}}}";
    }
}
