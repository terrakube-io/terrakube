package io.terrakube.api;

import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

public class ProjectAccessTests extends ServerApplicationTests {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
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
}
