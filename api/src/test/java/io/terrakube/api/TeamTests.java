package io.terrakube.api;

import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

class TeamTests extends ServerApplicationTests{

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void searchTeamAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team/58529721-425e-44d7-8b0d-1d515043c2f7")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("TERRAKUBE_DEVELOPERS"))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createTeamAsAdmin() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"team\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"NEW_TEAM\",\n" +
                        "      \"manageWorkspace\": true,\n" +
                        "      \"manageModule\": true,\n" +
                        "      \"manageProvider\": true,\n" +
                        "      \"manageVcs\": true,\n" +
                        "      \"manageTemplate\": true\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("NEW_TEAM"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());
    }

    @Test
    void createTeamAsNonAdmin() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"team\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"NEW_TEAM_TWO\",\n" +
                        "      \"manageWorkspace\": true,\n" +
                        "      \"manageModule\": true,\n" +
                        "      \"manageProvider\": true,\n" +
                        "      \"manageVcs\": true,\n" +
                        "      \"manageTemplate\": true\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void searchTeamAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team/58529721-425e-44d7-8b0d-1d515043c2f7")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void listTeamsAsRegistryService() {
        given()
                .headers("Authorization", "Bearer " + generateRegistryToken())
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void searchTeamAsRegistryService() {
        given()
                .headers("Authorization", "Bearer " + generateRegistryToken())
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team/58529721-425e-44d7-8b0d-1d515043c2f7")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("TERRAKUBE_DEVELOPERS"))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createTeamAsRegistryServiceForbidden() {
        given()
                .headers("Authorization", "Bearer " + generateRegistryToken(), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"team\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"REGISTRY_TEST_TEAM\",\n" +
                        "      \"manageWorkspace\": true\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }
}
