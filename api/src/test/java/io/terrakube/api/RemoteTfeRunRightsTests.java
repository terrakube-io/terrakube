package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.ContentRepository;
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
 * terraform destroy from the CLI needs the destroy right, on top of the right to plan. Running terraform plan and
 * terraform apply is not affected.
 */
@TestPropertySource(properties = "spring.quartz.auto-startup=false")
class RemoteTfeRunRightsTests extends ServerApplicationTests {
    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @MockitoBean ScheduleJobService scheduleJobService;
    @Autowired ContentRepository contentRepository;

    private final List<Workspace> workspaces = new ArrayList<>();
    private final List<Content> contents = new ArrayList<>();
    private final List<Team> teams = new ArrayList<>();

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
            workspaceRepository.save(current);
        }
        teamRepository.deleteAll(teams);
        workspaces.clear();
        contents.clear();
        teams.clear();
    }

    private Workspace workspace(String name) {
        Workspace workspace = new Workspace();
        workspace.setName(name);
        workspace.setSource("empty");
        workspace.setBranch("remote-content");
        workspace.setTerraformVersion("1.14.0");
        workspace.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        Workspace saved = workspaceRepository.save(workspace);
        workspaces.add(saved);
        return saved;
    }

    private String team(String name, String role, Workspace workspace) {
        Team team = new Team();
        team.setName(name);
        team.setRole(role);
        team.setOrganization(workspace.getOrganization());
        teams.add(teamRepository.save(team));
        return generatePAT(name);
    }

    private io.restassured.response.Response run(String token, Workspace workspace, boolean destroy) {
        return run(token, workspace, destroy, false);
    }

    private io.restassured.response.Response run(String token, Workspace workspace, boolean destroy, boolean autoApply) {
        Content content = new Content();
        content.setWorkspace(workspace);
        content = contentRepository.save(content);
        contents.add(content);
        Map<String, Object> body = Map.of("data", Map.of("type", "runs", "attributes", Map.of("is-destroy", destroy, "auto-apply", autoApply),
                "relationships", Map.of(
                        "workspace", Map.of("data", Map.of("type", "workspaces", "id", workspace.getId().toString())),
                        "configuration-version", Map.of("data", Map.of("type", "configuration-versions", "id", content.getId().toString())))));
        return given().auth().oauth2(token).contentType("application/vnd.api+json").body(body).post("/remote/tfe/v2/runs");
    }

    @Test
    void aTeamThatOnlyPlansCanQueueTheDestroyThatWaitsForAnApproval() {
        Workspace workspace = workspace("tfe-destroy-plan-role");
        String token = team("TFE_DESTROY_PLAN_ROLE", "plan", workspace);

        // The destroy template of the CLI plans, waits for an approval, then applies the destroy.
        assertEquals(201, run(token, workspace, true).statusCode());
        assertEquals(201, run(token, workspace, false).statusCode());
    }

    @Test
    void autoApprovingTakesTheApprovalAwayFromTheDestroy() {
        Workspace workspace = workspace("tfe-destroy-auto-approve");
        long before = jobRepository.count();

        // A team that cannot approve cannot auto approve anything.
        var planner = run(team("TFE_DESTROY_AUTO_PLAN", "plan", workspace), workspace, true, true);
        assertEquals(403, planner.statusCode(), planner.asString());
        assertTrue(planner.asString().contains("Auto approving a run requires permission to approve"), planner.asString());

        // A team that may approve but never destroy cannot skip the approval of a destroy either.
        Team approverOnly = new Team();
        approverOnly.setName("TFE_DESTROY_AUTO_APPROVER");
        approverOnly.setRole("custom");
        approverOnly.setPlanJob(true);
        approverOnly.setApproveJob(true);
        approverOnly.setDestroyJob(false);
        approverOnly.setOrganization(workspace.getOrganization());
        teams.add(teamRepository.save(approverOnly));
        var approver = run(generatePAT("TFE_DESTROY_AUTO_APPROVER"), workspace, true, true);
        assertEquals(403, approver.statusCode(), approver.asString());
        assertTrue(approver.asString().contains("cannot start a run that destroys"), approver.asString());

        assertEquals(before, jobRepository.count(), "a refused run must not create a job");
        assertEquals(201, run(team("TFE_DESTROY_AUTO_WRITE", "write", workspace), workspace, true, true).statusCode());
    }

    @Test
    void theCliIsToldItCannotDestroyWhenTheDestroyRightIsMissing() {
        Workspace workspace = workspace("tfe-destroy-reported");
        String writer = team("TFE_DESTROY_REPORTED_WRITE", "write", workspace);
        Team managerWithoutDestroy = new Team();
        managerWithoutDestroy.setName("TFE_DESTROY_REPORTED_NO_DESTROY");
        managerWithoutDestroy.setRole("custom");
        managerWithoutDestroy.setManageWorkspace(true);
        managerWithoutDestroy.setPlanJob(true);
        managerWithoutDestroy.setDestroyJob(false);
        managerWithoutDestroy.setOrganization(workspace.getOrganization());
        teams.add(teamRepository.save(managerWithoutDestroy));
        String orgName = workspace.getOrganization().getName();
        String path = "/remote/tfe/v2/organizations/" + orgName + "/workspaces/" + workspace.getName();

        given().auth().oauth2(writer).get(path).then().statusCode(200)
                .body("data.attributes.permissions.can-queue-destroy", org.hamcrest.Matchers.equalTo(true));
        given().auth().oauth2(generatePAT("TFE_DESTROY_REPORTED_NO_DESTROY")).get(path).then().statusCode(200)
                .body("data.attributes.permissions.can-queue-destroy", org.hamcrest.Matchers.equalTo(false));
    }

    @Test
    void theCliIsToldItCanDestroyWithThePlanAndDestroyRightsAloneLikeTheServerChecks() {
        Workspace workspace = workspace("tfe-destroy-reported-plan-destroy");
        Team planAndDestroy = new Team();
        planAndDestroy.setName("TFE_DESTROY_REPORTED_PLAN_DESTROY");
        planAndDestroy.setRole("custom");
        planAndDestroy.setPlanJob(true);
        planAndDestroy.setDestroyJob(true);
        planAndDestroy.setOrganization(workspace.getOrganization());
        teams.add(teamRepository.save(planAndDestroy));
        String path = "/remote/tfe/v2/organizations/" + workspace.getOrganization().getName() + "/workspaces/" + workspace.getName();

        // createRun accepts this team's destroy, so the permission it reports has to agree.
        given().auth().oauth2(generatePAT("TFE_DESTROY_REPORTED_PLAN_DESTROY")).get(path).then().statusCode(200)
                .body("data.attributes.permissions.can-queue-destroy", org.hamcrest.Matchers.equalTo(true));
        assertEquals(201, run(generatePAT("TFE_DESTROY_REPORTED_PLAN_DESTROY"), workspace, true, false).statusCode());
    }

    @Test
    void aWorkspaceThatAsksForApprovalAlsoCoversACliTemplateThatWasEditedToSkipIt() {
        Workspace workspace = workspace("tfe-apply-edited-template");
        workspace.setRequireApproveToApply(true);
        workspaceRepository.save(workspace);
        String planner = team("TFE_APPLY_EDITED_PLAN", "plan", workspace);
        String writer = team("TFE_APPLY_EDITED_WRITE", "write", workspace);
        io.terrakube.api.rs.template.Template template = templateRepository.getByOrganizationNameAndName(
                workspace.getOrganization().getName(), "Terraform-Plan/Apply-Cli");
        String original = template.getTcl();
        template.setTcl(java.util.Base64.getEncoder().encodeToString(
                "flow:\n- type: terraformPlan\n  step: 100\n- type: terraformApply\n  step: 200\n".getBytes()));
        templateRepository.save(template);
        try {
            var refused = run(planner, workspace, false);
            assertEquals(403, refused.statusCode(), refused.asString());
            assertTrue(refused.asString().contains("requires the permission to approve runs"), refused.asString());

            assertEquals(201, run(writer, workspace, false).statusCode());
        } finally {
            template.setTcl(original);
            templateRepository.save(template);
        }
    }

    @Test
    void aTeamWithTheWriteRoleCanDestroy() {
        Workspace workspace = workspace("tfe-destroy-write-role");
        String token = team("TFE_DESTROY_WRITE_ROLE", "write", workspace);

        assertEquals(201, run(token, workspace, true).statusCode());
    }

    @Test
    void aTeamThatMayApproveButNeverDestroyCannotConfirmARunThatDestroys() {
        Workspace workspace = workspace("tfe-destroy-confirm");
        String creator = team("TFE_DESTROY_CONFIRM_WRITE", "write", workspace);
        String runId = run(creator, workspace, true).jsonPath().getString("data.id");
        Team approverOnly = new Team();
        approverOnly.setName("TFE_DESTROY_CONFIRM_APPROVER");
        approverOnly.setRole("custom");
        approverOnly.setPlanJob(true);
        approverOnly.setApproveJob(true);
        approverOnly.setDestroyJob(false);
        approverOnly.setOrganization(workspace.getOrganization());
        teams.add(teamRepository.save(approverOnly));

        var refused = given().auth().oauth2(generatePAT("TFE_DESTROY_CONFIRM_APPROVER"))
                .contentType("application/vnd.api+json").body("{}")
                .post("/remote/tfe/v2/runs/" + runId + "/actions/apply");

        assertEquals(403, refused.statusCode(), refused.asString());
        assertTrue(refused.asString().contains("cannot approve a run that destroys"), refused.asString());
    }
}
