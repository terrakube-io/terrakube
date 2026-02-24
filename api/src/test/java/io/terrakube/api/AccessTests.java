package io.terrakube.api;

import org.apache.commons.io.FileUtils;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.Mockito.when;

public class AccessTests extends ServerApplicationTests {

    private static final String STATE_DIRECTORY_JSON = "%s/.terraform-spring-boot/local/state/%s/%s/state/%s.json";

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void searchWorkspaceAccessAsAdmin() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/access")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void searchAllWorkspaceInOrgAccessAsNonAdmin() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace")
                .then()
                .assertThat()
                .log()
                .all()
                // Use greaterThanOrEqualTo to allow for workspaces created by other tests
                .body("data.size()", greaterThanOrEqualTo(4))
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void searchWorkspaceAccessAsNonAdmin() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/access")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createWorkspaceAccessAsAdmin() {
        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"NEW_TEAM_WORKSPACE_ACCESS_ONLY\",\n" +
                        "      \"manageWorkspace\": true,\n" +
                        "      \"manageJob\": true,\n" +
                        "      \"manageState\": true\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/access")
                .then()
                .log()
                .all()
                .body("data.attributes.name", IsEqual.equalTo("NEW_TEAM_WORKSPACE_ACCESS_ONLY"))
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/access/" + accessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void createWorkspaceAccessAsNonAdmin() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"NEW_TEAM_WORKSPACE_ACCESS_ONLY\",\n" +
                        "      \"manageWorkspace\": true,\n" +
                        "      \"manageJob\": true,\n" +
                        "      \"manageState\": true\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/access")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void searchWorkspacesWithLimitedAccess() {
        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"searchWorkspacesWithLimitedAccess\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("searchWorkspacesWithLimitedAccess"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("SEARCH_WITH_LIMITED_ACCESS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"SEARCH_WITH_LIMITED_ACCESS\",\n" +
                        "      \"manageWorkspace\": true,\n" +
                        "      \"manageJob\": true,\n" +
                        "      \"manageState\": true\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId + "/access")
                .then()
                .log()
                .all()
                .body("data.attributes.name", IsEqual.equalTo("SEARCH_WITH_LIMITED_ACCESS"))
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("SEARCH_WITH_LIMITED_ACCESS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"  + workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("SEARCH_WITH_LIMITED_ACCESS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId + "/access/" + accessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

    }

    @Test
    void manageWorkspacesWithLimitedAccess() {

        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"MANAGE_WITH_LIMITED_ACCESS\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("MANAGE_WITH_LIMITED_ACCESS"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("MANAGE_WITH_LIMITED_ACCESS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"id\": \""+workspaceId+"\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"MANAGE_WITH_LIMITED_ACCESS_MODIFIED\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"MANAGE_WITH_LIMITED_ACCESS\",\n" +
                        "      \"manageWorkspace\": true,\n" +
                        "      \"manageJob\": false,\n" +
                        "      \"manageState\": false\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+workspaceId+"/access")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");


        given()
                .headers("Authorization", "Bearer " + generatePAT("MANAGE_WITH_LIMITED_ACCESS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"id\": \""+workspaceId+"\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"MANAGE_WITH_LIMITED_ACCESS_MODIFIED\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+workspaceId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("MANAGE_WITH_LIMITED_ACCESS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"job\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"templateReference\": \"2db36f7c-f549-4341-a789-315d47eb061d\"\n" +
                        "    },\n" +
                        "    \"relationships\":{\n" +
                        "        \"workspace\":{\n" +
                        "            \"data\":{\n" +
                        "                \"type\": \"workspace\",\n" +
                        "                \"id\": \""+ workspaceId +"\"\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/job/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"id\": \""+ accessId +"\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"manageWorkspace\": true,\n" +
                        "      \"manageJob\": true,\n" +
                        "      \"planJob\": true,\n" +
                        "      \"approveJob\": true,\n" +
                        "      \"manageState\": false,\n" +
                        "      \"role\": \"custom\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+workspaceId+"/access/" + accessId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("MANAGE_WITH_LIMITED_ACCESS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"job\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"templateReference\": \"2db36f7c-f549-4341-a789-315d47eb061d\"\n" +
                        "    },\n" +
                        "    \"relationships\":{\n" +
                        "        \"workspace\":{\n" +
                        "            \"data\":{\n" +
                        "                \"type\": \"workspace\",\n" +
                        "                \"id\": \""+ workspaceId +"\"\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/job/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId + "/access/" + accessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void testDownloadStateStorageJSON() throws IOException {

        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"testDownloadStateStorageJSON\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("testDownloadStateStorageJSON"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"DOWNLOAD_STORAGE_JSON_GROUP\",\n" +
                        "      \"manageWorkspace\": true,\n" +
                        "      \"manageJob\": false,\n" +
                        "      \"manageState\": false\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+workspaceId+"/access")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        FileUtils.writeStringToFile(
                new File(
                        String.format(STATE_DIRECTORY_JSON, FileUtils.getUserDirectoryPath(), "d9b58bd3-f3fc-4056-a026-1163297e80a8", workspaceId, "1")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        given()
                .headers("Authorization", "Bearer " + generatePAT("DOWNLOAD_STORAGE_JSON_GROUP"))
                .when()
                .get("/tfstate/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+ workspaceId + "/state/1.json")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"id\": \""+ accessId +"\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"manageWorkspace\": true,\n" +
                        "      \"manageJob\": true,\n" +
                        "      \"manageState\": true\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId + "/access/" + accessId)
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("DOWNLOAD_STORAGE_JSON_GROUP"))
                .when()
                .get("/tfstate/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+ workspaceId + "/state/1.json")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId + "/access/" + accessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void viewTemplateAsUserWithLimitedAccess() {
        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"viewTemplateAsUserWithLimitedAccess\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("viewTemplateAsUserWithLimitedAccess"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"VIEW_TEMPLATE_LIMITED_ACCESS\",\n" +
                        "      \"manageWorkspace\": false,\n" +
                        "      \"manageJob\": false,\n" +
                        "      \"manageState\": false\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+workspaceId+"/access")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("VIEW_TEMPLATE_LIMITED_ACCESS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/template")
                .then()
                .assertThat()
                .log()
                .all()
                .body("data.size()", equalTo(5))
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("VIEW_TEMPLATE_LIMITED_ACCESS_FAKE"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/template")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId + "/access/" + accessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void viewVcsUserWithLimitedAccess() {
        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"viewVcsUserWithLimitedAccess\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("viewVcsUserWithLimitedAccess"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"VIEW_VCS_LIMITED_ACCESS\",\n" +
                        "      \"manageWorkspace\": false,\n" +
                        "      \"manageJob\": false,\n" +
                        "      \"manageState\": false\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+workspaceId+"/access")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("VIEW_VCS_LIMITED_ACCESS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs")
                .then()
                .assertThat()
                .log()
                .all()
                .body("data.size()", equalTo(0))
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("VIEW_VCS_LIMITED_ACCESS_FAKE"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/vcs")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId + "/access/" + accessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void viewModuleUserWithLimitedAccess() {
        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"viewModuleUserWithLimitedAccess\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("viewModuleUserWithLimitedAccess"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"VIEW_MODULE_LIMITED_ACCESS\",\n" +
                        "      \"manageWorkspace\": false,\n" +
                        "      \"manageJob\": false,\n" +
                        "      \"manageState\": false\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+workspaceId+"/access")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("VIEW_MODULE_LIMITED_ACCESS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/module")
                .then()
                .assertThat()
                .log()
                .all()
                .body("data.size()", equalTo(0))
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("VIEW_MODULE_LIMITED_ACCESS_FAKE"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/module")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId + "/access/" + accessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void validateAccessToken() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/json")
                .when()
                .get("/access-token/v1/teams/permissions/organization/3a130a1c-d96f-4f99-83b8-58d472567e3a") //AWS org in sample data
                .then()
                .assertThat()
                .body("manageState", IsEqual.equalTo(false))
                .body("manageWorkspace", IsEqual.equalTo(true))
                .body("manageModule", IsEqual.equalTo(true))
                .body("manageProvider", IsEqual.equalTo(true))
                .body("manageVcs", IsEqual.equalTo(true))
                .body("manageTemplate", IsEqual.equalTo(true))
                .body("manageCollection", IsEqual.equalTo(false))
                .body("manageJob", IsEqual.equalTo(false))
                .log()
                .all()
                .statusCode(HttpStatus.ACCEPTED.value());
    }

    @Test
    void validateAccessTokenForWorkspace() {
        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"validateAccessTokenForWorkspace\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-app-registration.git\",\n" +
                        "      \"branch\": \"main\",\n" +
                        "      \"terraformVersion\": \"1.0.11\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("validateAccessTokenForWorkspace"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        String accessId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"access\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"validateAccessTokenForWorkspace\",\n" +
                        "      \"manageWorkspace\": true,\n" +
                        "      \"manageJob\": true,\n" +
                        "      \"planJob\": true,\n" +
                        "      \"approveJob\": true,\n" +
                        "      \"manageState\": true,\n" +
                        "      \"role\": \"custom\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId + "/access")
                .then()
                .log()
                .all()
                .body("data.attributes.name", IsEqual.equalTo("validateAccessTokenForWorkspace"))
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("validateAccessTokenForWorkspace"), "Content-Type", "application/json")
                .when()
                .get("/access-token/v1/teams/permissions/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+workspaceId)
                .then()
                .assertThat()
                .body("manageState", IsEqual.equalTo(true))
                .body("manageWorkspace", IsEqual.equalTo(true))
                .body("manageJob", IsEqual.equalTo(true))
                .log()
                .all()
                .statusCode(HttpStatus.ACCEPTED.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId + "/access/" + accessId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());


        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/" + workspaceId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void readCliEntitlements() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("LIMITED_ACCESS"), "Content-Type", "application/vnd.api+json")
                .when()
                .get("/remote/tfe/v2/organizations/simple/entitlement-set")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void getCliWorkspaceInformation() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("LIMITED_ACCESS"), "Content-Type", "application/vnd.api+json")
                .when()
                .get("/remote/tfe/v2/organizations/simple/workspaces/sample_simple")
                .then()
                .assertThat()
                .log()
                .all()
                .body("data.attributes.permissions.can-queue-run", IsEqual.equalTo(true))
                .body("data.attributes.permissions.can-queue-destroy", IsEqual.equalTo(true))
                .body("data.attributes.permissions.can-queue-apply", IsEqual.equalTo(true))
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void getCliOrganizationCapacity() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("LIMITED_ACCESS"), "Content-Type", "application/vnd.api+json")
                .when()
                .get("/remote/tfe/v2/organizations/simple/capacity")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createCliWorkspaceLimitedAccess() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("LIMITED_ACCESS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspaces\",\"attributes\":{\"name\":\"createCliWorkspaceLimitedAccess\"}}}")
                .when()
                .post("/remote/tfe/v2/organizations/simple/workspaces")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void createCliWorkspaceNormalUser() {
        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspaces\",\"attributes\":{\"name\":\"createCliWorkspaceNormalUser\"}}}")
                .when()
                .post("/remote/tfe/v2/organizations/simple/workspaces")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/"+workspaceId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }
}
