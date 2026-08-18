package io.terrakube.api;

import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

class ModuleTests extends ServerApplicationTests {

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void searchModuleAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module/4e92ff1e-9937-400f-848d-f0ea367927bf")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("kubernetes-engine"))
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createModuleAsOrgMember() throws SchedulerException {
        String moduleId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"module\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"terrakube-storage\",\n" +
                        "      \"description\": \"Terrakube Storage Module\",\n" +
                        "      \"provider\": \"azurerm\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-cloud-storage.git\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("terrakube-storage"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        // Validate the job to refresh modules was created automatically in post commit phase
        boolean jobExist = scheduler.getJobDetail(new JobKey("TerrakubeV2_ModuleRefresh_" + moduleId)) != null;
        Assert.isTrue(jobExist, "Default job should be created for the vcs connection");
    }

    @Test
    void createDuplicateModuleIsRejectedWithConflict() {
        String body = "{\n" +
                "  \"data\": {\n" +
                "    \"type\": \"module\",\n" +
                "    \"attributes\": {\n" +
                "      \"name\": \"terrakube-storage-dup\",\n" +
                "      \"description\": \"Terrakube Storage Module\",\n" +
                "      \"provider\": \"azurerm\",\n" +
                "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-cloud-storage.git\"\n" +
                "    }\n" +
                "  }\n" +
                "}";

        String moduleId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(body)
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");

        // Wait for the first module to be durably committed and queryable before attempting the
        // second create - this models the real reported scenario (two sequential, spaced-out create
        // attempts), not a genuinely concurrent race, which the module-refresh job (fired
        // near-immediately in this test environment, unlike its real 300s production delay) can
        // otherwise interleave with a back-to-back second POST.
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module/" + moduleId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body(body)
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CONFLICT.value())
                .body("errors[0].detail", org.hamcrest.Matchers.containsString("already exists"));
    }

    @Test
    void createModuleWithInvalidSystemIsRejected() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"module\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"terrakube-storage-ecs\",\n" +
                        "      \"description\": \"Terrakube Storage Module\",\n" +
                        "      \"provider\": \"aws-ecs\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-cloud-storage.git\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void updateModuleWithInvalidSystemIsRejected() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"module\",\n" +
                        "    \"id\": \"4e92ff1e-9937-400f-848d-f0ea367927bf\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"provider\": \"aws-ecs\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module/4e92ff1e-9937-400f-848d-f0ea367927bf")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void searchModuleAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module/4e92ff1e-9937-400f-848d-f0ea367927bf")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void createModuleAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"module\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"terrakube-storage-fake\",\n" +
                        "      \"description\": \"Terrakube Storage Module\",\n" +
                        "      \"provider\": \"azurerm\",\n" +
                        "      \"source\": \"https://github.com/AzBuilder/terraform-azurerm-terrakube-cloud-storage.git\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/f5365c9e-bc11-4781-b649-45a281ccdd4a/module")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }
}
