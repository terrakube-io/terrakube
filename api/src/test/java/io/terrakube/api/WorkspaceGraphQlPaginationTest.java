package io.terrakube.api;

import io.restassured.response.Response;
import io.terrakube.api.repository.WorkspaceTagRepository;
import io.terrakube.api.repository.TagRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class WorkspaceGraphQlPaginationTest extends ServerApplicationTests {

    private static final UUID ORGANIZATION_ID = UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8");
    private static final String QUERY = """
            query WorkspacePage(
              $organizationId: ID!
              $first: Int
              $after: String
              $search: String
              $status: String
              $tagIds: [ID!]
              $projectId: ID
              $sort: WorkspaceSort
            ) {
              workspacePage(
                organizationId: $organizationId
                first: $first
                after: $after
                search: $search
                status: $status
                tagIds: $tagIds
                projectId: $projectId
                sort: $sort
              ) {
                nodes { id name lastJobStatus tagIds projectId }
                pageInfo { endCursor hasNextPage totalRecords }
                statusCounts { all running completed }
              }
            }
            """;

    @Autowired
    WorkspaceTagRepository workspaceTagRepository;

    @Autowired
    TagRepository tagRepository;

    private final List<Workspace> created = new ArrayList<>();
    private final List<WorkspaceTag> createdTags = new ArrayList<>();
    private Project project;
    private Tag tag;
    private String token;

    @BeforeEach
    void setupWorkspaces() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        token = generatePAT("TERRAKUBE_DEVELOPERS");

        Organization organization = organizationRepository.findById(ORGANIZATION_ID).orElseThrow();
        project = new Project();
        project.setOrganization(organization);
        project.setName("cursor-page-project");
        project.setDescription("Cursor pagination test project");
        project = projectRepository.saveAndFlush(project);

        Workspace alpha = saveWorkspace(organization, "cursor-page-alpha", JobStatus.running, project);
        created.add(alpha);
        created.add(saveWorkspace(organization, "cursor-page-bravo", JobStatus.completed, project));
        created.add(saveWorkspace(organization, "cursor-page-charlie", JobStatus.running, null));

        tag = new Tag();
        tag.setOrganization(organization);
        tag.setName("cursor-page-tag");
        tag = tagRepository.saveAndFlush(tag);

        WorkspaceTag workspaceTag = new WorkspaceTag();
        workspaceTag.setWorkspace(alpha);
        workspaceTag.setTagId(tag.getId().toString());
        createdTags.add(workspaceTagRepository.saveAndFlush(workspaceTag));
    }

    @AfterEach
    void removeWorkspaces() {
        workspaceTagRepository.deleteAll(createdTags);
        workspaceTagRepository.flush();
        workspaceRepository.deleteAll(created);
        workspaceRepository.flush();
        projectRepository.delete(project);
        projectRepository.flush();
        tagRepository.delete(tag);
        tagRepository.flush();
    }

    @Test
    void paginatesAuthorizedFilteredAndSortedWorkspacesWithOpaqueCursors() {
        Map<String, Object> variables = baseVariables();
        variables.put("status", "running");

        Response firstPage = execute(variables);
        firstPage.then().statusCode(200);
        assertThat(firstPage.jsonPath().getList("data.workspacePage.nodes.name", String.class))
                .containsExactly("cursor-page-alpha");
        assertThat(firstPage.jsonPath().getInt("data.workspacePage.pageInfo.totalRecords")).isEqualTo(2);
        assertThat(firstPage.jsonPath().getBoolean("data.workspacePage.pageInfo.hasNextPage")).isTrue();
        assertThat(firstPage.jsonPath().getInt("data.workspacePage.statusCounts.all")).isEqualTo(3);
        assertThat(firstPage.jsonPath().getInt("data.workspacePage.statusCounts.running")).isEqualTo(2);
        assertThat(firstPage.jsonPath().getInt("data.workspacePage.statusCounts.completed")).isEqualTo(1);

        String cursor = firstPage.jsonPath().getString("data.workspacePage.pageInfo.endCursor");
        assertThat(cursor).isNotBlank().doesNotContain("cursor-page-alpha");

        variables.put("after", cursor);
        Response secondPage = execute(variables);
        secondPage.then().statusCode(200);
        assertThat(secondPage.jsonPath().getList("data.workspacePage.nodes.name", String.class))
                .containsExactly("cursor-page-charlie");
        assertThat(secondPage.jsonPath().getBoolean("data.workspacePage.pageInfo.hasNextPage")).isFalse();
    }

    @Test
    void appliesProjectAndTagFiltersOnTheServer() {
        Map<String, Object> variables = baseVariables();
        variables.put("first", 20);
        variables.put("tagIds", List.of(tag.getId().toString()));
        variables.put("projectId", project.getId().toString());

        Response response = execute(variables);
        response.then().statusCode(200);
        assertThat(response.jsonPath().getList("data.workspacePage.nodes.name", String.class))
                .containsExactly("cursor-page-alpha");
        assertThat(response.jsonPath().getInt("data.workspacePage.pageInfo.totalRecords")).isEqualTo(1);
    }

    @Test
    void keepsEverySupportedSortCursorStableWhenValuesAreNullOrEqual() {
        List<String> sorts = List.of(
                "NAME_ASC",
                "NAME_DESC",
                "LAST_RUN_ASC",
                "LAST_RUN_DESC",
                "STATUS",
                "SOURCE_ASC",
                "SOURCE_DESC",
                "TERRAFORM_VERSION_ASC",
                "TERRAFORM_VERSION_DESC");

        for (String sort : sorts) {
            Map<String, Object> variables = baseVariables();
            variables.put("sort", sort);
            List<String> names = new ArrayList<>();

            while (true) {
                Response response = execute(variables);
                assertThat(response.jsonPath().getList("errors")).as(response.asPrettyString()).isNull();
                names.addAll(response.jsonPath().getList("data.workspacePage.nodes.name", String.class));
                if (!response.jsonPath().getBoolean("data.workspacePage.pageInfo.hasNextPage")) {
                    break;
                }
                variables.put("after", response.jsonPath().getString("data.workspacePage.pageInfo.endCursor"));
            }

            assertThat(names).hasSize(3).containsExactlyInAnyOrderElementsOf(Set.of(
                    "cursor-page-alpha", "cursor-page-bravo", "cursor-page-charlie"));
        }
    }

    @Test
    void doesNotExposeWorkspacesOrCountsToAnUnauthorizedGroup() {
        Response response = execute(baseVariables(), generatePAT("NO_WORKSPACE_ACCESS"));

        response.then().statusCode(200);
        assertThat(response.jsonPath().getList("data.workspacePage.nodes")).isEmpty();
        assertThat(response.jsonPath().getInt("data.workspacePage.pageInfo.totalRecords")).isZero();
        assertThat(response.jsonPath().getInt("data.workspacePage.statusCounts.all")).isZero();
    }

    private Map<String, Object> baseVariables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("organizationId", ORGANIZATION_ID.toString());
        variables.put("first", 1);
        variables.put("search", "CURSOR-PAGE");
        variables.put("sort", "NAME_ASC");
        return variables;
    }

    private Workspace saveWorkspace(
            Organization organization,
            String name,
            JobStatus status,
            Project workspaceProject) {
        Workspace workspace = new Workspace();
        workspace.setOrganization(organization);
        workspace.setProject(workspaceProject);
        workspace.setName(name);
        workspace.setDescription(name);
        workspace.setSource("https://example.com/" + name + ".git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.9.0");
        workspace.setIacType("terraform");
        workspace.setLastJobStatus(status);
        return workspaceRepository.saveAndFlush(workspace);
    }

    private Response execute(Map<String, Object> variables) {
        return execute(variables, token);
    }

    private Response execute(Map<String, Object> variables, String bearerToken) {
        return given()
                .headers(
                        "Authorization", "Bearer " + bearerToken,
                        "Content-Type", "application/json")
                .body(Map.of("query", QUERY, "variables", variables))
                .when()
                .post("/graphql");
    }
}
