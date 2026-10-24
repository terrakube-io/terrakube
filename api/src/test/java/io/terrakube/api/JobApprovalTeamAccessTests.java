package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;

@TestPropertySource(properties = "spring.quartz.auto-startup=false")
class JobApprovalTeamAccessTests extends ServerApplicationTests {
    @MockitoBean ScheduleJobService scheduleJobService;

    @Test
    void planOnlyCallerCannotRemoveOrReplaceTheDesignatedApprovalTeam() {
        var org = organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = new Workspace();
        workspace.setOrganization(org);
        workspace.setName("approval-team-access-test");
        workspace.setSource("https://github.com/example/infra.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.14.0");
        workspace = workspaceRepository.save(workspace);
        Team team = new Team();
        team.setName("APPROVAL_TEAM_PLAN_ONLY");
        team.setRole("plan");
        team.setOrganization(org);
        teamRepository.save(team);
        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(workspace);
        job.setStatus(JobStatus.waitingApproval);
        job.setApprovalTeam("APPLY_ONLY");
        job = jobRepository.save(job);
        String id = Integer.toString(job.getId());
        String token = generatePAT("APPROVAL_TEAM_PLAN_ONLY");
        String route = "/api/v1/organization/" + org.getId() + "/job/" + id;
        var approve = given().auth().oauth2(token).contentType("application/vnd.api+json")
                .body(Map.of("data", Map.of("type", "job", "id", id, "attributes", Map.of("status", "approved"))))
                .patch(route);
        assertEquals(403, approve.statusCode(), "The plan role must not approve the named-team gate");
        for (String replacement : new String[]{"", "APPROVAL_TEAM_PLAN_ONLY", null}) {
            var response = given().auth().oauth2(token).contentType("application/vnd.api+json")
                    .body(Map.of("data", Map.of("type", "job", "id", id,
                            "attributes", Collections.singletonMap("approvalTeam", replacement))))
                    .patch(route);
            assertEquals(403, response.statusCode(), "The plan role must not change approvalTeam");
            assertEquals("APPLY_ONLY", jobRepository.findById(job.getId()).orElseThrow().getApprovalTeam());
        }
    }
}
