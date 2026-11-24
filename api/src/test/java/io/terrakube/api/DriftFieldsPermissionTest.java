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
 * Only the drift check writes the drift fields. A workspace manager who could set them could mark a drifted workspace
 * clean, or move the run id ahead so that real checks stop being recorded. Nothing but these annotations stands in the
 * way, so a request that tries it must be refused and change nothing.
 */
class DriftFieldsPermissionTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String TEAM_ID = "58529721-425e-44d7-8b0d-1d515043c2f7";

    private Job job;
    private String previousRole;
    private boolean previousManageJob;
    private boolean previousManageWorkspace;

    @BeforeEach
    void aJobAndATeamThatMayManageThem() {
        Team team = teamRepository.findById(UUID.fromString(TEAM_ID)).orElseThrow();
        previousRole = team.getRole();
        previousManageJob = team.isManageJob();
        previousManageWorkspace = team.isManageWorkspace();
        team.setManageJob(true);
        team.setManageWorkspace(true);
        team.setRole("admin");
        teamRepository.save(team);

        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        Workspace workspace = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
        Job newJob = new Job();
        newJob.setStatus(JobStatus.completed);
        newJob.setOrganization(organization);
        newJob.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(newJob);
    }

    @AfterEach
    void removeTheJobAndRestoreTheTeam() {
        jobRepository.deleteById(job.getId());
        Team team = teamRepository.findById(UUID.fromString(TEAM_ID)).orElseThrow();
        team.setRole(previousRole);
        team.setManageJob(previousManageJob);
        team.setManageWorkspace(previousManageWorkspace);
        teamRepository.save(team);
    }

    private int patchJob(String token, String attributes) {
        return given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"job\",\"id\":\"" + job.getId() + "\",\"attributes\":" + attributes + "}}")
                .when()
                .patch("/api/v1/organization/" + ORGANIZATION_ID + "/job/" + job.getId())
                .then()
                .extract().statusCode();
    }

    private int patchWorkspace(String token, String attributes) {
        return given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspace\",\"id\":\"" + WORKSPACE_ID + "\",\"attributes\":" + attributes + "}}")
                .when()
                .patch("/api/v1/organization/" + ORGANIZATION_ID + "/workspace/" + WORKSPACE_ID)
                .then()
                .extract().statusCode();
    }

    private Workspace workspace() {
        return workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
    }

    @Test
    void aTeamMemberCannotWriteTheDriftFieldsOfAWorkspace() {
        for (String attributes : new String[] {"{\"driftStatus\":\"clean\"}", "{\"driftJobId\":999999}",
                "{\"driftFirstSeen\":\"2020-01-01T00:00:00Z\"}", "{\"driftLastChecked\":\"2020-01-01T00:00:00Z\"}"}) {
            assertThat(patchWorkspace(generatePAT("TERRAKUBE_DEVELOPERS"), attributes)).as(attributes)
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
        }

        assertThat(workspace().getDriftStatus()).isNull();
        assertThat(workspace().getDriftJobId()).isNull();
        assertThat(workspace().getDriftFirstSeen()).isNull();
        assertThat(workspace().getDriftLastChecked()).isNull();
    }

    @Test
    void theExecutorCannotWriteThemEither() {
        assertThat(patchWorkspace(generateExecutorToken(), "{\"driftStatus\":\"drifted\",\"driftJobId\":1}"))
                .isEqualTo(HttpStatus.FORBIDDEN.value());

        assertThat(workspace().getDriftStatus()).isNull();
    }

    @Test
    void aTeamMemberAndTheExecutorCannotWriteWhatARunFoundOut() {
        assertThat(patchJob(generatePAT("TERRAKUBE_DEVELOPERS"), "{\"driftResult\":\"clean\"}"))
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(patchJob(generateExecutorToken(), "{\"driftResult\":\"drift\"}")).isEqualTo(HttpStatus.FORBIDDEN.value());

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getDriftResult()).isNull();
    }
}
