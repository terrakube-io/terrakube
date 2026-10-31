package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

class TerraformHttpBackendIntegrationTest extends ServerApplicationTests {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WS_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String OTHER_WS_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG_ID = "22222222-2222-2222-2222-222222222222";
    private static final String BACKEND_URL = "/tfstate/v1/organization/" + ORG_ID + "/workspace/" + WS_ID + "/backend/terraform.tfstate";
    private static final String BACKEND_FILE_PATH = "%s/.terraform-spring-boot/local/backend/%s/%s/terraform.tfstate";

    private Job activeJob;
    private Step activeStep;

    @BeforeEach
    void setupTest() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cleanupBackendFile();

        Organization org = organizationRepository.findById(UUID.fromString(ORG_ID)).orElseThrow();
        Workspace ws = workspaceRepository.findById(UUID.fromString(WS_ID)).orElseThrow();

        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(ws);
        job.setStatus(JobStatus.running);
        activeJob = jobRepository.save(job);

        Step step = new Step();
        step.setJob(activeJob);
        step.setStepNumber(100);
        step.setStatus(JobStatus.running);
        activeStep = stepRepository.save(step);
    }

    @AfterEach
    void tearDownTest() {
        cleanupBackendFile();
        if (activeStep != null) {
            stepRepository.deleteById(activeStep.getId());
        }
        if (activeJob != null) {
            jobRepository.deleteById(activeJob.getId());
        }
    }

    private void cleanupBackendFile() {
        File backendFile = new File(String.format(BACKEND_FILE_PATH, FileUtils.getUserDirectoryPath(), ORG_ID, WS_ID));
        if (backendFile.exists()) {
            FileUtils.deleteQuietly(backendFile);
        }
    }

    private String generateWorkspaceExecutorToken(String orgId, String wsId, String jobId, String stepId) {
        Map<String, Object> claims = new HashMap<>();
        if (orgId != null) {
            claims.put("organizationId", orgId);
        }
        if (wsId != null) {
            claims.put("workspaceId", wsId);
        }
        if (jobId != null) {
            claims.put("jobId", jobId);
        }
        if (stepId != null) {
            claims.put("stepId", stepId);
        }
        return generateSystemToken("TerrakubeInternal (EXECUTOR)", claims);
    }

    private String basicAuthHeader(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testHttpBackendStateFullLifecycleWithBasicAuth() throws Exception {
        String scopedToken = generateWorkspaceExecutorToken(
                ORG_ID, WS_ID, String.valueOf(activeJob.getId()), activeStep.getId().toString());
        String authHeader = basicAuthHeader("terraform", scopedToken);

        // 1. Initial GET when no state exists -> 204 No Content
        given()
                .header("Authorization", authHeader)
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // 2. POST state with valid Content-MD5 -> 200 OK
        String stateV1 = "{\"version\":4,\"terraform_version\":\"1.8.0\",\"serial\":1}";
        byte[] stateV1Bytes = stateV1.getBytes(StandardCharsets.UTF_8);
        String md5V1 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(stateV1Bytes));

        given()
                .header("Authorization", authHeader)
                .header("Content-MD5", md5V1)
                .contentType("application/json")
                .body(stateV1Bytes)
                .when()
                .post(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.OK.value());

        // 3. GET state -> 200 OK with stateV1
        given()
                .header("Authorization", authHeader)
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body(equalTo(stateV1));

        // 4. PUT updated state -> 200 OK
        String stateV2 = "{\"version\":4,\"terraform_version\":\"1.8.0\",\"serial\":2}";
        given()
                .header("Authorization", authHeader)
                .contentType("application/json")
                .body(stateV2.getBytes(StandardCharsets.UTF_8))
                .when()
                .put(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.OK.value());

        // 5. GET updated state -> 200 OK with stateV2
        given()
                .header("Authorization", authHeader)
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body(equalTo(stateV2));

        // 6. DELETE state -> 200 OK
        given()
                .header("Authorization", authHeader)
                .when()
                .delete(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.OK.value());

        // 7. GET after DELETE -> 204 No Content
        given()
                .header("Authorization", authHeader)
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void testHttpBackendPostRejectsInvalidContentMd5() {
        String scopedToken = generateWorkspaceExecutorToken(
                ORG_ID, WS_ID, String.valueOf(activeJob.getId()), activeStep.getId().toString());
        String authHeader = basicAuthHeader("terraform", scopedToken);

        String stateJson = "{\"version\":4,\"serial\":1}";
        String wrongMd5 = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        given()
                .header("Authorization", authHeader)
                .header("Content-MD5", wrongMd5)
                .contentType("application/json")
                .body(stateJson.getBytes(StandardCharsets.UTF_8))
                .when()
                .post(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void testHttpBackendRejectsMissingAndMalformedAuthWith401() {
        given()
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());

        given()
                .header("Authorization", "Basic !!!not-base64!!!")
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());

        given()
                .header("Authorization", basicAuthHeader("terraform", "not-a-valid-jwt"))
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void testHttpBackendRejectsCompletedStepTokenWith401() {
        activeStep.setStatus(JobStatus.completed);
        stepRepository.save(activeStep);

        String scopedToken = generateWorkspaceExecutorToken(
                ORG_ID, WS_ID, String.valueOf(activeJob.getId()), activeStep.getId().toString());

        given()
                .header("Authorization", basicAuthHeader("terraform", scopedToken))
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void testHttpBackendRejectsCrossWorkspaceCrossOrgServiceAndUserPatTokensWith403() {
        // 1. Mismatched workspaceId
        String wrongWsToken = generateWorkspaceExecutorToken(
                ORG_ID, OTHER_WS_ID, String.valueOf(activeJob.getId()), activeStep.getId().toString());
        given()
                .header("Authorization", basicAuthHeader("terraform", wrongWsToken))
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // 2. Mismatched organizationId
        String wrongOrgToken = generateWorkspaceExecutorToken(
                OTHER_ORG_ID, WS_ID, String.valueOf(activeJob.getId()), activeStep.getId().toString());
        given()
                .header("Authorization", basicAuthHeader("terraform", wrongOrgToken))
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // 3. Unscoped EXECUTOR_SERVICE token
        given()
                .header("Authorization", basicAuthHeader("terraform", generateExecutorToken()))
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // 4. REGISTRY_SERVICE token
        given()
                .header("Authorization", basicAuthHeader("terraform", generateRegistryToken()))
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // 5. User PAT (TERRAKUBE_ADMIN) via Basic Auth
        String adminPat = generatePAT("TERRAKUBE_ADMIN");
        given()
                .header("Authorization", basicAuthHeader("terraform", adminPat))
                .when()
                .get(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // 6. User PAT (TERRAKUBE_ADMIN) via Bearer Auth on POST
        given()
                .header("Authorization", "Bearer " + adminPat)
                .contentType("application/json")
                .body("{\"version\":4}".getBytes(StandardCharsets.UTF_8))
                .when()
                .post(BACKEND_URL)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }
}

