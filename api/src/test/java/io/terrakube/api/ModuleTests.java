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
