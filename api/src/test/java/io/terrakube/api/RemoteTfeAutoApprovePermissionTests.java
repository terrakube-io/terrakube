package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.ContentRepository;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto approving a run from the Terraform CLI (terraform apply -auto-approve) skips the approval step, so it needs
 * the right to approve and not only the right to plan. The right can come from the organization team, the
 * workspace access or the project access, like it does in the UI and API.
 */
@TestPropertySource(properties = "spring.quartz.auto-startup=false")
class RemoteTfeAutoApprovePermissionTests extends ServerApplicationTests {
    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @MockitoBean ScheduleJobService scheduleJobService;
    @Autowired ContentRepository contentRepository;

    private final List<Workspace> workspaces = new ArrayList<>();
    private final List<Content> contents = new ArrayList<>();
    private final List<Team> teams = new ArrayList<>();
    private final List<Project> projects = new ArrayList<>();

    @AfterEach
    void cleanup() {
        jobRepository.findAll().stream()
                .filter(job -> job.getWorkspace() != null
                        && workspaces.stream().anyMatch(workspace -> workspace.getId().equals(job.getWorkspace().getId())))
                .forEach(jobRepository::delete);
        contentRepository.deleteAll(contents);
        for (Workspace item : workspaces) {
            Workspace current = workspaceRepository.findById(item.getId()).orElseThrow();
            current.setDeleted(true);
            current.setProject(null);
            workspaceRepository.save(current);
        }
        projects.forEach(projectRepository::delete);
        teamRepository.deleteAll(teams);
        workspaces.clear();
        contents.clear();
        projects.clear();
        teams.clear();
    }

    private Workspace workspace() {
        Workspace workspace = new Workspace();
        workspace.setName("tfe-auto-approve-" + UUID.randomUUID());
        workspace.setSource("empty");
        workspace.setBranch("remote-content");
        workspace.setTerraformVersion("1.14.0");
        workspace.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        Workspace saved = workspaceRepository.save(workspace);
        workspaces.add(saved);
        return saved;
    }

    private String team(String role, boolean plan, boolean approve, Workspace workspace) {
        Team team = new Team();
        team.setName("AUTO_APPROVE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        team.setRole(role);
        team.setPlanJob(plan);
        team.setApproveJob(approve);
        team.setOrganization(workspace.getOrganization());
        teams.add(teamRepository.save(team));
        return team.getName();
    }

    private void workspaceAccess(Workspace target, String name, String role, boolean plan, boolean approve) {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"access\",\"attributes\":{\"name\":\"" + name + "\",\"role\":\"" + role + "\","
                        + "\"manageWorkspace\":false,\"manageJob\":false,\"manageState\":false,"
                        + "\"planJob\":" + plan + ",\"approveJob\":" + approve + "}}}")
                .post("/api/v1/organization/" + ORG + "/workspace/" + target.getId() + "/access")
                .then().statusCode(201);
    }

    private void projectAccess(Workspace target, String name, String role, boolean plan, boolean approve) {
        Project project = new Project();
        project.setName("auto-approve-" + UUID.randomUUID());
        project.setDescription("auto approve permission test");
        project.setOrganization(target.getOrganization());
        ProjectAccess access = new ProjectAccess();
        access.setName(name);
        access.setRole(role);
        access.setPlanJob(plan);
        access.setApproveJob(approve);
        access.setProject(project);
        project.getProjectAccess().add(access);
        project = projectRepository.save(project);
        projects.add(project);
        Workspace current = workspaceRepository.findById(target.getId()).orElseThrow();
        current.setProject(project);
        workspaceRepository.save(current);
    }

    private io.restassured.response.Response run(String team, Workspace workspace, boolean speculative, boolean destroy,
                                                 boolean autoApply) {
        Content content = new Content();
        content.setWorkspace(workspace);
        content.setSpeculative(speculative);
        content = contentRepository.save(content);
        contents.add(content);
        Map<String, Object> body = Map.of("data", Map.of("type", "runs",
                "attributes", Map.of("is-destroy", destroy, "auto-apply", autoApply),
                "relationships", Map.of(
                        "workspace", Map.of("data", Map.of("type", "workspaces", "id", workspace.getId().toString())),
                        "configuration-version", Map.of("data", Map.of("type", "configuration-versions", "id", content.getId().toString())))));
        return given().auth().oauth2(generatePAT(team)).contentType("application/vnd.api+json").body(body)
                .post("/remote/tfe/v2/runs");
    }

    private void assertRefused(String team, Workspace workspace, boolean destroy) {
        long before = jobRepository.count();

        var response = run(team, workspace, false, destroy, true);

        assertEquals(403, response.statusCode(), response.asString());
        assertTrue(response.asString().contains("Auto approving a run requires permission to approve"), response.asString());
        assertEquals(before, jobRepository.count(), "a refused run must not create a job");
    }

    // Organization team

    @Test
    void aTeamThatOnlyPlansCannotAutoApproveAnApplyOrADestroy() {
        Workspace workspace = workspace();
        String planner = team("plan", false, false, workspace);

        assertRefused(planner, workspace, false);
        assertRefused(planner, workspace, true);
    }

    @Test
    void aTeamThatOnlyPlansStillQueuesARunThatWaitsForAnApproval() {
        Workspace workspace = workspace();
        String planner = team("plan", false, false, workspace);

        assertEquals(201, run(planner, workspace, false, false, false).statusCode());
        assertEquals(201, run(planner, workspace, true, false, false).statusCode());
    }

    @Test
    void autoApproveOnASpeculativePlanAsksForNothing() {
        Workspace workspace = workspace();
        String planner = team("plan", false, false, workspace);

        assertEquals(201, run(planner, workspace, true, false, true).statusCode());
    }

    @Test
    void aTeamThatMayApproveCanAutoApprove() {
        Workspace workspace = workspace();

        assertEquals(201, run(team("write", false, false, workspace), workspace, false, false, true).statusCode());
        assertEquals(201, run(team("custom", true, true, workspace), workspace, false, false, true).statusCode());
    }

    @Test
    void aCustomTeamThatOnlyPlansIsRefused() {
        Workspace workspace = workspace();

        assertRefused(team("custom", true, false, workspace), workspace, false);
    }

    // Workspace access

    @Test
    void theWorkspaceAccessCanGrantTheRightToApprove() {
        Workspace workspace = workspace();
        String name = team("plan", false, false, workspace);
        assertRefused(name, workspace, false);

        workspaceAccess(workspace, name, "custom", true, true);

        assertEquals(201, run(name, workspace, false, false, true).statusCode());
    }

    // Project access

    @Test
    void theProjectAccessAloneLetsATeamPlanFromTheCli() {
        Workspace workspace = workspace();
        String name = team("read", false, false, workspace);
        assertEquals(403, run(name, workspace, true, false, false).statusCode());

        projectAccess(workspace, name, "plan", true, false);

        assertEquals(201, run(name, workspace, true, false, false).statusCode());
    }

    @Test
    void aProjectAccessThatOnlyPlansCannotAutoApprove() {
        Workspace workspace = workspace();
        String name = team("read", false, false, workspace);
        projectAccess(workspace, name, "plan", true, false);

        assertRefused(name, workspace, false);
    }

    @Test
    void aProjectAccessThatMayApproveCanAutoApprove() {
        Workspace workspace = workspace();
        String name = team("read", false, false, workspace);
        projectAccess(workspace, name, "custom", true, true);

        assertEquals(201, run(name, workspace, false, false, true).statusCode());
    }

    @Test
    void aTeamOutsideEveryAccessListCannotAutoApprove() {
        Workspace workspace = workspace();
        String outsider = team("read", false, false, workspace);
        projectAccess(workspace, "SOME_OTHER_TEAM", "write", true, true);

        assertEquals(403, run(outsider, workspace, false, false, true).statusCode());
    }
}
