package io.terrakube.api;

import org.apache.commons.io.FileUtils;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import io.terrakube.api.rs.team.Team;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

class WorkspaceTests extends ServerApplicationTests {

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void searchWorkspaceAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("sample_simple"))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void searchWorkspaceManageSateOrgMember() throws IOException {

        FileUtils.writeStringToFile(
                new File(
                        String.format("%s/.terraform-spring-boot/local/backend/%s/%s/terraform.tfstate", FileUtils.getUserDirectoryPath(), "d9b58bd3-f3fc-4056-a026-1163297e80a8", "5ed411ca-7ab8-4d2f-b591-02d0d5788afc")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        Optional<Team> teamOptional = teamRepository.findById(UUID.fromString("58529721-425e-44d7-8b0d-1d515043c2f7"));
        Team team = teamOptional.get();
        team.setManageState(true);
        teamRepository.save(team);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tfstate/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/state/terraform.tfstate")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void searchWorkspaceManageSateNoOrgMember() throws IOException {

        FileUtils.writeStringToFile(
                new File(
                        String.format("%s/.terraform-spring-boot/local/backend/%s/%s/terraform.tfstate", FileUtils.getUserDirectoryPath(), "d9b58bd3-f3fc-4056-a026-1163297e80a8", "5ed411ca-7ab8-4d2f-b591-02d0d5788afc")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        Optional<Team> teamOptional = teamRepository.findById(UUID.fromString("58529721-425e-44d7-8b0d-1d515043c2f7"));
        Team team = teamOptional.get();
        team.setManageState(true);
        teamRepository.save(team);

        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_GROUP"))
                .when()
                .get("/tfstate/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/state/terraform.tfstate")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void searchWorkspaceStateAsExecutor() throws IOException {

        FileUtils.writeStringToFile(
                new File(
                        String.format("%s/.terraform-spring-boot/local/backend/%s/%s/terraform.tfstate", FileUtils.getUserDirectoryPath(), "d9b58bd3-f3fc-4056-a026-1163297e80a8", "5ed411ca-7ab8-4d2f-b591-02d0d5788afc")),
                "SAMPLE",
                Charset.defaultCharset().toString()
        );

        Optional<Team> teamOptional = teamRepository.findById(UUID.fromString("58529721-425e-44d7-8b0d-1d515043c2f7"));
        Team team = teamOptional.get();
        team.setManageState(true);
        teamRepository.save(team);

        given()
                .headers("Authorization", "Bearer " + generateSystemToken())
                .when()
                .get("/tfstate/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/state/terraform.tfstate")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void searchWorkspaceAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void searchVariableAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/variable")
                .then()
                .log().all()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void searchVariableAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/variable")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void createVariableAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"variable\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"key\": \"random_key\",\n" +
                        "      \"value\": \"random_key\",\n" +
                        "      \"sensitive\": true,\n" +
                        "      \"hcl\": false,\n" +
                        "      \"category\": \"ENV\",\n" +
                        "      \"description\": \"random_description\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/variable")
                .then()
                .body("data.attributes.key", IsEqual.equalTo("random_key"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());
    }

    @Test
    void createScheduleAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"schedule\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"cron\": \"0 0/1 * * * ?\",\n" +
                        "      \"templateReference\": \"42201234-a5e2-4c62-b2fc-9729ca6b4515\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/schedule")
                .then()
                .body("data.attributes.cron", IsEqual.equalTo("0 0/1 * * * ?"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());
    }

    @Test
    void createVariableAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"variable\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"key\": \"random_key\",\n" +
                        "      \"value\": \"random_key\",\n" +
                        "      \"sensitive\": true,\n" +
                        "      \"hcl\": false,\n" +
                        "      \"category\": \"ENV\",\n" +
                        "      \"description\": \"random_description\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/variable")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void searchHistoryAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/history")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createHistoryAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),"Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"history\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"output\": \"sampleOutput\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/history")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void createHistoryAsInternalService() {
        given()
                .headers("Authorization", "Bearer " + generateSystemToken(),"Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"history\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"output\": \"sampleOutput\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/history")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());
    }

    @Test
    void searchHistoryAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/history")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void searchScheduleAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/schedule")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createWorkspaceAsOrgMember() {
        String workspaceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"TestWorkspace\",\n" +
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
                .body("data.attributes.name", IsEqual.equalTo("TestWorkspace"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // Delete the test workspace
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

    @Test
    void createTerragruntWorkspaceAtomic() {
        given()
                .headers(
                        "Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),
                        "Content-Type", "application/vnd.api+json;ext=\"https://jsonapi.org/ext/atomic\"",
                        "Accept", "application/vnd.api+json;ext=\"https://jsonapi.org/ext/atomic\""
                )
                .body("{\n" +
                        "  \"atomic:operations\": [\n" +
                        "    {\n" +
                        "      \"op\": \"add\",\n" +
                        "      \"href\": \"/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace\",\n" +
                        "      \"data\": {\n" +
                        "        \"type\": \"workspace\",\n" +
                        "        \"lid\": \"01a026af-48b5-70b9-9eca-2881e54b2523\",\n" +
                        "        \"attributes\": {\n" +
                        "          \"source\": \"https://github.com/alfespa17/terrakube-docker-compose\",\n" +
                        "          \"folder\": \"/\",\n" +
                        "          \"name\": \"terragrunt-atomic-test\",\n" +
                        "          \"terraformVersion\": \"1.15.9\",\n" +
                        "          \"terragruntVersion\": \"1.1.3\",\n" +
                        "          \"branch\": \"terragrunt\",\n" +
                        "          \"iacType\": \"terragrunt\",\n" +
                        "          \"executionMode\": \"remote\"\n" +
                        "        },\n" +
                        "        \"relationships\": {}\n" +
                        "      }\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}")
                .when()
                .post("/api/v1/operations")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createWorkspaceAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"workspace\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"WorkspaceCreateTest\",\n" +
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
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

}
