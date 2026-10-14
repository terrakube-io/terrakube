package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The version a job resolved from its workspace's constraint is pinned by the executor, once, and only as a
 * version number (issue #3707).
 */
class JobResolvedVersionControllerTest extends ServerApplicationTests {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    private int jobId;

    @BeforeEach
    void createJob() {
        Organization org = organizationRepository.findById(UUID.fromString(ORG_ID)).orElseThrow();
        Workspace workspace = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(workspace);
        job.setStatus(JobStatus.running);
        jobId = jobRepository.save(job).getId();
    }

    @AfterEach
    void removeJob() {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setDeleted(true);
            jobRepository.save(job);
        });
    }

    private String executorToken(int forJob) {
        return generateSystemToken(Map.of("organizationId", ORG_ID, "workspaceId", WORKSPACE_ID, "jobId", String.valueOf(forJob)));
    }

    private io.restassured.response.ValidatableResponse pin(String token, String organization, String version) {
        return given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/json")
                .body("{\"version\":\"" + version + "\"}")
                .when()
                .put("/version/v1/organization/" + organization + "/job/" + jobId)
                .then();
    }

    private String stored() {
        return jobRepository.findById(jobId).orElseThrow().getResolvedTerraformVersion();
    }

    @Test
    void theExecutorPinsTheVersionOnceAndRepeatingItIsHarmless() {
        assertNull(stored());

        pin(executorToken(jobId), ORG_ID, "1.16.5").statusCode(HttpStatus.OK.value()).body("version", equalTo("1.16.5"));
        assertEquals("1.16.5", stored());

        pin(executorToken(jobId), ORG_ID, "1.16.5").statusCode(HttpStatus.OK.value());
        assertEquals("1.16.5", stored());
    }

    @Test
    void aPinnedVersionCannotBeMovedToAnotherRelease() {
        pin(executorToken(jobId), ORG_ID, "1.16.5").statusCode(HttpStatus.OK.value());

        pin(executorToken(jobId), ORG_ID, "1.16.6").statusCode(HttpStatus.CONFLICT.value()).body("version", equalTo("1.16.5"));
        assertEquals("1.16.5", stored());
    }

    @Test
    void onlyAVersionNumberIsAccepted() {
        for (String bad : List.of(">= 1.3.0", "../../etc/passwd", "latest", "1.9", "1.9.5;rm -rf /")) {
            pin(executorToken(jobId), ORG_ID, bad).statusCode(HttpStatus.BAD_REQUEST.value());
        }
        pin(executorToken(jobId), ORG_ID, "1.0.0-" + "a".repeat(80)).statusCode(HttpStatus.BAD_REQUEST.value());
        assertNull(stored());
    }

    @Test
    void preReleaseVersionsAreAccepted() {
        pin(executorToken(jobId), ORG_ID, "1.10.0-rc1").statusCode(HttpStatus.OK.value());
        assertEquals("1.10.0-rc1", stored());
    }

    @Test
    void anExecutorOfAnotherJobCannotPinThisOne() {
        pin(executorToken(jobId + 1000), ORG_ID, "1.16.5").statusCode(HttpStatus.FORBIDDEN.value());
        assertNull(stored());
    }

    @Test
    void anOrdinaryUserCannotPinIt() {
        pin(generatePAT("TERRAKUBE_DEVELOPERS"), ORG_ID, "1.16.5").statusCode(HttpStatus.FORBIDDEN.value());
        assertNull(stored());
    }

    @Test
    void aJobInAnotherOrganizationIsNotFound() {
        pin(executorToken(jobId), UUID.randomUUID().toString(), "1.16.5").statusCode(HttpStatus.NOT_FOUND.value());
        assertNull(stored());
    }

    @Test
    void theVersionCannotBeChangedThroughTheGenericJobApi() {
        pin(executorToken(jobId), ORG_ID, "1.16.5").statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + executorToken(jobId), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"job\",\"id\":\"" + jobId + "\",\"attributes\":{\"resolvedTerraformVersion\":\"1.99.0\"}}}")
                .when()
                .patch("/api/v1/organization/" + ORG_ID + "/job/" + jobId)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
        assertEquals("1.16.5", stored());
    }
}
