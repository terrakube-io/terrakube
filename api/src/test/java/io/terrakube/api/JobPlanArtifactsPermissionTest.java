package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import io.terrakube.api.plugin.storage.StorageTypeService;
import org.springframework.http.HttpStatus;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * Where a plan step stored its artifacts is read back by the apply step to decide which file to
 * unpack, so only the executor may write it: a team member who can manage jobs must not be able to
 * point an apply at another job's bundle.
 */
class JobPlanArtifactsPermissionTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String TEAM_ID = "58529721-425e-44d7-8b0d-1d515043c2f7";
    private static final String LOCATION = "https://bucket.example/plan-artifacts/o/w/1/s/plan-artifacts.tar.gz";

    private Job job;

    @MockitoSpyBean
    private StorageTypeService storageTypeService;

    @BeforeEach
    void aJobAndATeamThatMayManageJobs() {
        Team team = teamRepository.findById(UUID.fromString(TEAM_ID)).orElseThrow();
        team.setManageJob(true);
        team.setRole("admin");
        teamRepository.save(team);

        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        Workspace workspace = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
        Job newJob = new Job();
        newJob.setStatus(JobStatus.pending);
        newJob.setOrganization(organization);
        newJob.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(newJob);
    }

    private int patch(String token, String attributes) {
        return given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"job\",\"id\":\"" + job.getId() + "\",\"attributes\":" + attributes + "}}")
                .when()
                .patch("/api/v1/organization/" + ORGANIZATION_ID + "/job/" + job.getId())
                .then()
                .extract().statusCode();
    }

    @Test
    void aTeamMemberCannotSetWhereTheArtifactsAre() {
        int status = patch(generatePAT("TERRAKUBE_DEVELOPERS"), "{\"terraformPlanArtifacts\":\"" + LOCATION + "\"}");

        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getTerraformPlanArtifacts()).isNull();
    }

    @Test
    void aTeamMemberCannotSetTheChecksumEither() {
        int status = patch(generatePAT("TERRAKUBE_DEVELOPERS"), "{\"terraformPlanArtifactsChecksum\":\"abc\"}");

        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getTerraformPlanArtifactsChecksum()).isNull();
    }

    @Test
    void theExecutorRecordsAndLaterClearsThem() {
        int recorded = patch(generateExecutorToken(),
                "{\"terraformPlanArtifacts\":\"" + LOCATION + "\",\"terraformPlanArtifactsChecksum\":\"abc123\"}");

        assertThat(recorded).isEqualTo(HttpStatus.NO_CONTENT.value());
        Job stored = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(stored.getTerraformPlanArtifacts()).isEqualTo(LOCATION);
        assertThat(stored.getTerraformPlanArtifactsChecksum()).isEqualTo("abc123");

        int cleared = patch(generateExecutorToken(), "{\"terraformPlanArtifacts\":\"\",\"terraformPlanArtifactsChecksum\":\"\"}");

        assertThat(cleared).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getTerraformPlanArtifacts()).isEmpty();
    }

    @Test
    void theArtifactFieldsAreReadableByTheTeamThatCanSeeTheJob() {
        patch(generateExecutorToken(), "{\"terraformPlanArtifacts\":\"" + LOCATION + "\"}");

        String stored = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/" + ORGANIZATION_ID + "/job/" + job.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract().path("data.attributes.terraformPlanArtifacts");

        assertThat(stored).isEqualTo(LOCATION);
    }

    @Test
    void whenTheJobEndsItsArtifactsAreDeletedAndTheLocationIsCleared() {
        job.setTerraformPlanArtifacts(LOCATION);
        job = jobRepository.saveAndFlush(job);

        int status = patch(generateExecutorToken(), "{\"status\":\"completed\"}");

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT.value());
        org.mockito.Mockito.verify(storageTypeService, org.mockito.Mockito.timeout(5000))
                .deleteJobArtifacts(ORGANIZATION_ID, WORKSPACE_ID, job.getId());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobRepository.findById(job.getId()).orElseThrow().getTerraformPlanArtifacts()).isNull());
    }

    @Test
    void whileTheJobIsStillRunningItsArtifactsStay() {
        job.setTerraformPlanArtifacts(LOCATION);
        job = jobRepository.saveAndFlush(job);

        // Any status that is not final. "approved" cannot be used: a job can only be approved while it
        // waits for approval, and this one is pending.
        int status = patch(generateExecutorToken(), "{\"status\":\"running\"}");

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT.value());
        org.mockito.Mockito.verify(storageTypeService, org.mockito.Mockito.never())
                .deleteJobArtifacts(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getTerraformPlanArtifacts()).isEqualTo(LOCATION);
    }
}
