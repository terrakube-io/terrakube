package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.ContentRepository;
import io.terrakube.api.repository.SshRepository;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.ssh.SshType;
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
 * The Terraform CLI path of the "restrict apply to the configured branch" setting. On a workspace that runs
 * uploaded content under a VCS or SSH connection, {@code terraform plan} (speculative) still works, while
 * {@code terraform apply} and {@code destroy} are refused.
 */
@TestPropertySource(properties = "spring.quartz.auto-startup=false")
class RemoteTfeBranchPolicyTests extends ServerApplicationTests {
    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @MockitoBean ScheduleJobService scheduleJobService;
    @Autowired ContentRepository contentRepository;
    @Autowired SshRepository sshRepository;

    private final List<Workspace> workspaces = new ArrayList<>();
    private final List<Ssh> keys = new ArrayList<>();
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
            current.setSsh(null);
            current.setDeleted(true);
            workspaceRepository.save(current);
        }
        sshRepository.deleteAll(keys);
        teamRepository.deleteAll(teams);
        workspaces.clear();
        keys.clear();
        contents.clear();
        teams.clear();
    }

    private Workspace workspace(String name, boolean restricted, boolean withConnection) {
        Workspace workspace = new Workspace();
        workspace.setName(name);
        workspace.setSource("empty");
        workspace.setBranch(withConnection ? "main" : "remote-content");
        workspace.setTerraformVersion("1.14.0");
        workspace.setRestrictApplyToConfiguredBranch(restricted);
        workspace.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        if (withConnection) {
            Ssh ssh = new Ssh();
            ssh.setName(name + "-ssh");
            ssh.setDescription("branch policy test");
            ssh.setPrivateKey("not-a-real-key");
            ssh.setSshType(SshType.rsa);
            ssh.setOrganization(workspace.getOrganization());
            Ssh saved = sshRepository.save(ssh);
            keys.add(saved);
            workspace.setSsh(saved);
        }
        Workspace saved = workspaceRepository.save(workspace);
        workspaces.add(saved);
        return saved;
    }

    private String admin(String name, Workspace workspace) {
        Team team = new Team();
        team.setName(name);
        team.setRole("admin");
        team.setOrganization(workspace.getOrganization());
        teams.add(teamRepository.save(team));
        return generatePAT(name);
    }

    private io.restassured.response.Response run(String token, Workspace workspace, boolean speculative, boolean destroy) {
        Content content = new Content();
        content.setWorkspace(workspace);
        content.setSpeculative(speculative);
        content = contentRepository.save(content);
        contents.add(content);
        Map<String, Object> body = Map.of("data", Map.of("type", "runs", "attributes", Map.of("is-destroy", destroy),
                "relationships", Map.of(
                        "workspace", Map.of("data", Map.of("type", "workspaces", "id", workspace.getId().toString())),
                        "configuration-version", Map.of("data", Map.of("type", "configuration-versions", "id", content.getId().toString())))));
        return given().auth().oauth2(token).contentType("application/vnd.api+json").body(body).post("/remote/tfe/v2/runs");
    }

    @Test
    void applyAndDestroyAreRefusedOnAConnectedWorkspaceThatRestrictsApply() {
        Workspace workspace = workspace("tfe-branch-policy-on", true, true);
        String token = admin("TFE_BRANCH_POLICY_ON", workspace);
        long before = jobRepository.count();

        var apply = run(token, workspace, false, false);
        var destroy = run(token, workspace, false, true);

        assertEquals(403, apply.statusCode(), apply.asString());
        assertEquals(403, destroy.statusCode(), destroy.asString());
        assertTrue(apply.asString().contains("can only run terraform plan"), apply.asString());
        assertTrue(apply.asString().contains("configured branch, main"), apply.asString());
        assertEquals(before, jobRepository.count(), "a refused run must not create a job");
    }

    @Test
    void aDestroyPlanIsRefusedBecauseItRunsTheDestroyTemplate() {
        // terraform plan -destroy runs Terraform-Plan/Destroy-Cli, which waits for an approval and then destroys.
        Workspace workspace = workspace("tfe-branch-policy-plan-destroy", true, true);
        String token = admin("TFE_BRANCH_POLICY_PLAN_DESTROY", workspace);

        assertEquals(403, run(token, workspace, true, true).statusCode());
    }

    @Test
    void aSpeculativePlanStillWorksOnAConnectedWorkspaceThatRestrictsApply() {
        Workspace workspace = workspace("tfe-branch-policy-plan", true, true);
        String token = admin("TFE_BRANCH_POLICY_PLAN", workspace);

        var plan = run(token, workspace, true, false);

        assertEquals(201, plan.statusCode(), plan.asString());
    }

    @Test
    void withTheSettingOffAConnectedWorkspaceKeepsItsBehaviour() {
        Workspace workspace = workspace("tfe-branch-policy-off", false, true);
        String token = admin("TFE_BRANCH_POLICY_OFF", workspace);

        assertEquals(201, run(token, workspace, false, false).statusCode());
        assertEquals(201, run(token, workspace, false, true).statusCode());
    }

    @Test
    void aWorkspaceWithoutAConnectionIsNotAffected() {
        Workspace workspace = workspace("tfe-branch-policy-cli-only", true, false);
        String token = admin("TFE_BRANCH_POLICY_CLI_ONLY", workspace);

        assertEquals(201, run(token, workspace, false, false).statusCode());
    }
}
