package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.team.Team;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

public class CollectionTests extends ServerApplicationTests {


    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void createCollectionAsOrgMember() {

        Optional<Team> teamOptional = teamRepository.findById(UUID.fromString("58529721-425e-44d7-8b0d-1d515043c2f7"));
        Team team = teamOptional.get();
        team.setManageCollection(true);
        teamRepository.save(team);

        String collectionId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"collection\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"Collection1\",\n" +
                        "      \"description\": \"Sample Description\",\n" +
                        "      \"priority\": 10\n" +
                        "      }" +
                        "   }" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");


        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"item\",\n" +
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
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/"+collectionId+"/item/")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"collection\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"Collection1\",\n" +
                        "      \"description\": \"Sample Description\",\n" +
                        "      \"priority\": 10\n" +
                        "      }" +
                        "   }" +
                        "}")
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/"+collectionId+"/item/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        String referenceId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"reference\",\n" +
                        "    \"attributes\": {\n" +
                        "            \"description\": \"my random description\"\n" +
                        "         },\n" +
                        "    \"relationships\": {\n" +
                        "      \"workspace\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"workspace\",\n" +
                        "          \"id\": \"5ed411ca-7ab8-4d2f-b591-02d0d5788afc\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/"+collectionId+"/reference/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");


        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"collection\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"Collection1\",\n" +
                        "      \"description\": \"Sample Description\",\n" +
                        "      \"priority\": 10\n" +
                        "      }" +
                        "   }" +
                        "}")
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/"+collectionId+"/reference/"+referenceId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        team.setManageCollection(false);
        teamRepository.save(team);
    }

    @Test
    void createCollectionAsNonOrgMember() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_GROUP"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"collection\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"Collection1\",\n" +
                        "      \"description\": \"Sample Description\",\n" +
                        "      \"priority\": 10\n" +
                        "      }" +
                        "   }" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        Optional<Team> teamOptional = teamRepository.findById(UUID.fromString("58529721-425e-44d7-8b0d-1d515043c2f7"));
        Team team = teamOptional.get();
        team.setManageCollection(true);
        teamRepository.save(team);

        String collectionId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"collection\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"collecton_random\",\n" +
                        "      \"description\": \"Sample Description\",\n" +
                        "      \"priority\": 10\n" +
                        "      }" +
                        "   }" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");


        given()
                .headers("Authorization", "Bearer " + generatePAT("FAKE_GROUP"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"item\",\n" +
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
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/"+collectionId+"/item/")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.FORBIDDEN.value());

        team.setManageCollection(false);
        teamRepository.save(team);

    }

    @Test
    void testCollectionPriorityAsOrgMember() {

        String EXECUTOR_ENDPOINT="http://localhost:" + wireMockServer.port() + "/fake/executor";

        wireMockServer.resetAll();

        stubFor(post(urlPathEqualTo("/fake/executor/api/v1/terraform-rs"))
                .withPort(wireMockServer.port())
                .willReturn(aResponse()
                        .withStatus(org.apache.http.HttpStatus.SC_ACCEPTED)));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"team\",\n" +
                        "    \"id\": \"58529721-425e-44d7-8b0d-1d515043c2f7\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"manageCollection\": true\n" +
                        "      }" +
                        "   }" +
                        "}")
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team/58529721-425e-44d7-8b0d-1d515043c2f7")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        //Create new collection with priority 10 and 2 items
        String collectionId10 = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"collection\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"collection_priority_10\",\n" +
                        "      \"description\": \"Sample Description\",\n" +
                        "      \"priority\": 10\n" +
                        "      }" +
                        "   }" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        //Create new collection with priority 20 and 1 items that will override the value from collection collectionId10
        String collectionId20 = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"collection\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"collection_priority_20\",\n" +
                        "      \"description\": \"Sample Description\",\n" +
                        "      \"priority\": 20\n" +
                        "      }" +
                        "   }" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        //Value to be overridden from collectionId20
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"item\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"key\": \"test_value\",\n" +
                        "      \"value\": \"priority_10\",\n" +
                        "      \"sensitive\": false,\n" +
                        "      \"hcl\": false,\n" +
                        "      \"category\": \"ENV\",\n" +
                        "      \"description\": \"value_priority_10\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/"+collectionId10+"/item/")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());

        // this should override the value from collectionId10
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"item\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"key\": \"test_value\",\n" +
                        "      \"value\": \"priority_20\",\n" +
                        "      \"sensitive\": false,\n" +
                        "      \"hcl\": false,\n" +
                        "      \"category\": \"ENV\",\n" +
                        "      \"description\": \"value_priority_10\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/"+collectionId20+"/item/")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());

        // simply other value that will be in the final ENV vars
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"item\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"key\": \"other_value\",\n" +
                        "      \"value\": \"other_value\",\n" +
                        "      \"sensitive\": false,\n" +
                        "      \"hcl\": false,\n" +
                        "      \"category\": \"ENV\",\n" +
                        "      \"description\": \"value_priority_10\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/"+collectionId10+"/item/")
                .then()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value());

        //Associate workspace with collectionId10
        String referenceId10 = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"reference\",\n" +
                        "    \"attributes\": {\n" +
                        "            \"description\": \"my random description\"\n" +
                        "         },\n" +
                        "    \"relationships\": {\n" +
                        "      \"workspace\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"workspace\",\n" +
                        "          \"id\": \"5ed411ca-7ab8-4d2f-b591-02d0d5788afc\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/"+collectionId10+"/reference/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        //Associate workspace with collectionId20
        String referenceId20 = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"reference\",\n" +
                        "    \"attributes\": {\n" +
                        "            \"description\": \"my random description\"\n" +
                        "         },\n" +
                        "    \"relationships\": {\n" +
                        "      \"workspace\": {\n" +
                        "        \"data\": {\n" +
                        "          \"type\": \"workspace\",\n" +
                        "          \"id\": \"5ed411ca-7ab8-4d2f-b591-02d0d5788afc\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/collection/"+collectionId20+"/reference/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"team\",\n" +
                        "    \"id\": \"58529721-425e-44d7-8b0d-1d515043c2f7\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"manageCollection\": false\n" +
                        "      }" +
                        "   }" +
                        "}")
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/team/58529721-425e-44d7-8b0d-1d515043c2f7")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        String agentId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"agent\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"fake-agent\",\n" +
                        "      \"url\": \""+EXECUTOR_ENDPOINT+"\",\n" +
                        "      \"description\": \"This is a sample agent\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/agent/")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "      \"type\": \"agent\",\n" +
                        "      \"id\": \""+agentId+"\"\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc/relationships/agent")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());

        Job job = new Job();
        job.setPlanChanges(true);
        job.setRefresh(true);
        job.setRefreshOnly(false);
        job.setWorkspace(workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).get());
        job.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        job.setStatus(JobStatus.pending);
        job.setAutoApply(true);
        job.setComments("fake-job");
        job.setVia("CLI");
        job.setTemplateReference("42201234-a5e2-4c62-b2fc-9729ca6b4515");
        job = jobRepository.save(job);

        Flow flow = new Flow();
        flow.setName("Plan");
        flow.setType("terraformPlan");
        flow.setStep(100);

        ExecutorContext executorContext = executorService.execute(job, UUID.randomUUID().toString(), flow);

        Assertions.assertNotNull(executorContext.getEnvironmentVariables());
        Assertions.assertEquals(executorContext.getEnvironmentVariables().get("test_value"), "priority_20");
        Assertions.assertEquals(executorContext.getEnvironmentVariables().get("other_value"), "other_value");
        Assertions.assertEquals(executorContext.getEnvironmentVariables().size(), 6);

    }



}
