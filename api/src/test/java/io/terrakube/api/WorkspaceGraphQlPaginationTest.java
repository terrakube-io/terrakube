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
        return given()
                .headers(
                        "Authorization", "Bearer " + bearerToken,
                        "Content-Type", "application/json")
                .body(Map.of("query", QUERY, "variables", variables))
                .when()
                .post("/graphql/api/v1");
    }
}
