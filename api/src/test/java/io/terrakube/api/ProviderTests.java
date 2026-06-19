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


class ProviderTests extends ServerApplicationTests {

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void searchProviderAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS")).when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/provider")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void searchProviderAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS")).when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/provider")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void createProviderAsOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"provider\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"random\",\n" +
                        "      \"description\": \"Provider description\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}   ")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/provider")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("random"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());
    }

    @Test
    void createImportedProviderFromPrivateRegistrySchedulesRefresh() throws SchedulerException {
        String providerId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"provider\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"customreg\",\n" +
                        "      \"description\": \"Imported from a private registry\",\n" +
                        "      \"imported\": true,\n" +
                        "      \"sourceType\": \"TERRAFORM_REGISTRY\",\n" +
                        "      \"registryHost\": \"registry.example.com\",\n" +
                        "      \"registryNamespace\": \"acme\",\n" +
                        "      \"registryToken\": \"super-secret-token\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/provider")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("customreg"))
                .body("data.attributes.sourceType", IsEqual.equalTo("TERRAFORM_REGISTRY"))
                .body("data.attributes.registryHost", IsEqual.equalTo("registry.example.com"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        boolean jobExist = scheduler.getJobDetail(new JobKey("TerrakubeV2_ProviderRefresh_" + providerId)) != null;
        Assert.isTrue(jobExist, "Refresh job should be created for an imported provider");
    }

    @Test
    void createImportedProviderFromRepositorySchedulesRefresh() throws SchedulerException {
        String providerId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"provider\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"repoprov\",\n" +
                        "      \"description\": \"Imported from a repository release page\",\n" +
                        "      \"imported\": true,\n" +
                        "      \"sourceType\": \"REPOSITORY\",\n" +
                        "      \"repositoryUrl\": \"https://github.com/acme/terraform-provider-repoprov/releases/download/v{version}\",\n" +
                        "      \"repositoryVersions\": \"1.0.0,1.1.0\",\n" +
                        "      \"gpgKeyId\": \"51852D87348FFC4C\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/provider")
                .then()
                .assertThat()
                .body("data.attributes.name", IsEqual.equalTo("repoprov"))
                .body("data.attributes.sourceType", IsEqual.equalTo("REPOSITORY"))
                .body("data.attributes.repositoryVersions", IsEqual.equalTo("1.0.0,1.1.0"))
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        boolean jobExist = scheduler.getJobDetail(new JobKey("TerrakubeV2_ProviderRefresh_" + providerId)) != null;
        Assert.isTrue(jobExist, "Refresh job should be created for a repository imported provider");
    }

    @Test
    void createProviderAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"provider\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"random\",\n" +
                        "      \"description\": \"Provider description\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}   ")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/provider")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }


}
