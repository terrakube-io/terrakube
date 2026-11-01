package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Starting a run that destroys is a separate right from planning. A team that can plan can still be kept from
 * destroying, and the right can come from the organization team, the workspace access or the project access.
 * Every case goes through persisted teams and the real Elide checks and hooks.
 */
class JobRunRightsTests extends ServerApplicationTests {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String PLAN = "42201234-a5e2-4c62-b2fc-9729ca6b4515";
    private static final String PLAN_AND_APPLY = "2db36f7c-f549-4341-a789-315d47eb061d";
    private static final String DESTROY = "d944e19d-bc3e-41b0-bdf9-1881e1a459c4";

    private Organization organization;
    private Workspace workspace;
    private final List<Team> teams = new ArrayList<>();
    private final List<Project> projects = new ArrayList<>();
    private final List<io.terrakube.api.rs.template.Template> templates = new ArrayList<>();

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        wireMockServer.resetAll();
        stubFor(post(urlPathEqualTo("/api/v1/terraform-rs"))
                .willReturn(aResponse().withStatus(HttpStatus.ACCEPTED.value()).withBody("")));

        organization = organizationRepository.getReferenceById(UUID.fromString(ORG_ID));
        Workspace created = new Workspace();
        created.setName(UUID.randomUUID().toString());
        created.setSource("https://github.com/AzBuilder/terrakube-docker-compose.git");
        created.setBranch("main");
        created.setTerraformVersion("1.2.5");
        created.setOrganization(organization);
        workspace = workspaceRepository.save(created);
    }

    @AfterEach
    void cleanup() {
        Workspace current = workspaceRepository.findById(workspace.getId()).orElseThrow();
        current.setDeleted(true);
        current.setProject(null);
        workspaceRepository.save(current);
        projects.forEach(projectRepository::delete);
        projects.clear();
        teamRepository.deleteAll(teams);
        teams.clear();
        jobRepository.findAll().stream()
                .filter(job -> job.getWorkspace() != null && workspace.getId().equals(job.getWorkspace().getId()))
                .forEach(job -> {
                    stepRepository.deleteAll(stepRepository.findByJobId(job.getId()));
                    jobRepository.delete(job);
                });
        templateRepository.deleteAll(templates);
        templates.clear();
    }

    @Test
    void theRightIsOnByDefaultSoAnOldClientKeepsItsBehaviour() {
        assertTrue(new Team().isDestroyJob());
    }

    @ParameterizedTest
    @CsvSource({"admin,201", "write,201", "plan,403"})
    void aBuiltInRoleDecidesWhetherATeamCanDestroyWithoutAnApproval(String role, int expected) {
        String name = orgTeam(role, true, true).getName();

        assertEquals(expected, createJob(name, DESTROY).statusCode());
    }

    @Test
    void aTeamThatOnlyPlansCanQueueADestroyThatWaitsForAnApproval() {
        String name = orgTeam("plan", true, false).getName();
        String gated = destroyAfterApproval();

        assertEquals(HttpStatus.CREATED.value(), createJob(name, gated).statusCode());
    }

    // The setting that asks for the right to approve before an apply that nobody approves

    @Test
    void withTheSettingOffATeamThatOnlyPlansCanStartAnApplyWithNoApprovalStep() {
        String name = orgTeam("plan", true, false).getName();

        assertEquals(HttpStatus.CREATED.value(), createJob(name, PLAN_AND_APPLY).statusCode());
    }

    @Test
    void withTheSettingOnATeamThatOnlyPlansCannotStartAnApplyWithNoApprovalStep() {
        requireApprove(true);
        String name = orgTeam("plan", true, false).getName();
        long before = jobRepository.count();

        var refused = createJob(name, PLAN_AND_APPLY);

        assertEquals(HttpStatus.FORBIDDEN.value(), refused.statusCode(), refused.asString());
        assertTrue(refused.asString().contains("requires the permission to approve runs"), refused.asString());
        assertEquals(before, jobRepository.count(), "a refused job must not be saved");
    }

    @Test
    void withTheSettingOnATeamThatOnlyPlansCanStillQueueRunsThatWaitForAnApprovalOrOnlyPlan() {
        requireApprove(true);
        String name = orgTeam("plan", true, false).getName();

        assertEquals(HttpStatus.CREATED.value(), createJob(name, destroyAfterApproval()).statusCode());
        assertEquals(HttpStatus.CREATED.value(), createJob(name, PLAN).statusCode());
    }

    @Test
    void withTheSettingOnATeamThatMayApproveCanStartAnApplyWithNoApprovalStep() {
        requireApprove(true);

        assertEquals(HttpStatus.CREATED.value(), createJob(orgTeam("write", true, true).getName(), PLAN_AND_APPLY).statusCode());
        assertEquals(HttpStatus.CREATED.value(), createJob(orgTeam("custom", true, true).getName(), PLAN_AND_APPLY).statusCode());
    }

    @Test
    void withTheSettingOnTheWorkspaceAccessCanGrantTheRightToApprove() {
        requireApprove(true);
        String name = orgTeam("plan", true, false).getName();
        bindToWorkspaceApprove(name);

        assertEquals(HttpStatus.CREATED.value(), createJob(name, PLAN_AND_APPLY).statusCode());
    }

    @Test
    void aCustomTeamWithoutTheRightCanPlanAndApplyButNotDestroy() {
        Team team = orgTeam("custom", true, true);
        team.setDestroyJob(false);
        teamRepository.save(team);

        assertEquals(HttpStatus.CREATED.value(), createJob(team.getName(), PLAN).statusCode());
        assertEquals(HttpStatus.CREATED.value(), createJob(team.getName(), PLAN_AND_APPLY).statusCode());
        var refused = createJob(team.getName(), DESTROY);
        assertEquals(HttpStatus.FORBIDDEN.value(), refused.statusCode(), refused.asString());
        assertTrue(refused.asString().contains("cannot start a run that destroys"), refused.asString());
    }

    @Test
    void aCustomTeamWithTheRightCanDestroy() {
        Team team = orgTeam("custom", true, false);
        team.setDestroyJob(true);
        teamRepository.save(team);

        assertEquals(HttpStatus.CREATED.value(), createJob(team.getName(), DESTROY).statusCode());
    }

    @Test
    void aRefusedJobIsNotSaved() {
        String name = orgTeam("plan", true, false).getName();
        long before = jobRepository.count();

        assertEquals(HttpStatus.FORBIDDEN.value(), createJob(name, DESTROY).statusCode());

        assertEquals(before, jobRepository.count());
    }

    @Test
    void theWorkspaceAccessCanGrantTheRightToATeamThatOnlyPlans() {
        String name = orgTeam("plan", true, false).getName();
        assertEquals(HttpStatus.FORBIDDEN.value(), createJob(name, DESTROY).statusCode());

        bindToWorkspace(name, true);

        assertEquals(HttpStatus.CREATED.value(), createJob(name, DESTROY).statusCode());
    }

    @Test
    void aWorkspaceAccessWithoutTheRightDoesNotTakeItAwayFromAnotherGrant() {
        String name = orgTeam("write", true, true).getName();
        bindToWorkspace(name, false);

        assertEquals(HttpStatus.CREATED.value(), createJob(name, DESTROY).statusCode());
    }

    @Test
    void theProjectAccessCanGrantTheRight() {
        String name = orgTeam("plan", true, false).getName();
        bindToProject(name, true);

        assertEquals(HttpStatus.CREATED.value(), createJob(name, DESTROY).statusCode());
    }

    @Test
    void aProjectAccessWithoutTheRightGrantsNothing() {
        String name = orgTeam("plan", true, false).getName();
        bindToProject(name, false);

        assertEquals(HttpStatus.FORBIDDEN.value(), createJob(name, DESTROY).statusCode());
    }

    private Team orgTeam(String role, boolean plan, boolean approve) {
        Team team = new Team();
        team.setName("DESTROY_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        team.setOrganization(organization);
        team.setRole(role);
        team.setPlanJob(plan);
        team.setApproveJob(approve);
        team.setManageJob(false);
        team.setManageWorkspace(false);
        team.setManageState(false);
        team.setManageCollection(false);
        team.setManageModule(false);
        team.setManageProvider(false);
        team.setManageVcs(false);
        team.setManageTemplate(false);
        team.setManagePolicies(false);
        team.setDestroyJob(false);
        team = teamRepository.save(team);
        teams.add(team);
        return team;
    }

    private void bindToWorkspace(String team, boolean destroy) {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"access\",\"attributes\":{\"name\":\"" + team + "\",\"role\":\"custom\","
                        + "\"manageWorkspace\":false,\"manageJob\":false,\"manageState\":false,"
                        + "\"planJob\":true,\"approveJob\":false,\"destroyJob\":" + destroy + "}}}")
                .post("/api/v1/organization/" + ORG_ID + "/workspace/" + workspace.getId() + "/access")
                .then().statusCode(HttpStatus.CREATED.value());
    }

    private void requireApprove(boolean value) {
        Workspace current = workspaceRepository.findById(workspace.getId()).orElseThrow();
        current.setRequireApproveToApply(value);
        workspaceRepository.save(current);
    }

    private void bindToWorkspaceApprove(String team) {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"access\",\"attributes\":{\"name\":\"" + team + "\",\"role\":\"custom\","
                        + "\"manageWorkspace\":false,\"manageJob\":false,\"manageState\":false,"
                        + "\"planJob\":true,\"approveJob\":true,\"destroyJob\":false}}}")
                .post("/api/v1/organization/" + ORG_ID + "/workspace/" + workspace.getId() + "/access")
                .then().statusCode(HttpStatus.CREATED.value());
    }

    private void bindToProject(String team, boolean destroy) {
        Project project = new Project();
        project.setName("destroy-" + UUID.randomUUID());
        project.setDescription("destroy permission test");
        project.setOrganization(organization);
        ProjectAccess access = new ProjectAccess();
        access.setName(team);
        access.setRole("custom");
        access.setPlanJob(true);
        access.setApproveJob(false);
        access.setDestroyJob(destroy);
        access.setProject(project);
        project.getProjectAccess().add(access);
        project = projectRepository.save(project);
        projects.add(project);
        Workspace current = workspaceRepository.findById(workspace.getId()).orElseThrow();
        current.setProject(project);
        workspaceRepository.save(current);
    }

    // Plan the destroy, wait for an approval, then apply it: the shape of the Terraform CLI destroy template.
    private String destroyAfterApproval() {
        io.terrakube.api.rs.template.Template template = new io.terrakube.api.rs.template.Template();
        template.setName("destroy-after-approval");
        template.setTcl(java.util.Base64.getEncoder().encodeToString(
                ("flow:\n- type: terraformPlanDestroy\n  step: 100\n- type: approval\n  step: 150\n  team: APPROVERS\n"
                        + "- type: terraformApply\n  step: 200\n").getBytes()));
        template.setOrganization(organization);
        template = templateRepository.save(template);
        templates.add(template);
        return template.getId().toString();
    }

    private io.restassured.response.Response createJob(String identity, String templateRef) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(identity), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"job\",\"attributes\":{\"templateReference\":\"" + templateRef + "\"},"
                        + "\"relationships\":{\"workspace\":{\"data\":{\"type\":\"workspace\",\"id\":\"" + workspace.getId() + "\"}}}}}")
                .post("/api/v1/organization/" + ORG_ID + "/job");
    }
}
