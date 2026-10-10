package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

class ExecutorTokenIntegrationTest extends ServerApplicationTests {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void searchWorkspaceAsExecutorToken_MatchingWorkspace_Success() {
        String token = generateSystemToken(Map.of(
                "organizationId", ORG_ID,
                "workspaceId", WORKSPACE_ID
        ));

        given()
                .headers("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace/" + WORKSPACE_ID)
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value())
                .body("data.attributes.name", IsEqual.equalTo("sample_simple"));
    }

    @Test
    void searchWorkspaceAsExecutorToken_MismatchedWorkspace_Forbidden() {
        String otherWorkspaceId = UUID.randomUUID().toString();
        String token = generateSystemToken(Map.of(
                "organizationId", ORG_ID,
                "workspaceId", otherWorkspaceId
        ));

        given()
                .headers("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace/" + WORKSPACE_ID)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void searchHistoryAsExecutorToken_MatchingWorkspace_Success() {
        String token = generateSystemToken(Map.of(
                "organizationId", ORG_ID,
                "workspaceId", WORKSPACE_ID
        ));

        given()
                .headers("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace/" + WORKSPACE_ID + "/history")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void createHistoryAsExecutorToken_MatchingWorkspace_Success() {
        String token = generateSystemToken(Map.of(
                "organizationId", ORG_ID,
                "workspaceId", WORKSPACE_ID
        ));

        given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"history\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"output\": \"executorOutput\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/workspace/" + WORKSPACE_ID + "/history")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value());
    }

    @Test
    void createHistoryAsExecutorToken_MismatchedWorkspace_Forbidden() {
        String token = generateSystemToken(Map.of(
                "organizationId", ORG_ID,
                "workspaceId", UUID.randomUUID().toString()
        ));

        given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"history\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"output\": \"executorOutput\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/" + ORG_ID + "/workspace/" + WORKSPACE_ID + "/history")
                .then()
                .assertThat()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void searchVariableAsExecutorToken_MatchingWorkspace_Success() {
        String token = generateSystemToken(Map.of(
                "organizationId", ORG_ID,
                "workspaceId", WORKSPACE_ID
        ));

        given()
                .headers("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace/" + WORKSPACE_ID + "/variable")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void searchVariableAsExecutorToken_MismatchedWorkspace_Forbidden() {
        String token = generateSystemToken(Map.of(
                "organizationId", ORG_ID,
                "workspaceId", UUID.randomUUID().toString()
        ));

        given()
                .headers("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/workspace/" + WORKSPACE_ID + "/variable")
                .then()
                .assertThat()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void jobAccessAsExecutorToken_MatchingJob_Allowed_MismatchedJob_Forbidden() {
        Organization org = organizationRepository.findById(UUID.fromString(ORG_ID)).orElseThrow();
        Workspace ws = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();

        Job testJob = new Job();
        testJob.setOrganization(org);
        testJob.setWorkspace(ws);
        testJob.setStatus(JobStatus.pending);
        testJob.setComments("Test job for executor");
        testJob = jobRepository.save(testJob);

        int jobId = testJob.getId();

        Step testStep = new Step();
        testStep.setJob(testJob);
        testStep.setName("terraformPlan");
        testStep.setStatus(JobStatus.pending);
        testStep.setStepNumber(100);
        testStep = stepRepository.save(testStep);

        String stepId = testStep.getId().toString();

        // 1. Token matching jobId, stepId, workspaceId
        String matchingToken = generateSystemToken(Map.of(
                "organizationId", ORG_ID,
                "workspaceId", WORKSPACE_ID,
                "jobId", String.valueOf(jobId),
                "stepId", stepId
        ));

        // Read job: OK
        given()
                .headers("Authorization", "Bearer " + matchingToken)
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/job/" + jobId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value())
                .body("data.attributes.comments", IsEqual.equalTo("Test job for executor"));

        // Read step: OK
        given()
                .headers("Authorization", "Bearer " + matchingToken)
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/job/" + jobId + "/step/" + stepId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value());

        // Update step status: OK
        given()
                .headers("Authorization", "Bearer " + matchingToken, "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"step\",\n" +
                        "    \"id\": \"" + stepId + "\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"status\": \"completed\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/job/" + jobId + "/step/" + stepId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // 2. Token with mismatched jobId
        String mismatchedToken = generateSystemToken(Map.of(
                "organizationId", ORG_ID,
                "workspaceId", WORKSPACE_ID,
                "jobId", "99999",
                "stepId", UUID.randomUUID().toString()
        ));

        // Read job with mismatched token: Not Found (Elide hides unauthorized entity)
        given()
                .headers("Authorization", "Bearer " + mismatchedToken)
                .when()
                .get("/api/v1/organization/" + ORG_ID + "/job/" + jobId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NOT_FOUND.value());

        // Update step with mismatched token: Not Found (Elide hides unauthorized entity)
        given()
                .headers("Authorization", "Bearer " + mismatchedToken, "Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"step\",\n" +
                        "    \"id\": \"" + stepId + "\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"status\": \"failed\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/job/" + jobId + "/step/" + stepId)
                .then()
                .assertThat()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }
}
