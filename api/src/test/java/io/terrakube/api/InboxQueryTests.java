package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.restassured.response.ValidatableResponse;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

// The queries of the UI's inbox: a page of one kind with an offset, the exact total, and a name search. Elide checks
// the declared variable types and the filter and sort strings itself, so a mocked client cannot tell whether they are
// valid: this runs the same shapes through the real endpoint, where a wrong one comes back as a GraphQL error.
class InboxQueryTests extends ServerApplicationTests {

    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String PREFIX = "inbox-gql-";

    private static final String PAGE_QUERY = """
            query ($orgId: String!, $first: StringOrInt, $after: StringOrInt, $sort: String, $filter: String) {
              organization(ids: [$orgId]) { edges { node {
                workspace(first: $first, after: $after, sort: $sort, filter: $filter) {
                  edges { node { id name lastJobDate driftFirstSeen project { edges { node { name ownerTeam } } } } }
                  pageInfo { totalRecords }
                }
              } } }
            }""";

    private static final String COUNT_QUERY = """
            query ($orgId: String!, $failedFilter: String, $waitingFilter: String) {
              organization(ids: [$orgId]) { edges { node {
                failed: workspace(first: "1", after: "0", filter: $failedFilter) { pageInfo { totalRecords } }
                waiting: workspace(first: "1", after: "0", filter: $waitingFilter) { pageInfo { totalRecords } }
              } } }
            }""";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Names are unique per organization, soft-deleted workspaces included, so every run uses its own.
    private String run;
    private final List<Workspace> workspaces = new java.util.ArrayList<>();

    @BeforeEach
    void seed() {
        run = UUID.randomUUID().toString().substring(0, 8);
        workspaces.clear();
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Organization organization = organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();
        // Four failed workspaces: failed-3 ran last, so it is first when sorted newest first, and one waiting for approval.
        for (int index = 0; index < 4; index++) {
            workspaces.add(workspace(organization, "failed-" + index, JobStatus.failed, 10 - index));
        }
        workspaces.add(workspace(organization, "waiting-0", JobStatus.waitingApproval, 5));
    }

    @AfterEach
    void cleanUp() {
        for (Workspace workspace : workspaces) {
            jdbcTemplate.update("update workspace set deleted = true, project_id = null where id = ?", workspace.getId().toString());
        }
        if (project != null) {
            jdbcTemplate.update("delete from project where id = ?", project.getId().toString());
            project = null;
        }
    }

    @Test
    void readsTheSecondPageOfAKindWithTheExactTotal() {
        page("-lastJobDate,+name", "lastJobStatus=='failed';name=ini=*" + PREFIX + "*", "2", "2")
                .body("errors", nullValue())
                .body("data.organization.edges[0].node.workspace.pageInfo.totalRecords", is(4))
                .body("data.organization.edges[0].node.workspace.edges", hasSize(2))
                .body("data.organization.edges[0].node.workspace.edges.node.name",
                        contains(PREFIX + run + "-failed-1", PREFIX + run + "-failed-0"));
    }

    @Test
    void narrowsToNamesContainingTheSearchText() {
        page("+lastJobDate,+name", "lastJobStatus=='failed';name=ini=*" + run + "-failed-1*", "0", "25")
                .body("errors", nullValue())
                .body("data.organization.edges[0].node.workspace.pageInfo.totalRecords", is(1));
    }

    @Test
    void countsEachKindWithoutRows() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/json")
                .body(Map.of("query", COUNT_QUERY, "variables", Map.of(
                        "orgId", ORG,
                        "failedFilter", "lastJobStatus=='failed';name=ini=*" + PREFIX + "*",
                        "waitingFilter", "lastJobStatus=='waitingApproval';name=ini=*" + PREFIX + "*")))
                .post("/graphql/api/v1")
                .then()
                .body("errors", nullValue())
                .body("data.organization.edges[0].node.failed.pageInfo.totalRecords", is(4))
                .body("data.organization.edges[0].node.waiting.pageInfo.totalRecords", is(1));
    }

    // The filters the UI sends for each kind, as the service builds them, narrowed to the teams that own the project.
    private static final String DRIFTED = "driftStatus=='drifted';lastJobStatus!='failed';lastJobStatus!='waitingApproval'";
    private static final String POLICY = "policyComplianceStatus=='NON_COMPLIANT';lastJobStatus!='failed';lastJobStatus!='waitingApproval'";

    private static final String DRIFT_PAGE_QUERY = """
            query ($orgId: String!, $first: StringOrInt, $after: StringOrInt, $sort: String, $filter: String) {
              organization(ids: [$orgId]) { edges { node {
                workspace(first: $first, after: $after, sort: $sort, filter: $filter) {
                  edges { node { id name lastJobDate driftFirstSeen driftJobId project { edges { node { name ownerTeam } } } } }
                  pageInfo { totalRecords }
                }
              } } }
            }""";

    private static final String FOUR_COUNTS_QUERY = """
            query ($orgId: String!, $waitingFilter: String, $failedFilter: String, $driftedFilter: String, $policyFilter: String) {
              organization(ids: [$orgId]) { edges { node {
                waiting: workspace(first: "1", after: "0", filter: $waitingFilter) { pageInfo { totalRecords } }
                failed: workspace(first: "1", after: "0", filter: $failedFilter) { pageInfo { totalRecords } }
                drifted: workspace(first: "1", after: "0", filter: $driftedFilter) { pageInfo { totalRecords } }
                policy: workspace(first: "1", after: "0", filter: $policyFilter) { pageInfo { totalRecords } }
              } } }
            }""";

    private String ownerTeam;
    private io.terrakube.api.rs.project.Project project;

    // A project owned by a team, with a drifted workspace and a non-compliant one in it. The drift columns are not
    // written by an entity save, so they are set with the statement that owns them.
    private void seedOwnedProject() {
        ownerTeam = "inbox-owner-" + run;
        Organization organization = organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();
        project = new io.terrakube.api.rs.project.Project();
        project.setName(PREFIX + run + "-project");
        project.setOrganization(organization);
        project.setOwnerTeam(ownerTeam);
        project = projectRepository.save(project);
        Workspace drifted = workspace(organization, "drifted-0", JobStatus.completed, 3);
        drifted.setProject(project);
        workspaces.add(workspaceRepository.save(drifted));
        jdbcTemplate.update("update workspace set drift_status = 'drifted', drift_first_seen = ?, drift_job_id = 77 where id = ?",
                new Date(System.currentTimeMillis() - 2 * 3_600_000L), drifted.getId().toString());
        Workspace policy = workspace(organization, "policy-0", JobStatus.completed, 4);
        policy.setProject(project);
        policy.setPolicyComplianceStatus(io.terrakube.api.rs.policy.PolicyComplianceStatus.NON_COMPLIANT);
        workspaces.add(workspaceRepository.save(policy));
    }

    private ValidatableResponse query(String team, String query, Map<String, Object> variables) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(team), "Content-Type", "application/json")
                .body(Map.of("query", query, "variables", variables))
                .post("/graphql/api/v1")
                .then();
    }

    @Test
    void readsDriftedWorkspacesOfAnOwnedProjectSortedByWhenDriftWasFirstSeen() {
        seedOwnedProject();
        String filter = DRIFTED + ";project.ownerTeam=in=(\"" + ownerTeam + "\");name=ini=*" + PREFIX + run + "*";

        query("TERRAKUBE_ADMIN", DRIFT_PAGE_QUERY, Map.of("orgId", ORG, "first", "25", "after", "0",
                "sort", "+driftFirstSeen,+name", "filter", filter))
                .body("errors", nullValue())
                .body("data.organization.edges[0].node.workspace.pageInfo.totalRecords", is(1))
                .body("data.organization.edges[0].node.workspace.edges[0].node.name", is(PREFIX + run + "-drifted-0"))
                .body("data.organization.edges[0].node.workspace.edges[0].node.driftJobId", is(77))
                .body("data.organization.edges[0].node.workspace.edges[0].node.project.edges[0].node.ownerTeam", is(ownerTeam));
    }

    @Test
    void theOwnerFilterWorksThroughTheProjectReadCheckForSomeoneWhoIsNotASuperuser() {
        seedOwnedProject();
        String filter = DRIFTED + ";project.ownerTeam=in=(\"" + ownerTeam + "\");name=ini=*" + PREFIX + run + "*";

        // TERRAKUBE_DEVELOPERS is an organization team: the relationship goes through the project's own read filter.
        query("TERRAKUBE_DEVELOPERS", DRIFT_PAGE_QUERY, Map.of("orgId", ORG, "first", "25", "after", "0",
                "sort", "+driftFirstSeen,+name", "filter", filter))
                .body("errors", nullValue())
                .body("data.organization.edges[0].node.workspace.pageInfo.totalRecords", is(1));

        // Someone with no team at all gets nothing, and no error that would say the project exists.
        query("NOT_A_MEMBER", DRIFT_PAGE_QUERY, Map.of("orgId", ORG, "first", "25", "after", "0",
                "sort", "+driftFirstSeen,+name", "filter", filter))
                .body("data.organization.edges[0].node.workspace.edges", org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.nullValue(), hasSize(0)));
    }

    @Test
    void sendsTheFourCountFiltersOfTheSummaryInOneRequest() {
        seedOwnedProject();
        String name = ";name=ini=*" + PREFIX + run + "*";
        String owner = ";project.ownerTeam=in=(\"" + ownerTeam + "\")";

        query("TERRAKUBE_ADMIN", FOUR_COUNTS_QUERY, Map.of("orgId", ORG,
                "waitingFilter", "lastJobStatus=='waitingApproval'" + owner + name,
                "failedFilter", "lastJobStatus=='failed'" + owner + name,
                "driftedFilter", DRIFTED + owner + name,
                "policyFilter", POLICY + owner + name))
                .body("errors", nullValue())
                .body("data.organization.edges[0].node.waiting.pageInfo.totalRecords", is(0))
                .body("data.organization.edges[0].node.failed.pageInfo.totalRecords", is(0))
                .body("data.organization.edges[0].node.drifted.pageInfo.totalRecords", is(1))
                .body("data.organization.edges[0].node.policy.pageInfo.totalRecords", is(1));
    }

    private ValidatableResponse page(String sort, String filter, String after, String first) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", "application/json")
                .body(Map.of("query", PAGE_QUERY, "variables", Map.of(
                        "orgId", ORG, "first", first, "after", after, "sort", sort, "filter", filter)))
                .post("/graphql/api/v1")
                .then();
    }

    private Workspace workspace(Organization organization, String name, JobStatus status, int hoursAgo) {
        Workspace workspace = new Workspace();
        workspace.setName(PREFIX + run + "-" + name);
        workspace.setSource("empty");
        workspace.setBranch("remote-content");
        workspace.setTerraformVersion("1.14.0");
        workspace.setOrganization(organization);
        workspace.setLastJobStatus(status);
        workspace.setLastJobDate(new Date(System.currentTimeMillis() - hoursAgo * 3_600_000L));
        return workspaceRepository.save(workspace);
    }
}
