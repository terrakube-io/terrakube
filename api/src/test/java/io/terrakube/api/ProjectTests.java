package io.terrakube.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

public class ProjectTests extends ServerApplicationTests {

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void createHistoryAsOrgMember() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),"Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"test-project\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/project")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");
        
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),"Content-Type", "application/vnd.api+json")
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/project/" + projectId )
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/project/"+projectId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void createHistoryAsOrgMemberCli() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),"Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"test-sample\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/remote/tfe/v2/organizations/simple/projects")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),"Content-Type", "application/vnd.api+json")
                .when()
                .get("/remote/tfe/v2/organizations/simple/projects")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        projectRepository.deleteAll();
    }


}
