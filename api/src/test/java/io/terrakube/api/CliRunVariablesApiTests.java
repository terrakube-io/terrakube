package io.terrakube.api;

import io.terrakube.api.plugin.execution.JobVariablesService;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.ContentRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.content.Content;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** Variables sent by the Terraform CLI (-var, -var-file, TF_VAR_) when it creates a run. */
@TestPropertySource(properties = "spring.quartz.auto-startup=false")
class CliRunVariablesApiTests extends ServerApplicationTests {
    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    @MockitoBean ScheduleJobService scheduleJobService;
    @Autowired ContentRepository contentRepository;
    @Autowired JobVariablesService variablesService;

    private Workspace workspace(String name) {
        Workspace workspace = new Workspace();
        workspace.setName(name);
        workspace.setSource("empty");
        workspace.setBranch("remote-content");
        workspace.setTerraformVersion("1.14.0");
        workspace.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        return workspaceRepository.save(workspace);
    }

    private void team(String name, String role, Workspace workspace) {
        Team team = new Team();
        team.setName(name);
        team.setRole(role);
        team.setOrganization(workspace.getOrganization());
        teamRepository.save(team);
    }

    private Map<String, Object> run(Workspace workspace, Object variables) {
        Content content = new Content();
        content.setWorkspace(workspace);
        content = contentRepository.save(content);
        Map<String, Object> attributes = variables == null ? Map.of("is-destroy", false) : Map.of("is-destroy", false, "variables", variables);
        return Map.of("data", Map.of("type", "runs", "attributes", attributes,
                "relationships", Map.of(
                        "workspace", Map.of("data", Map.of("type", "workspaces", "id", workspace.getId().toString())),
                        "configuration-version", Map.of("data", Map.of("type", "configuration-versions", "id", content.getId().toString())))));
    }

    private int jobId(io.restassured.response.Response response) {
        return Integer.parseInt(response.<String>path("data.id").replace("run-", ""));
    }

    private io.restassured.response.Response post(String token, Map<String, Object> body) {
        return given().auth().oauth2(token).contentType("application/vnd.api+json").body(body).post("/remote/tfe/v2/runs");
    }

    @Test
    void cliInputsAreStoredEncryptedPerRunAndLeaveTheWorkspaceUnchanged() {
        Workspace workspace = workspace("cli-run-variables");
        team("CLI_RUN_VARIABLES_ADMIN", "admin", workspace);
        String token = generatePAT("CLI_RUN_VARIABLES_ADMIN");
        long variablesBefore = variableRepository.count();

        var first = post(token, run(workspace, List.of(
                Map.of("key", "example", "value", "\"private-from-file\""),
                Map.of("key", "count", "value", "5"),
                Map.of("key", "names", "value", "[\"a\", \"b\"]"),
                Map.of("key", "unset", "value", "null"))));
        var second = post(token, run(workspace, List.of(Map.of("key", "example", "value", "\"private-other-run\""))));
        assertEquals(201, first.statusCode(), first.asString());
        assertEquals(201, second.statusCode(), second.asString());
        assertFalse(first.asString().contains("private-from-file"));

        Job one = jobRepository.findById(jobId(first)).orElseThrow();
        Job two = jobRepository.findById(jobId(second)).orElseThrow();
        assertTrue(one.isVariablesAsArguments());
        assertEquals(Map.of("example", "\"private-from-file\"", "count", "5", "names", "[\"a\", \"b\"]"),
                variablesService.decrypt(one.getEncryptedVariables()));
        assertEquals(List.of("count", "example", "names"), one.getVariableNames());
        assertEquals(Map.of("example", "\"private-other-run\""), variablesService.decrypt(two.getEncryptedVariables()));
        assertFalse(one.getEncryptedVariables().contains("private-from-file"));
        assertEquals(variablesBefore, variableRepository.count());
    }

    @Test
    void runsWithoutInputsKeepTheirJobsFree() {
        Workspace workspace = workspace("cli-run-without-variables");
        team("CLI_RUN_WITHOUT_VARIABLES", "admin", workspace);
        String token = generatePAT("CLI_RUN_WITHOUT_VARIABLES");
        for (Object variables : new Object[]{null, List.of(), List.of(Map.of("key", "unset", "value", "null"))}) {
            var response = post(token, run(workspace, variables));
            assertEquals(201, response.statusCode(), response.asString());
            Job job = jobRepository.findById(jobId(response)).orElseThrow();
            assertNull(job.getEncryptedVariables());
            assertNull(job.getVariableNames());
            assertFalse(job.isVariablesAsArguments());
        }
    }

    @Test
    void teamThatCannotManageTheWorkspaceCanRunButNotSupplyInputs() {
        Workspace workspace = workspace("cli-run-variables-plan-only");
        team("CLI_RUN_VARIABLES_PLAN_ONLY", "plan", workspace);
        String token = generatePAT("CLI_RUN_VARIABLES_PLAN_ONLY");
        long before = jobRepository.count();
        var denied = post(token, run(workspace, List.of(Map.of("key", "example", "value", "\"private-denied\""))));
        assertEquals(403, denied.statusCode(), denied.asString());
        assertTrue(denied.asString().contains("example"));
        assertFalse(denied.asString().contains("private-denied"));
        assertEquals(before, jobRepository.count());
        assertEquals(201, post(token, run(workspace, null)).statusCode());
    }

    @Test
    void malformedInputsAreRejectedWithoutCreatingARun() {
        Workspace workspace = workspace("cli-run-variables-invalid");
        team("CLI_RUN_VARIABLES_INVALID", "admin", workspace);
        String token = generatePAT("CLI_RUN_VARIABLES_INVALID");
        long before = jobRepository.count();
        for (Object bad : new Object[]{"text", List.of(Map.of("key", "bad name", "value", "1")),
                List.of(Map.of("key", "example", "value", "\"private-marker")), List.of(Map.of("key", "example")),
                List.of(Map.of("key", "example", "value", "[\"private-marker\", ")), List.of(Map.of("key", "example", "value", "var.private-marker"))}) {
            var rejected = post(token, run(workspace, bad));
            assertEquals(400, rejected.statusCode(), rejected.asString());
            assertFalse(rejected.asString().contains("private-marker"));
        }
        assertEquals(before, jobRepository.count());
    }
}
