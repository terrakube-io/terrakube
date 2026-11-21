package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.policy.PolicyComplianceStatus;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

// The project summary roll-up is computed from workspace rows, so the tests seed workspaces directly and read the
// result back through the same Elide endpoints the UI uses.
class ProjectSummaryTests extends ServerApplicationTests {

    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String PROJECT_ONLY_TEAM = "HEALTH_PROJECT_TEAM";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Project> projects = new ArrayList<>();
    private Project busy;
    private Project quiet;
    private Project empty;

    @BeforeEach
    void seedProjects() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        busy = project("health-busy");
        quiet = project("health-quiet");
        empty = project("health-empty");

        workspace(busy, "b1", JobStatus.completed, PolicyComplianceStatus.COMPLIANT, false);
        workspace(busy, "b2", JobStatus.noChanges, PolicyComplianceStatus.UNKNOWN, false);
        workspace(busy, "b3", JobStatus.failed, PolicyComplianceStatus.COMPLIANT, false);
        workspace(busy, "b4", JobStatus.waitingApproval, PolicyComplianceStatus.NON_COMPLIANT, false);
        workspace(busy, "b5", JobStatus.running, PolicyComplianceStatus.COMPLIANT, true);
        workspace(busy, "b6", JobStatus.queue, PolicyComplianceStatus.COMPLIANT, false);
        workspace(busy, "b7", JobStatus.NeverExecuted, PolicyComplianceStatus.UNKNOWN, false);
        workspace(busy, "b8-deleted", JobStatus.failed, PolicyComplianceStatus.NON_COMPLIANT, false, true);

        workspace(quiet, "q1", JobStatus.completed, PolicyComplianceStatus.COMPLIANT, false);
        // A locked workspace is deliberate, so it is counted separately and does not need attention.
        workspace(quiet, "q2", JobStatus.completed, PolicyComplianceStatus.COMPLIANT, true);
    }

    @AfterEach
    void removeProjects() {
        // Workspaces are soft-deleted, so detach them before removing the projects they point at. Plain SQL avoids
        // optimistic locking clashes with background updates and with the detached entities this test mutates.
        for (Project project : projects) {
            String id = project.getId().toString();
            jdbcTemplate.update("update workspace set project_id = null, deleted = true where project_id = ?", id);
            jdbcTemplate.update("delete from project_access where project_id = ?", id);
            jdbcTemplate.update("delete from project where id = ?", id);
        }
    }

    @Test
    void summaryCountsCliWorkspacesOnLocalExecutionThatNeverRanApartFromOtherNeverRunOnes() {
        // b7 never ran. On local execution with no repository it never will, so it has no run status to show.
        jdbcTemplate.update("update workspace set execution_mode = 'local' where project_id = ? and name like 'health-b7-%'",
                busy.getId().toString());
        // A local workspace that did run (b1) is not counted, and neither is a remote one that never ran.
        jdbcTemplate.update("update workspace set execution_mode = 'local' where project_id = ? and name like 'health-b1-%'",
                busy.getId().toString());

        Map<String, Object> node = firstNode(summaries("name==health-busy", null, 10,
                "neverRunCount noRunStatusCount"));

        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) node.get("neverRunCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) node.get("noRunStatusCount")).intValue());

        // The same workspace on remote execution can still be run, so it is only never run.
        jdbcTemplate.update("update workspace set execution_mode = 'remote' where project_id = ? and name like 'health-b7-%'",
                busy.getId().toString());
        Map<String, Object> remote = firstNode(summaries("name==health-busy", null, 10,
                "neverRunCount noRunStatusCount"));
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) remote.get("neverRunCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(0, ((Number) remote.get("noRunStatusCount")).intValue());
    }

    @Test
    void summarySeparatesCliDrivenFromRepositoryDrivenWorkspaces() {
        // Every seeded workspace is CLI driven (no repository); give two of the live ones a repository.
        jdbcTemplate.update("update workspace set source = 'https://example.com/r.git', branch = 'main' "
                + "where project_id = ? and (name like 'health-b1-%' or name like 'health-b2-%')", busy.getId().toString());

        Map<String, Object> node = firstNode(summaries("name==health-busy", null, 10,
                "workspaceCount cliDrivenCount vcsDrivenCount"));

        org.junit.jupiter.api.Assertions.assertEquals(7, ((Number) node.get("workspaceCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(5, ((Number) node.get("cliDrivenCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(2, ((Number) node.get("vcsDrivenCount")).intValue());

        Map<String, Object> none = firstNode(summaries("name==health-empty", null, 10,
                "workspaceCount cliDrivenCount vcsDrivenCount"));
        org.junit.jupiter.api.Assertions.assertEquals(0, ((Number) none.get("cliDrivenCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(0, ((Number) none.get("vcsDrivenCount")).intValue());
    }

    @Test
    void summaryCountsWorkspacesByStatusAndIgnoresDeletedOnes() {
        Map<String, Object> node = firstNode(summaries("name==health-busy", null, 10,
                "workspaceCount completedCount failedCount runningCount queuedCount waitingApprovalCount "
                        + "neverRunCount nonCompliantCount lockedCount attentionCount teamCount lastRunAt"));

        org.junit.jupiter.api.Assertions.assertEquals(7, ((Number) node.get("workspaceCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(2, ((Number) node.get("completedCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) node.get("failedCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) node.get("runningCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) node.get("queuedCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) node.get("waitingApprovalCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) node.get("neverRunCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) node.get("nonCompliantCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) node.get("lockedCount")).intValue());
        // failed + waiting approval; the non-compliant workspace is the same one that waits for approval, and the
        // locked workspace is deliberate so it does not need attention.
        org.junit.jupiter.api.Assertions.assertEquals(2, ((Number) node.get("attentionCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(0, ((Number) node.get("teamCount")).intValue());
        org.junit.jupiter.api.Assertions.assertNotNull(node.get("lastRunAt"));
    }

    @Test
    void summaryReportsPolicyRollupAndHowLongTheOldestJobHasBeenQueued() {
        Workspace queuedWorkspace = workspace(quiet, "queued-job", JobStatus.pending, PolicyComplianceStatus.EXEMPTED, false);
        io.terrakube.api.rs.job.Job job = new io.terrakube.api.rs.job.Job();
        job.setStatus(JobStatus.pending);
        job.setOrganization(queuedWorkspace.getOrganization());
        job.setWorkspace(queuedWorkspace);
        job = jobRepository.saveAndFlush(job);
        try {
            Map<String, Object> quietNode = firstNode(summaries("name==health-quiet", null, 10, "compliantCount exemptedCount queuedSince queuedJobCount"));
            org.junit.jupiter.api.Assertions.assertEquals(2, ((Number) quietNode.get("compliantCount")).intValue());
            org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) quietNode.get("exemptedCount")).intValue());
            org.junit.jupiter.api.Assertions.assertNotNull(quietNode.get("queuedSince"));
            org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) quietNode.get("queuedJobCount")).intValue());

            Map<String, Object> busyNode = firstNode(summaries("name==health-busy", null, 10, "compliantCount queuedSince queuedJobCount"));
            org.junit.jupiter.api.Assertions.assertEquals(4, ((Number) busyNode.get("compliantCount")).intValue());
            org.junit.jupiter.api.Assertions.assertNull(busyNode.get("queuedSince"));
            org.junit.jupiter.api.Assertions.assertEquals(0, ((Number) busyNode.get("queuedJobCount")).intValue());
        } finally {
            jdbcTemplate.update("delete from job where id = ?", job.getId());
        }
    }

    @Test
    void policyRollupSeparatesHardFromSoftMandatoryViolations() {
        Project policy = project("health-policy");
        Workspace hard = workspace(policy, "hard", JobStatus.completed, PolicyComplianceStatus.NON_COMPLIANT, false);
        Workspace soft = workspace(policy, "soft", JobStatus.completed, PolicyComplianceStatus.NON_COMPLIANT, false);
        Workspace both = workspace(policy, "both", JobStatus.completed, PolicyComplianceStatus.NON_COMPLIANT, false);
        Workspace clean = workspace(policy, "clean", JobStatus.completed, PolicyComplianceStatus.COMPLIANT, false);
        // Legacy rows have no counts at all: they stay non-compliant but are neither hard nor soft.
        workspace(policy, "legacy", JobStatus.completed, PolicyComplianceStatus.NON_COMPLIANT, false);
        jdbcTemplate.update("update workspace set policy_hard_violations = 2, policy_soft_violations = 0 where id = ?", hard.getId().toString());
        jdbcTemplate.update("update workspace set policy_hard_violations = 0, policy_soft_violations = 1 where id = ?", soft.getId().toString());
        jdbcTemplate.update("update workspace set policy_hard_violations = 1, policy_soft_violations = 3 where id = ?", both.getId().toString());
        jdbcTemplate.update("update workspace set policy_hard_violations = 0, policy_soft_violations = 0 where id = ?", clean.getId().toString());

        Map<String, Object> node = firstNode(summaries("name==health-policy", null, 10,
                "nonCompliantCount hardViolationCount softViolationCount"));

        org.junit.jupiter.api.Assertions.assertEquals(4, ((Number) node.get("nonCompliantCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(2, ((Number) node.get("hardViolationCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, ((Number) node.get("softViolationCount")).intValue());
    }

    @Test
    void summaryCarriesTheTeamThatOwnsTheProject() {
        jdbcTemplate.update("update project set owner_team = ? where id = ?", "payments-devs", quiet.getId().toString());

        Map<String, Object> owned = firstNode(summaries("name==health-quiet", null, 10, "ownerTeam"));
        Map<String, Object> unowned = firstNode(summaries("name==health-empty", null, 10, "ownerTeam"));

        org.junit.jupiter.api.Assertions.assertEquals("payments-devs", owned.get("ownerTeam"));
        org.junit.jupiter.api.Assertions.assertNull(unowned.get("ownerTeam"));
    }

    @Test
    void projectWithoutWorkspacesReportsZeroes() {
        Map<String, Object> node = firstNode(summaries("name==health-empty", null, 10,
                "workspaceCount attentionCount lockedCount"));

        org.junit.jupiter.api.Assertions.assertEquals(0, ((Number) node.get("workspaceCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(0, ((Number) node.get("attentionCount")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(0, ((Number) node.get("lockedCount")).intValue());
    }

    @Test
    void summariesSortByAttentionAndFilterByWorkspaceCountWithAccurateTotals() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .contentType("application/json")
                .body(Map.of("query", summaryQuery("name=ini=*health-*", "-attentionCount,+name", 10, 0, "name")))
                .when()
                .post("/graphql/api/v1")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .body("$", not(hasKey("errors")))
                .rootPath("data.organization.edges[0].node.projectSummary")
                .body("edges.node.name", org.hamcrest.Matchers.contains("health-busy", "health-empty", "health-quiet"))
                .body("pageInfo.totalRecords", is(3));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .contentType("application/json")
                .body(Map.of("query", summaryQuery("name=ini=*health-*;workspaceCount==0", null, 10, 0, "name")))
                .when()
                .post("/graphql/api/v1")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .body("$", not(hasKey("errors")))
                .rootPath("data.organization.edges[0].node.projectSummary")
                .body("edges.node.name", org.hamcrest.Matchers.contains("health-empty"))
                .body("pageInfo.totalRecords", is(1));
    }

    @Test
    void pagingReturnsTheRequestedWindowAndTotal() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .contentType("application/json")
                .body(Map.of("query", summaryQuery("name=ini=*health-*", "+name", 1, 1, "name")))
                .when()
                .post("/graphql/api/v1")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .body("$", not(hasKey("errors")))
                .rootPath("data.organization.edges[0].node.projectSummary")
                .body("edges.node.name", org.hamcrest.Matchers.contains("health-empty"))
                .body("pageInfo.totalRecords", is(3))
                .body("pageInfo.hasNextPage", is(true));
    }

    @Test
    void projectAccessOnlyCallerSeesOnlyTheirProjectAndItsTeamCount() {
        ProjectAccess access = new ProjectAccess();
        access.setName(PROJECT_ONLY_TEAM);
        access.setRole("read");
        access.setProject(quiet);
        quiet.getProjectAccess().add(access);
        projectRepository.save(quiet);

        given()
                .headers("Authorization", "Bearer " + generatePAT(PROJECT_ONLY_TEAM))
                .contentType("application/json")
                .body(Map.of("query", summaryQuery("name=ini=*health-*", "+name", 10, 0, "name workspaceCount teamCount")))
                .when()
                .post("/graphql/api/v1")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .body("$", not(hasKey("errors")))
                .rootPath("data.organization.edges[0].node.projectSummary")
                .body("edges.size()", is(1))
                .body("edges[0].node.name", is("health-quiet"))
                .body("edges[0].node.workspaceCount", is(2))
                .body("edges[0].node.teamCount", is(1))
                .body("pageInfo.totalRecords", is(1));
    }

    @Test
    void callerWithoutAccessSeesNoSummaries() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("NOT_A_TEAM"))
                .contentType("application/json")
                .body(Map.of("query", summaryQuery("name=ini=*health-*", null, 10, 0, "name")))
                .when()
                .post("/graphql/api/v1")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                // The caller cannot even read the organization, so nothing about its projects is returned.
                .body(not(org.hamcrest.Matchers.containsString("health-")));
    }

    @Test
    void summaryCannotBeCreatedChangedOrDeletedThroughTheApi() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"project_summary\",\"attributes\":{\"name\":\"nope\"}}}")
                .when()
                .post("/api/v1/project_summary")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.BAD_REQUEST.value())));
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/project_summary/" + quiet.getId())
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(is(HttpStatus.FORBIDDEN.value()), is(HttpStatus.BAD_REQUEST.value())));
    }



    @Test
    void attentionFilterSentByTheProjectsPageMatchesOnlyProjectsThatNeedAttention() {
        io.restassured.response.ValidatableResponse response = summaries("name=ini=*health-*;attentionCount=gt=0", "+name", 10, "name");

        response.body("data.organization.edges[0].node.projectSummary.edges.size()", is(1))
                .body("data.organization.edges[0].node.projectSummary.edges[0].node.name", is("health-busy"));
    }

    @Test
    void attentionWorkspaceFilterSentByTheOverviewFindsFailedWaitingAndNonCompliantWorkspaces() {
        String filter = "project.id=='" + busy.getId() + "';(lastJobStatus=in=('failed','waitingApproval'),"
                + "policyComplianceStatus=='NON_COMPLIANT')";
        String query = "{ organization(ids: [\"" + ORG + "\"]) { edges { node { workspace(first: 5, after: \"0\", "
                + "sort: \"-lastJobDate\", filter: \"" + filter.replace("\"", "\\\"") + "\") { edges { node { name } } } } } } }";

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .contentType("application/json")
                .body(Map.of("query", query))
                .when()
                .post("/graphql/api/v1")
                .then()
                .log().ifValidationFails()
                .statusCode(HttpStatus.OK.value())
                .body("$", not(hasKey("errors")))
                .body("data.organization.edges[0].node.workspace.edges.size()", is(2));
    }

    private String summaryQuery(String filter, String sort, int first, int offset, String fields) {
        return "{ organization(ids: [\"" + ORG + "\"]) { edges { node { projectSummary(filter: \"" + filter + "\""
                + (sort == null ? "" : ", sort: \"" + sort + "\"") + ", first: " + first + ", after: \"" + offset + "\") { "
                + "edges { node { " + fields + " } } pageInfo { totalRecords hasNextPage } } } } } }";
    }

    private io.restassured.response.ValidatableResponse summaries(String filter, String sort, int first, String fields) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .contentType("application/json")
                .body(Map.of("query", summaryQuery(filter, sort, first, 0, fields)))
                .when()
                .post("/graphql/api/v1")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .statusCode(HttpStatus.OK.value())
                .body("$", not(hasKey("errors")));
    }

    private Map<String, Object> firstNode(io.restassured.response.ValidatableResponse response) {
        return response.extract().path("data.organization.edges[0].node.projectSummary.edges[0].node");
    }

    private Project project(String name) {
        Organization organization = organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();
        Project project = new Project();
        project.setName(name);
        project.setOrganization(organization);
        project = projectRepository.save(project);
        projects.add(project);
        return project;
    }

    private Workspace workspace(Project project, String name, JobStatus status, PolicyComplianceStatus policy, boolean locked) {
        return workspace(project, name, status, policy, locked, false);
    }

    private Workspace workspace(Project project, String name, JobStatus status, PolicyComplianceStatus policy,
                                boolean locked, boolean deleted) {
        Workspace workspace = new Workspace();
        workspace.setName("health-" + name + "-" + UUID.randomUUID());
        workspace.setSource("empty");
        workspace.setBranch("remote-content");
        workspace.setTerraformVersion("1.14.0");
        workspace.setOrganization(project.getOrganization());
        workspace.setProject(project);
        workspace.setLastJobStatus(status);
        workspace.setPolicyComplianceStatus(policy);
        workspace.setLocked(locked);
        workspace.setDeleted(deleted);
        workspace.setLastJobDate(new Date());
        workspace = workspaceRepository.save(workspace);
        return workspace;
    }
}
