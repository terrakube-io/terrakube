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
}
