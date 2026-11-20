package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * The plan counts of a run are shown in the run list, so nobody may write them through a request: the API records
 * them itself from the final plan snapshot. A team member who can manage jobs must not be able to make a run look
 * smaller than it is, and an update of a job through the API would wake the scheduler again.
 */
class JobPlanSummaryPermissionTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String TEAM_ID = "58529721-425e-44d7-8b0d-1d515043c2f7";

    private Job job;
    private String previousRole;
    private boolean previousManageJob;

    @BeforeEach
    void aJobAndATeamThatMayManageJobs() {
        Team team = teamRepository.findById(UUID.fromString(TEAM_ID)).orElseThrow();
        previousRole = team.getRole();
        previousManageJob = team.isManageJob();
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

    // The job is pending, so the scheduler's reconciliation would give it a trigger that fires in later tests, and the
    // team is shared with every other test of the organization.
    @AfterEach
    void removeTheJobAndRestoreTheTeam() {
        jobRepository.deleteById(job.getId());
        Team team = teamRepository.findById(UUID.fromString(TEAM_ID)).orElseThrow();
        team.setRole(previousRole);
        team.setManageJob(previousManageJob);
        teamRepository.save(team);
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
    void aTeamMemberCannotWriteThePlanCounts() {
        int status = patch(generatePAT("TERRAKUBE_DEVELOPERS"), "{\"planAdd\":0,\"planChange\":0,\"planDestroy\":0}");

        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getPlanAdd()).isNull();
    }

    @Test
    void theExecutorCannotEitherBecauseTheApiRecordsThemItself() {
        int status = patch(generateExecutorToken(), "{\"planAdd\":3,\"planChange\":1,\"planDestroy\":0}");

        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getPlanAdd()).isNull();
    }

    @Test
    void theCountsTheApiRecordedAreReadableByTheTeam() {
        jobRepository.updatePlanSummary(job.getId(), 3, 1, 0);

        Integer added = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/" + ORGANIZATION_ID + "/job/" + job.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract().path("data.attributes.planAdd");
        assertThat(added).isEqualTo(3);
    }

    @Test
    void aJobWithoutASummaryReadsAsNull() {
        Integer added = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/" + ORGANIZATION_ID + "/job/" + job.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract().path("data.attributes.planAdd");
        assertThat(added).isNull();
    }
}
