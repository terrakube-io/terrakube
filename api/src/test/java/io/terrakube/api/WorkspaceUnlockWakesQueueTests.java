package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.TeamRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.team.Team;

/** #3728: unlocking a workspace through the API wakes the FIFO queue head. */
class WorkspaceUnlockWakesQueueTests extends ServerApplicationTests {

    private static final String WORKSPACE_PATH = "/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/workspace/5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    @MockitoSpyBean
    private ScheduleJobService scheduleJobService;

    private static final UUID WORKSPACE_ID = UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc");

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @BeforeEach
    void allowWorkspaceManagement() {
        Team team = teamRepository.findById(UUID.fromString("58529721-425e-44d7-8b0d-1d515043c2f7")).get();
        team.setManageWorkspace(true);
        teamRepository.save(team);
    }

    private void setLocked(boolean locked) {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type",
                        "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspace\",\"id\":\"5ed411ca-7ab8-4d2f-b591-02d0d5788afc\",\"attributes\":{\"locked\":"
                        + locked + "}}}")
                .when()
                .patch(WORKSPACE_PATH)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void lockingDoesNotWakeTheQueueButUnlockingDoes() {
        setLocked(true);
        verify(scheduleJobService, never()).wakeQueueHead();

        setLocked(false);
        verify(scheduleJobService, timeout(5000)).wakeQueueHead();
    }

    @Test
    void unlockingThroughTheApiClearsAStalePrApplyLockDescription() {
        Workspace workspace = workspaceRepository.findById(WORKSPACE_ID).get();
        workspace.setLocked(true);
        workspace.setLockDescription("Locked by PR #7 apply");
        workspaceRepository.save(workspace);

        setLocked(false);

        assertThat(workspaceRepository.findById(WORKSPACE_ID).get().getLockDescription()).isNull();
    }

    @Test
    void unlockingThroughTheTfeApiWakesTheQueueAndClearsTheLockDescription() {
        Team team = teamRepository.findById(UUID.fromString("58529721-425e-44d7-8b0d-1d515043c2f7")).get();
        team.setManageJob(true);
        team.setRole("admin");
        teamRepository.save(team);
        try {
            Workspace workspace = workspaceRepository.findById(WORKSPACE_ID).get();
            workspace.setLocked(true);
            workspace.setLockDescription("Locked by PR #7 apply");
            workspaceRepository.save(workspace);

            given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                    .when()
                    .post("/remote/tfe/v2/workspaces/" + WORKSPACE_ID + "/actions/unlock")
                    .then()
                    .statusCode(HttpStatus.OK.value());

            verify(scheduleJobService, timeout(5000)).wakeQueueHead();
            assertThat(workspaceRepository.findById(WORKSPACE_ID).get().getLockDescription()).isNull();
        } finally {
            team.setManageJob(false);
            team.setRole("custom");
            teamRepository.save(team);
        }
    }
}
