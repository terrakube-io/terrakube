package io.terrakube.api;

import io.restassured.response.Response;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class WorkspaceGraphQlPaginationTest extends ServerApplicationTests {

    private static final UUID ORGANIZATION_ID = UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8");
    private static final String QUERY = """
            query WorkspacePage(
              $organizationIds: [String]
              $first: StringOrInt
              $after: StringOrInt
              $filter: String
              $sort: String
              $allFilter: String
              $completedFilter: String
            ) {
              organization(ids: $organizationIds) {
                edges {
                  node {
                    workspace(first: $first, after: $after, filter: $filter, sort: $sort) {
                      edges { node { name lastJobStatus } }
                      pageInfo { endCursor hasNextPage totalRecords }
                    }
                    all: workspace(first: "1", filter: $allFilter) { pageInfo { totalRecords } }
                    completed: workspace(first: "1", filter: $completedFilter) { pageInfo { totalRecords } }
                  }
                }
              }
            }
            """;

    private final List<Workspace> created = new ArrayList<>();
    private String token;

    @BeforeEach
    void setupWorkspaces() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        token = generatePAT("TERRAKUBE_DEVELOPERS");

        Organization organization = organizationRepository.findById(ORGANIZATION_ID).orElseThrow();
        created.add(saveWorkspace(organization, "native-page-alpha", JobStatus.running));
        created.add(saveWorkspace(organization, "native-page-bravo", JobStatus.completed));
        created.add(saveWorkspace(organization, "native-page-charlie", JobStatus.running));
    }

    @AfterEach
    void removeWorkspaces() {
        workspaceRepository.deleteAll(created);
        workspaceRepository.flush();
        created.clear();
    }

    @Test
    void loadsTheFullUiRequest() throws Exception {
        removeWorkspaces();
        String body = new String(getClass().getResourceAsStream("/workspace-page-request.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String group : List.of("TERRAKUBE_DEVELOPERS", "TERRAKUBE_ADMIN")) {
            Response response = given().headers("Authorization", "Bearer " + generatePAT(group), "Content-Type", "application/json")
                    .body(body).post("/graphql/api/v1");
            assertThat(response.jsonPath().getList("errors")).as(response.asPrettyString()).isNull();
            assertThat(response.jsonPath().getList("data.organization.edges[0].node.workspace.edges.node.name", String.class))
                    .as(response.asPrettyString()).containsExactly("sample_simple", "simple_tag1", "simple_tag2", "simple_tag3");
            assertThat(response.jsonPath().getInt("data.organization.edges[0].node.all.pageInfo.totalRecords")).isEqualTo(4);
        }
    }

    @Test
    void demoProjectOnlyListsAssignedWorkspaces() {
        UUID projectId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        String filter = "project.id==\"" + projectId + "\"";
        Map<String, Object> variables = Map.of(
                "organizationIds", List.of(ORGANIZATION_ID.toString()), "first", "20", "after", "0",
                "sort", "name,id", "filter", filter, "allFilter", filter,
                "completedFilter", filter + ";lastJobStatus==completed");
        Response empty = execute(variables, token);
        assertThat(empty.jsonPath().getList("errors")).as(empty.asPrettyString()).isNull();
        assertThat(empty.jsonPath().getList("data.organization.edges[0].node.workspace.edges")).isEmpty();
        assertThat(empty.jsonPath().getInt("data.organization.edges[0].node.all.pageInfo.totalRecords")).isZero();

        Workspace assigned = created.getFirst();
        assigned.setProject(projectRepository.findById(projectId).orElseThrow());
        workspaceRepository.saveAndFlush(assigned);
        Response populated = execute(variables, token);
        assertThat(populated.jsonPath().getList("errors")).as(populated.asPrettyString()).isNull();
        assertThat(populated.jsonPath().getList("data.organization.edges[0].node.workspace.edges.node.name", String.class))
                .containsExactly(assigned.getName());
    }

    @Test
    void loadsWorkspacesWithoutOptionalFilters() {
        String unfilteredQuery = QUERY
                .replace("$filter: String", "")
                .replace("$allFilter: String", "")
                .replace(", filter: $filter", "")
                .replace(", filter: $allFilter", "");
        Response response = execute(unfilteredQuery, Map.of(
                "organizationIds", List.of(ORGANIZATION_ID.toString()),
                "first", "20",
                "after", "0",
                "sort", "name,id",
                "completedFilter", "lastJobStatus==\"completed\""), token);
        response.then().statusCode(200);
        assertThat(response.jsonPath().getList("errors")).as(response.asPrettyString()).isNull();
        assertThat(response.jsonPath().getList("data.organization.edges[0].node.workspace.edges.node.name", String.class))
                .contains("native-page-alpha", "native-page-bravo", "native-page-charlie");
    }

    @Test
    void usesElidePaginationFilteringSortingTotalsAndSecurity() {
        String baseFilter = "(name=ini=\"*NATIVE-PAGE*\",description=ini=\"*NATIVE-PAGE*\")";
        Map<String, Object> variables = Map.of(
                "organizationIds", List.of(ORGANIZATION_ID.toString()),
                "first", "1",
                "after", "0",
                "filter", baseFilter + ";lastJobStatus==\"running\"",
                "sort", "name,id",
                "allFilter", baseFilter,
                "completedFilter", baseFilter + ";lastJobStatus==\"completed\"");

        Response firstPage = execute(variables, token);
        firstPage.then().statusCode(200);
        assertThat(firstPage.jsonPath().getList("errors")).as(firstPage.asPrettyString()).isNull();
        assertThat(firstPage.jsonPath().getList("data.organization.edges[0].node.workspace.edges.node.name", String.class))
                .containsExactly("native-page-alpha");
        assertThat(firstPage.jsonPath().getInt("data.organization.edges[0].node.workspace.pageInfo.totalRecords"))
                .isEqualTo(2);
        assertThat(firstPage.jsonPath().getBoolean("data.organization.edges[0].node.workspace.pageInfo.hasNextPage"))
                .isTrue();
        assertThat(firstPage.jsonPath().getInt("data.organization.edges[0].node.all.pageInfo.totalRecords"))
                .isEqualTo(3);
        assertThat(firstPage.jsonPath().getInt("data.organization.edges[0].node.completed.pageInfo.totalRecords"))
                .isEqualTo(1);

        Map<String, Object> secondPageVariables = new HashMap<>(variables);
        secondPageVariables.put("after", "1");
        Response secondPage = execute(secondPageVariables, token);
        assertThat(secondPage.jsonPath().getList("data.organization.edges[0].node.workspace.edges.node.name", String.class))
                .containsExactly("native-page-charlie");

        Response denied = execute(variables, generatePAT("NO_WORKSPACE_ACCESS"));
        denied.then().statusCode(200);
        assertThat(denied.jsonPath().getList("data.organization.edges"))
                .as(denied.asPrettyString())
                .isNull();
    }

    private Workspace saveWorkspace(Organization organization, String name, JobStatus status) {
        Workspace workspace = new Workspace();
        workspace.setOrganization(organization);
        workspace.setName(name);
        workspace.setDescription(name);
        workspace.setSource("https://example.com/" + name + ".git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.9.0");
        workspace.setIacType("terraform");
        workspace.setLastJobStatus(status);
        return workspaceRepository.saveAndFlush(workspace);
    }

    private Response execute(Map<String, Object> variables, String bearerToken) {
        return execute(QUERY, variables, bearerToken);
    }

    private Response execute(String query, Map<String, Object> variables, String bearerToken) {
        return given()
                .headers(
                        "Authorization", "Bearer " + bearerToken,
                        "Content-Type", "application/json")
                .body(Map.of("query", query, "variables", variables))
                .when()
                .post("/graphql/api/v1");
    }
}
