package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import io.terrakube.api.rs.team.Team;

class SensitiveFlagPermissionTests extends ServerApplicationTests {

    private static final String ORGANIZATION = "/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String VARIABLES = ORGANIZATION + "/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/variable/";

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void readOnlyMemberCannotMarkAVariableSensitive() {
        String variableId = create(VARIABLES, """
                {"data": {"type": "variable", "attributes": {
                  "key": "plain_variable", "value": "visible", "sensitive": false, "hcl": false, "category": "ENV"}}}""");

        // PROJECT_TEAM_MEMBER can read this workspace but has manage_workspace=false.
        patchSensitive("PROJECT_TEAM_MEMBER", VARIABLES + variableId, "variable", variableId)
                .statusCode(HttpStatus.FORBIDDEN.value());
        patchSensitive("TERRAKUBE_DEVELOPERS", VARIABLES + variableId, "variable", variableId)
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void readOnlyMemberCannotMarkACollectionItemSensitive() {
        Team developers = teamRepository.findById(UUID.fromString("58529721-425e-44d7-8b0d-1d515043c2f7")).orElseThrow();
        developers.setManageCollection(true);
        teamRepository.save(developers);

        String collectionId = create(ORGANIZATION + "/collection/", """
                {"data": {"type": "collection", "attributes": {
                  "name": "SensitiveFlagPermission", "description": "test", "priority": 60}}}""");
        String items = ORGANIZATION + "/collection/" + collectionId + "/item/";
        String itemId = create(items, """
                {"data": {"type": "item", "attributes": {
                  "key": "plain_item", "value": "visible", "sensitive": false, "category": "ENV"}}}""");

        // PROJECT_TEAM_MEMBER is an organization member without manage_collection.
        patchSensitive("PROJECT_TEAM_MEMBER", items + itemId, "item", itemId)
                .statusCode(HttpStatus.FORBIDDEN.value());
        patchSensitive("TERRAKUBE_DEVELOPERS", items + itemId, "item", itemId)
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    private String create(String path, String body) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(body)
                .when()
                .post(path)
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");
    }

    private io.restassured.response.ValidatableResponse patchSensitive(String group, String path, String type, String id) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(group), "Content-Type", "application/vnd.api+json")
                .body("{\"data\": {\"type\": \"" + type + "\", \"id\": \"" + id + "\", \"attributes\": {\"sensitive\": true}}}")
                .when()
                .patch(path)
                .then();
    }
}
