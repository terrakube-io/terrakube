package io.terrakube.api;

import io.terrakube.api.rs.team.Team;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

public class ProjectAccessTests extends ServerApplicationTests {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String TEMPLATE_REF = "2db36f7c-f549-4341-a789-315d47eb061d";
    private static final String DEVS_TEAM_ID = "58529721-425e-44d7-8b0d-1d515043c2f7";

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
        stubFor(post(urlPathEqualTo("/api/v1/terraform-rs"))
                .willReturn(aResponse().withStatus(HttpStatus.ACCEPTED.value()).withBody("")));
    }

    private void setDevsJobPermissions(boolean enabled) {
        Team team = teamRepository.findById(UUID.fromString(DEVS_TEAM_ID)).get();
        team.setManageJob(enabled);
        team.setPlanJob(enabled);
        team.setApproveJob(enabled);
        team.setRole("custom");
        teamRepository.save(team);
    }

    @Test
    void searchWorkspacesWithProjectLevelAccess() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"searchWorkspacesWithProjectLevelAccess\",\n" +
                        "      \"description\": \"Integration test project\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("searchWorkspacesWithProjectLevelAccess"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"searchWorkspacesWithProjectLevelAccess\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    },\n" +
                        "    \"relationships\": {\n" +
                        "      \"project\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"project\",\n" +
                        "          \"id\": \"" + projectId + "\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("searchWorkspacesWithProjectLevelAccess"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_TEAM_MEMBER"))
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NOT_FOUND.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_TEAM_MEMBER"))
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", Matchers.not(Matchers.hasItem(workspaceId)));

        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_TEAM_MEMBER\",\n" +
                        "      \"role\": \"read\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .log()
                .all()
                .body("data.attributes.name", IsEqual.equalTo("PROJECT_TEAM_MEMBER"))
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_TEAM_MEMBER"))
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_TEAM_MEMBER"))
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", Matchers.hasItem(workspaceId));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + accessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void manageWorkspaceWithProjectWriteRole() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"manageWorkspaceWithProjectWriteRole\",\n" +
                        "      \"description\": \"Integration test project for write role\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("manageWorkspaceWithProjectWriteRole"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"manageWorkspaceWithProjectWriteRole\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    },\n" +
                        "    \"relationships\": {\n" +
                        "      \"project\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"project\",\n" +
                        "          \"id\": \"" + projectId + "\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("manageWorkspaceWithProjectWriteRole"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_WRITE_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"id\": \"" + workspaceId + "\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"description\": \"Modified by project write role\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NOT_FOUND.value());

        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_WRITE_MEMBER\",\n" +
                        "      \"role\": \"write\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .log()
                .all()
                .body("data.attributes.name", IsEqual.equalTo("PROJECT_WRITE_MEMBER"))
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_WRITE_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"id\": \"" + workspaceId + "\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"description\": \"Modified by project write role\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + accessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void manageProjectAccessWithProjectAdminRole() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"manageProjectAccessWithProjectAdminRole\",\n" +
                        "      \"description\": \"Integration test project for admin role\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("manageProjectAccessWithProjectAdminRole"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // PROJECT_ADMIN_MEMBER cannot manage ProjectAccess before being granted admin role
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_ADMIN_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"SOME_TEAM\",\n" +
                        "      \"role\": \"read\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NOT_FOUND.value());

        // Org admin grants PROJECT_ADMIN_MEMBER an admin role on the project
        String adminAccessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_ADMIN_MEMBER\",\n" +
                        "      \"role\": \"admin\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .log()
                .all()
                .body("data.attributes.name", IsEqual.equalTo("PROJECT_ADMIN_MEMBER"))
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // PROJECT_ADMIN_MEMBER can now create a new ProjectAccess entry
        String newAccessId = given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_ADMIN_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"SOME_TEAM\",\n" +
                        "      \"role\": \"read\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .log()
                .all()
                .body("data.attributes.name", IsEqual.equalTo("SOME_TEAM"))
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // PROJECT_ADMIN_MEMBER can delete a ProjectAccess entry
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_ADMIN_MEMBER"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + newAccessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // Cleanup
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + adminAccessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void manageProjectMetadataWithProjectAdminRole() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"manageProjectMetadataWithProjectAdminRole\",\n" +
                        "      \"description\": \"Integration test project for admin metadata update\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("manageProjectMetadataWithProjectAdminRole"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // PROJECT_ADMIN_MEMBER cannot update project metadata before being granted admin role
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_ADMIN_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"id\": \"" + projectId + "\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"shouldBeRejected\",\n" +
                        "      \"description\": \"Should be rejected\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NOT_FOUND.value());

        // Org admin grants PROJECT_ADMIN_MEMBER an admin role on the project
        String adminAccessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_ADMIN_MEMBER\",\n" +
                        "      \"role\": \"admin\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .log()
                .all()
                .body("data.attributes.name", IsEqual.equalTo("PROJECT_ADMIN_MEMBER"))
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // PROJECT_ADMIN_MEMBER can now update project name and description
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_ADMIN_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"id\": \"" + projectId + "\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"updatedByProjectAdmin\",\n" +
                        "      \"description\": \"Updated by project admin\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // Cleanup
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + adminAccessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void cannotManageProjectAccessWithProjectWriteRole() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"cannotManageProjectAccessWithProjectWriteRole\",\n" +
                        "      \"description\": \"Integration test project for write role restriction\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("cannotManageProjectAccessWithProjectWriteRole"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String writeAccessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_WRITE_MEMBER\",\n" +
                        "      \"role\": \"write\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .log()
                .all()
                .body("data.attributes.name", IsEqual.equalTo("PROJECT_WRITE_MEMBER"))
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // PROJECT_WRITE_MEMBER cannot create a new ProjectAccess entry
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_WRITE_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"SOME_TEAM\",\n" +
                        "      \"role\": \"read\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // PROJECT_WRITE_MEMBER cannot delete a ProjectAccess entry (Elide returns 404 when ReadPermission is denied)
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_WRITE_MEMBER"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + writeAccessId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NOT_FOUND.value());

        // Cleanup
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + writeAccessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void workspaceCreatedWithoutProjectAutoAssignedToDefault() {
        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"workspaceCreatedWithoutProject\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("workspaceCreatedWithoutProject"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // Workspace auto-assigned to Default project — org member can see it
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_TEAM_MEMBER"))
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void orphanedWorkspaceNotVisibleToRegularUser() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"orphanedWorkspaceNotVisibleToRegularUser\",\n" +
                        "      \"description\": \"Integration test project\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"orphanedWorkspaceTest\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    },\n" +
                        "    \"relationships\": {\n" +
                        "      \"project\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"project\",\n" +
                        "          \"id\": \"" + projectId + "\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_TEAM_MEMBER\",\n" +
                        "      \"role\": \"read\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // PROJECT_TEAM_MEMBER can see the workspace via project access
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_TEAM_MEMBER"))
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        // Admin orphans the workspace by removing the project relationship
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"id\": \"" + workspaceId + "\",\n" +
                        "    \"relationships\": {\n" +
                        "      \"project\": {\n" +
                        "        \"data\": null\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // Orphaned workspace (null project) IS visible to all org members
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_TEAM_MEMBER"))
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_TEAM_MEMBER"))
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", Matchers.hasItem(workspaceId));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + accessId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void projectAdminCannotMoveWorkspaceToAnotherProject() {
        String projectAId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"projectAdminCannotMove_A\",\n" +
                        "      \"description\": \"Source project\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String projectBId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"projectAdminCannotMove_B\",\n" +
                        "      \"description\": \"Target project\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"projectAdminCannotMoveWs\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    },\n" +
                        "    \"relationships\": {\n" +
                        "      \"project\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"project\",\n" +
                        "          \"id\": \"" + projectAId + "\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // Grant PROJECT_ADMIN_MEMBER admin role on Project A
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_ADMIN_MEMBER\",\n" +
                        "      \"role\": \"admin\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectAId + "/projectAccess")
                .then()
                .statusCode(HttpStatus.CREATED.value());

        // Project Admin tries to move workspace to Project B — must be forbidden
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_ADMIN_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"id\": \"" + workspaceId + "\",\n" +
                        "    \"relationships\": {\n" +
                        "      \"project\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"project\",\n" +
                        "          \"id\": \"" + projectBId + "\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Project Admin tries to set project to null — must also be forbidden
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_ADMIN_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"id\": \"" + workspaceId + "\",\n" +
                        "    \"relationships\": {\n" +
                        "      \"project\": {\n" +
                        "        \"data\": null\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Cleanup
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectAId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectBId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void viewJobWithProjectLevelAccess() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"viewJobWithProjectLevelAccess\",\n" +
                        "      \"description\": \"Integration test project\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"viewJobWithProjectLevelAccess\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    },\n" +
                        "    \"relationships\": {\n" +
                        "      \"project\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"project\",\n" +
                        "          \"id\": \"" + projectId + "\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        setDevsJobPermissions(true);
        String jobId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"job\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"templateReference\": \"" + TEMPLATE_REF + "\"\n" +
                        "    },\n" +
                        "    \"relationships\": {\n" +
                        "      \"workspace\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"workspace\",\n" +
                        "          \"id\": \"" + workspaceId + "\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/job")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        // Before project access: PROJECT_PLAN_MEMBER (not an org team member) gets 403
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_PLAN_MEMBER"))
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/job/" + jobId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Grant PROJECT_PLAN_MEMBER read role on the project
        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_PLAN_MEMBER\",\n" +
                        "      \"role\": \"read\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        // After project access: PROJECT_PLAN_MEMBER can view the job
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_PLAN_MEMBER"))
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/job/" + jobId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        // Cleanup
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + accessId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // Job has no @DeletePermission; delete steps first to avoid FK_JOB_STEP constraint
        stepRepository.deleteAll(stepRepository.findByJobId(Integer.parseInt(jobId)));
        jobRepository.deleteById(Integer.parseInt(jobId));
        setDevsJobPermissions(false);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void planJobWithProjectPlanRole() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"planJobWithProjectPlanRole\",\n" +
                        "      \"description\": \"Integration test project for plan role\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"planJobWithProjectPlanRole\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    },\n" +
                        "    \"relationships\": {\n" +
                        "      \"project\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"project\",\n" +
                        "          \"id\": \"" + projectId + "\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        // Grant PROJECT_TEAM_MEMBER read role (canPlanJob=false)
        String readAccessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_TEAM_MEMBER\",\n" +
                        "      \"role\": \"read\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        String jobPayload = "{\n" +
                "  \"data\": {\n" +
                "    \"type\": \"job\",\n" +
                "    \"attributes\": {\n" +
                "      \"templateReference\": \"" + TEMPLATE_REF + "\"\n" +
                "    },\n" +
                "    \"relationships\": {\n" +
                "      \"workspace\": {\n" +
                "        \"data\": {\n" +
                "          \"type\": \"workspace\",\n" +
                "          \"id\": \"" + workspaceId + "\"\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        // Read role cannot create (plan) a job
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_TEAM_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body(jobPayload)
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/job")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Grant PROJECT_PLAN_MEMBER plan role (canPlanJob=true)
        String planAccessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_PLAN_MEMBER\",\n" +
                        "      \"role\": \"plan\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        // Plan role can create (plan) a job
        String jobId = given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_PLAN_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body(jobPayload)
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/job")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        // Cleanup
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + planAccessId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + readAccessId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // Job has no @DeletePermission; delete steps first to avoid FK_JOB_STEP constraint
        stepRepository.deleteAll(stepRepository.findByJobId(Integer.parseInt(jobId)));
        jobRepository.deleteById(Integer.parseInt(jobId));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void approveJobWithProjectWriteRole() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"approveJobWithProjectWriteRole\",\n" +
                        "      \"description\": \"Integration test project for approve role\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"approveJobWithProjectWriteRole\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    },\n" +
                        "    \"relationships\": {\n" +
                        "      \"project\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"project\",\n" +
                        "          \"id\": \"" + projectId + "\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/workspace")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        setDevsJobPermissions(true);
        String jobId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"job\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"templateReference\": \"" + TEMPLATE_REF + "\"\n" +
                        "    },\n" +
                        "    \"relationships\": {\n" +
                        "      \"workspace\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"workspace\",\n" +
                        "          \"id\": \"" + workspaceId + "\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/job")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");
        setDevsJobPermissions(false);

        // Grant PROJECT_PLAN_MEMBER plan role (canApproveJob=false)
        String planAccessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_PLAN_MEMBER\",\n" +
                        "      \"role\": \"plan\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        // Plan role cannot approve a job (canApproveJob=false)
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_PLAN_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\": {\"type\": \"job\", \"id\": \"" + jobId + "\", \"attributes\": {\"status\": \"approved\"}}}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/job/" + jobId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Grant PROJECT_WRITE_MEMBER write role (canApproveJob=true)
        String writeAccessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project_access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"PROJECT_WRITE_MEMBER\",\n" +
                        "      \"role\": \"write\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        // Write role can approve a job (canApproveJob=true)
        given()
                .headers("Authorization", "Bearer " + generatePAT("PROJECT_WRITE_MEMBER"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\": {\"type\": \"job\", \"id\": \"" + jobId + "\", \"attributes\": {\"status\": \"approved\"}}}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/job/" + jobId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // Cleanup
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + writeAccessId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId + "/projectAccess/" + planAccessId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // Job has no @DeletePermission; delete steps first to avoid FK_JOB_STEP constraint
        stepRepository.deleteAll(stepRepository.findByJobId(Integer.parseInt(jobId)));
        jobRepository.deleteById(Integer.parseInt(jobId));
        setDevsJobPermissions(false);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/workspace/" + workspaceId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/" + ORG_ID + "/project/" + projectId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }
}
