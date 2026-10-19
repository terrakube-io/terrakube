package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import io.restassured.response.Response;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * #3726: the workspace list must find workspaces that have a run waiting for approval even when
 * Workspace.lastJobStatus (the status of whichever run changed last) says something else, using
 * only the filter the Elide GraphQL endpoint already supports on the job relationship.
 */
class WorkspaceWaitingApprovalFilterTest extends ServerApplicationTests {

    private static final UUID ORGANIZATION_ID = UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8");
    private static final String NAME_FILTER = "name==\"wa-filter-*\"";
    private static final String WAITING_FILTER = "(lastJobStatus==\"waitingApproval\",job.status==\"waitingApproval\")";
    private static final String QUERY = """
            query Page($organizationIds: [String], $filter: String, $all: String) {
              organization(ids: $organizationIds) {
                edges {
                  node {
                    workspace(first: "50", filter: $filter, sort: "name,id") {
                      edges { node { name } }
                      pageInfo { totalRecords }
                    }
                    all: workspace(first: "1", filter: $all) { pageInfo { totalRecords } }
                  }
                }
              }
            }
            """;

    private final List<Workspace> workspaces = new ArrayList<>();
    private final List<Job> jobs = new ArrayList<>();

    @BeforeEach
    void setupWorkspaces() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Organization organization = organizationRepository.findById(ORGANIZATION_ID).orElseThrow();

        // Older run waiting for approval, newer run pending behind it: lastJobStatus is "pending".
        Workspace hiddenBehindPending = workspace(organization, "wa-filter-1-hidden", JobStatus.pending);
        job(organization, hiddenBehindPending, JobStatus.waitingApproval);
        job(organization, hiddenBehindPending, JobStatus.pending);

        // Two runs waiting at once (plan-only runs can overtake): the workspace must be listed once.
        Workspace twoWaiting = workspace(organization, "wa-filter-2-two", JobStatus.completed);
        job(organization, twoWaiting, JobStatus.waitingApproval);
        job(organization, twoWaiting, JobStatus.waitingApproval);
        job(organization, twoWaiting, JobStatus.completed);

        // lastJobStatus alone says waiting, and there is no job row at all for it.
        workspace(organization, "wa-filter-3-last-only", JobStatus.waitingApproval);

        // Nothing waiting.
        Workspace none = workspace(organization, "wa-filter-4-none", JobStatus.completed);
        job(organization, none, JobStatus.completed);
        workspace(organization, "wa-filter-5-empty", JobStatus.NeverExecuted);
    }

    @AfterEach
    void cleanUp() {
        jobRepository.deleteAll(jobs);
        workspaceRepository.deleteAll(workspaces);
        workspaceRepository.flush();
        jobs.clear();
        workspaces.clear();
    }

    @Test
    void waitingApprovalFilterFindsWorkspacesWhoseLastJobStatusIsSomethingElse() {
        Response response = query(NAME_FILTER + ";" + WAITING_FILTER);

        assertThat(response.jsonPath().getList("errors")).as(response.asPrettyString()).isNull();
        assertThat(response.jsonPath().getList("data.organization.edges[0].node.workspace.edges.node.name", String.class))
                .as(response.asPrettyString())
                .containsExactly("wa-filter-1-hidden", "wa-filter-2-two", "wa-filter-3-last-only");
        assertThat(response.jsonPath().getInt("data.organization.edges[0].node.workspace.pageInfo.totalRecords"))
                .as(response.asPrettyString()).isEqualTo(3);
    }

    @Test
    void unfilteredListIsNotAffectedByTheJoin() {
        Response response = query(NAME_FILTER);

        assertThat(response.jsonPath().getList("data.organization.edges[0].node.workspace.edges.node.name", String.class))
                .as(response.asPrettyString()).hasSize(5);
        assertThat(response.jsonPath().getInt("data.organization.edges[0].node.workspace.pageInfo.totalRecords"))
                .isEqualTo(5);
    }

    private Response query(String filter) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type",
                        "application/json")
                .body(Map.of("query", QUERY, "variables",
                        Map.of("organizationIds", List.of(ORGANIZATION_ID.toString()), "filter", filter, "all",
                                NAME_FILTER)))
                .post("/graphql/api/v1");
    }

    private Workspace workspace(Organization organization, String name, JobStatus lastJobStatus) {
        Workspace workspace = new Workspace();
        workspace.setOrganization(organization);
        workspace.setName(name);
        workspace.setDescription(name);
        workspace.setSource("https://example.com/" + name + ".git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.9.0");
        workspace.setIacType("terraform");
        workspace.setLastJobStatus(lastJobStatus);
        Workspace saved = workspaceRepository.saveAndFlush(workspace);
        workspaces.add(saved);
        return saved;
    }

    private void job(Organization organization, Workspace workspace, JobStatus status) {
        Job job = new Job();
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job.setStatus(status);
        jobs.add(jobRepository.saveAndFlush(job));
    }
}
