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
            jdbcTemplate.update("update workspace set deleted = true where id = ?", workspace.getId().toString());
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
