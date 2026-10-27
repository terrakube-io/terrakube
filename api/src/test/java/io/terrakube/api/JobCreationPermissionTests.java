package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import org.apache.calcite.avatica.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
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
 * Creating a job needs plan rights. Approving or rejecting an existing job needs approval rights.
 * The two must not imply each other: an identity that can only approve must not be able to queue a run.
 * Every case here goes through persisted teams and access bindings and the real Elide permission checks.
 */
class JobCreationPermissionTests extends ServerApplicationTests {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String TEMPLATE_REF = "2db36f7c-f549-4341-a789-315d47eb061d";
    private static final String OUTSIDER = "AUTHZ_NOT_A_MEMBER_OF_ANY_TEAM";

    private Organization organization;
    private Workspace workspace;
    private Workspace otherWorkspace;
    private final List<Team> teams = new ArrayList<>();
    private final List<Project> projects = new ArrayList<>();

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        wireMockServer.resetAll();
        stubFor(post(urlPathEqualTo("/api/v1/terraform-rs"))
                .willReturn(aResponse().withStatus(HttpStatus.ACCEPTED.value()).withBody("")));

        organization = organizationRepository.getReferenceById(UUID.fromString(ORG_ID));
        workspace = newWorkspace();
        otherWorkspace = newWorkspace();
    }

    @AfterEach
    void cleanup() {
        // Creating a job updates the workspace row, so reload it instead of saving the stale copy.
        for (Workspace item : List.of(workspace, otherWorkspace)) {
            Workspace current = workspaceRepository.findById(item.getId()).orElseThrow();
            current.setDeleted(true);
            current.setProject(null);
            workspaceRepository.save(current);
        }
        for (Project project : projects) {
            projectRepository.delete(project);
        }
        projects.clear();
        teamRepository.deleteAll(teams);
        teams.clear();
    }

    // Organization scope

    @Test
    void approvalOnlyOrganizationTeamCannotCreateAJob() {
        String name = orgTeam("custom", false, true).getName();

        assertCreateRefused(name, workspace);
    }

    @Test
    void approvalOnlyOrganizationTeamCannotCreateAJobEvenWithTheLegacyManageFlag() {
        Team team = orgTeam("custom", false, true);
        team.setManageJob(true);
        teamRepository.save(team);

        assertCreateRefused(team.getName(), workspace);
    }

    @Test
    void planEnabledOrganizationTeamCanCreateAJob() {
        String name = orgTeam("custom", true, false).getName();

        assertEquals(HttpStatus.CREATED.value(), createJob(name, workspace));
    }

    @ParameterizedTest
    @CsvSource({"admin,201", "write,201", "plan,201", "read,403"})
    void builtInRolesKeepTheirCreateBehaviour(String role, int expected) {
        String name = orgTeam(role, false, false).getName();

        if (expected == HttpStatus.CREATED.value()) {
            assertEquals(expected, createJob(name, workspace));
        } else {
            assertCreateRefused(name, workspace);
        }
    }

    @Test
    void anIdentityOutsideEveryTeamCannotCreateAJob() {
        assertCreateRefused(OUTSIDER, workspace);
    }

    // Workspace scope

    @Test
    void approvalOnlyWorkspaceBindingCannotCreateAJob() {
        String name = UUID.randomUUID().toString();
        orgTeam(name, "read", false, false);
        bindToWorkspace(workspace, name, false, true);

        assertCreateRefused(name, workspace);
    }

    @Test
    void planEnabledWorkspaceBindingCanCreateAJobOnlyInThatWorkspace() {
        String name = UUID.randomUUID().toString();
        orgTeam(name, "read", false, false);
        bindToWorkspace(workspace, name, true, false);

        assertEquals(HttpStatus.CREATED.value(), createJob(name, workspace));
        assertCreateRefused(name, otherWorkspace);
    }

    @Test
    void approvalOnlyWorkspaceBindingGainsNothingInAnotherWorkspace() {
        String name = UUID.randomUUID().toString();
        orgTeam(name, "read", false, false);
        bindToWorkspace(workspace, name, false, true);

        assertCreateRefused(name, otherWorkspace);
    }

    @Test
    void approvalOnlyWorkspaceBindingCannotChangeTheWorkspace() {
        String name = UUID.randomUUID().toString();
        orgTeam(name, "read", false, false);
        bindToWorkspace(workspace, name, false, true);

        given()
                .headers("Authorization", "Bearer " + generatePAT(name), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspace\",\"id\":\"" + workspace.getId()
                        + "\",\"attributes\":{\"description\":\"changed\"}}}")
                .patch("/api/v1/organization/" + ORG_ID + "/workspace/" + workspace.getId())
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }

    // Project scope

    @Test
    void approvalOnlyProjectBindingCannotCreateAJob() {
        String name = UUID.randomUUID().toString();
        orgTeam(name, "read", false, false);
        bindToProject(workspace, name, "custom", false, true);

        assertCreateRefused(name, workspace);
    }

    @Test
    void planEnabledProjectBindingCanCreateAJob() {
        String name = UUID.randomUUID().toString();
        orgTeam(name, "read", false, false);
        bindToProject(workspace, name, "custom", true, false);

        assertEquals(HttpStatus.CREATED.value(), createJob(name, workspace));
    }

    // Approval and rejection of an existing, held job

    @Test
    void anApprovalOnlyTeamCanApproveAndRejectButNotCreate() throws InterruptedException {
        String approver = orgTeam("custom", false, true).getName();
        Template template = approvalTemplate(approver);

        int created = createJob(approver, workspace, template.getId().toString());
        assertEquals(HttpStatus.FORBIDDEN.value(), created);

        String planner = orgTeam("custom", true, false).getName();
        // A workspace runs one job at a time, so each held job gets its own workspace.
        String approvedJob = createdJobId(planner, workspace, template);
        String rejectedJob = createdJobId(planner, otherWorkspace, template);

        assertEquals(HttpStatus.NO_CONTENT.value(), setStatus(approver, approvedJob, JobStatus.approved));
        assertEquals(HttpStatus.NO_CONTENT.value(), setStatus(approver, rejectedJob, JobStatus.rejected));
        assertEquals("test@terrakube.io", jobRepository.findById(Integer.parseInt(approvedJob)).orElseThrow().getApprovedBy());
    }

    @Test
    void aPlanOnlyTeamCannotApproveOrReject() throws InterruptedException {
        String planner = orgTeam("custom", true, false).getName();
        String approver = orgTeam("custom", false, true).getName();
        Template template = approvalTemplate(approver);
        String jobId = createdJobId(planner, workspace, template);

        assertEquals(HttpStatus.FORBIDDEN.value(), setStatus(planner, jobId, JobStatus.approved));
        assertEquals(HttpStatus.FORBIDDEN.value(), setStatus(planner, jobId, JobStatus.rejected));
        assertEquals(JobStatus.waitingApproval, jobRepository.findById(Integer.parseInt(jobId)).orElseThrow().getStatus());
    }

    @Test
    void onlyTheNamedApprovalTeamCanApprove() throws InterruptedException {
        String planner = orgTeam("custom", true, false).getName();
        String named = orgTeam("custom", false, true).getName();
        Template template = approvalTemplate(named);
        String jobId = createdJobId(planner, workspace, template);

        assertTrue(setStatus(OUTSIDER, jobId, JobStatus.approved) >= HttpStatus.BAD_REQUEST.value());
        assertEquals(JobStatus.waitingApproval, jobRepository.findById(Integer.parseInt(jobId)).orElseThrow().getStatus());

        assertEquals(HttpStatus.NO_CONTENT.value(), setStatus(named, jobId, JobStatus.approved));
    }

    // Helpers

    private Team orgTeam(String role, boolean plan, boolean approve) {
        return orgTeam("AUTHZ_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), role, plan, approve);
    }

    // Every permission attribute is set explicitly, so only the ones under test are enabled.
    private Team orgTeam(String name, String role, boolean plan, boolean approve) {
        Team team = new Team();
        team.setName(name);
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
        team = teamRepository.save(team);
        teams.add(team);
        return team;
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setName(UUID.randomUUID().toString());
        created.setSource("https://github.com/AzBuilder/terrakube-docker-compose.git");
        created.setBranch("main");
        created.setTerraformVersion("1.2.5");
        created.setOrganization(organization);
        return workspaceRepository.save(created);
    }

    private void bindToWorkspace(Workspace target, String team, boolean plan, boolean approve) {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"access\",\"attributes\":{\"name\":\"" + team + "\",\"role\":\"custom\","
                        + "\"manageWorkspace\":false,\"manageJob\":false,\"manageState\":false,"
                        + "\"planJob\":" + plan + ",\"approveJob\":" + approve + "}}}")
                .post("/api/v1/organization/" + ORG_ID + "/workspace/" + target.getId() + "/access")
                .then().statusCode(HttpStatus.CREATED.value());
    }

    private void bindToProject(Workspace target, String team, String role, boolean plan, boolean approve) {
        Project project = new Project();
        project.setName("authz-" + UUID.randomUUID());
        project.setDescription("job creation permission test");
        project.setOrganization(organization);
        ProjectAccess access = new ProjectAccess();
        access.setName(team);
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

    private Template approvalTemplate(String approvalTeam) {
        Template template = new Template();
        template.setName("authz-approval");
        template.setTcl(Base64.encodeBytes(
                ("flow:\n- name: Approve\n  type: approval\n  team: " + approvalTeam + "\n").getBytes()));
        template.setOrganization(organization);
        return templateRepository.save(template);
    }

    private String createdJobId(String creator, Workspace target, Template template) throws InterruptedException {
        String jobId = given()
                .headers("Authorization", "Bearer " + generatePAT(creator), "Content-Type", "application/vnd.api+json")
                .body(jobBody(target, template.getId().toString()))
                .post("/api/v1/organization/" + ORG_ID + "/job")
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("data.id");
        awaitStatus(jobId, JobStatus.waitingApproval);
        return jobId;
    }

    private void awaitStatus(String jobId, JobStatus status) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (jobRepository.findById(Integer.parseInt(jobId)).map(Job::getStatus).filter(status::equals).isPresent()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job " + jobId + " did not reach " + status);
    }

    private int setStatus(String identity, String jobId, JobStatus status) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(identity), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"job\",\"id\":\"" + jobId + "\",\"attributes\":{\"status\":\"" + status + "\"}}}")
                .patch("/api/v1/organization/" + ORG_ID + "/job/" + jobId)
                .then().extract().statusCode();
    }

    private int createJob(String identity, Workspace target) {
        return createJob(identity, target, TEMPLATE_REF);
    }

    private int createJob(String identity, Workspace target, String templateRef) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(identity), "Content-Type", "application/vnd.api+json")
                .body(jobBody(target, templateRef))
                .post("/api/v1/organization/" + ORG_ID + "/job")
                .then().extract().statusCode();
    }

    // A refused request must leave no job behind, so nothing can be queued for execution.
    private void assertCreateRefused(String identity, Workspace target) {
        long before = jobRepository.count();

        assertEquals(HttpStatus.FORBIDDEN.value(), createJob(identity, target));

        assertEquals(before, jobRepository.count());
    }

    private String jobBody(Workspace target, String templateRef) {
        return "{\"data\":{\"type\":\"job\",\"attributes\":{\"templateReference\":\"" + templateRef + "\"},"
                + "\"relationships\":{\"workspace\":{\"data\":{\"type\":\"workspace\",\"id\":\"" + target.getId() + "\"}}}}}";
    }
}
