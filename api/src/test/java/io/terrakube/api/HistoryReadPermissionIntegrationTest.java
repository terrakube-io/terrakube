package io.terrakube.api;

import io.restassured.response.Response;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.AccessRepository;
import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.ProjectAccessRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;
import io.terrakube.api.rs.workspace.history.History;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HistoryReadPermissionIntegrationTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @Autowired
    private HistoryRepository historyRepository;
    @Autowired
    private AccessRepository accessRepository;
    @Autowired
    private ProjectAccessRepository projectAccessRepository;
    @MockitoBean
    private StorageTypeService storageTypeService;

    private Workspace workspace;
    private History history;
    private Team team;
    private Project project;
    private ProjectAccess projectAccess;
    private Access access;

    @BeforeEach
    void prepareHistory() {
        project = null;
        projectAccess = null;
        access = null;
        valueOperations = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        team = new Team();
        team.setName("history-team-" + UUID.randomUUID());
        team.setRole("read");
        team.setOrganization(organization);
        team = teamRepository.saveAndFlush(team);

        workspace = new Workspace();
        workspace.setName("history-workspace-" + UUID.randomUUID());
        workspace.setSource("https://github.com/terrakube-io/terrakube.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.13.1");
        workspace.setOrganization(organization);
        workspace = workspaceRepository.saveAndFlush(workspace);

        history = new History();
        history.setWorkspace(workspace);
        history.setSerial(12);
        history.setLineage("state-lineage");
        history.setOutput("https://localhost/tfstate/v1/state/snapshot.json");
        history = historyRepository.saveAndFlush(history);
    }

    @AfterEach
    void cleanUp() {
        if (workspace != null) {
            List<History> histories = historyRepository.findByWorkspace(workspace);
            historyRepository.deleteAll(histories);
            // A CLI state upload creates its own audit job/step. Only remove jobs owned by
            // this test's workspace, never the shared demo fixtures.
            for (History item : histories) {
                if (item.getJobReference() != null) {
                    jobRepository.findById(Integer.parseInt(item.getJobReference())).ifPresent(job -> {
                        if (workspace.getId().equals(job.getWorkspace().getId())) {
                            stepRepository.deleteAll(stepRepository.findByJobId(job.getId()));
                            jobRepository.delete(job);
                        }
                    });
                }
            }
            if (access != null) accessRepository.delete(access);
            workspaceRepository.delete(workspace);
        }
        if (projectAccess != null) {
            projectAccessRepository.delete(projectAccess);
            projectAccessRepository.flush();
        }
        if (project != null) projectRepository.delete(project);
        if (team != null) teamRepository.delete(team);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "write", "custom"})
    void organizationStateManagersCanListHistoryAndLoadWorkspaceIncludes(String role) {
        team.setRole(role);
        team.setManageState("custom".equals(role));
        teamRepository.saveAndFlush(team);
        assertReadable(generatePAT(team.getName()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "project"})
    void limitedStateGrantsCanReadHistory(String level) {
        grantLimitedStateAccess(level);
        assertReadable(generatePAT(team.getName()));
    }

    @Test
    void viewingAWorkspaceDoesNotGrantAccessToItsStateHistory() {
        String token = generatePAT(team.getName());
        given().header("Authorization", "Bearer " + token)
                .get(workspacePath()).then().statusCode(200);
        given().header("Authorization", "Bearer " + token)
                .get(workspacePath() + "/history").then().statusCode(200).body("data", empty());
        Response included = given().header("Authorization", "Bearer " + token)
                .get(workspacePath() + "?include=history").then().statusCode(200).extract().response();
        List<String> types = included.jsonPath().getList("included.type", String.class);
        assertTrue(types == null || !types.contains("history"));
    }

    @Test
    void aLimitedGrantDoesNotExposeAnotherWorkspacesHistory() {
        grantLimitedStateAccess("workspace");
        Workspace other = new Workspace();
        other.setName("other-history-" + UUID.randomUUID());
        other.setOrganization(workspace.getOrganization());
        other.setSource("https://github.com/terrakube-io/terrakube.git");
        other.setBranch("main");
        other.setTerraformVersion("1.13.1");
        other = workspaceRepository.saveAndFlush(other);
        History otherHistory = new History();
        otherHistory.setWorkspace(other);
        otherHistory.setOutput("other-state");
        otherHistory = historyRepository.saveAndFlush(otherHistory);
        try {
            assertReadable(generatePAT(team.getName()));
            given().header("Authorization", "Bearer " + generatePAT(team.getName()))
                    .get("/api/v1/organization/" + ORGANIZATION_ID + "/workspace/" + other.getId() + "/history")
                    .then().statusCode(200).body("data", empty());
            given().header("Authorization", "Bearer " + generatePAT("UNRELATED_ORGANIZATION"))
                    .get(workspacePath() + "/history").then().statusCode(403);
        } finally {
            historyRepository.delete(otherHistory);
            workspaceRepository.delete(other);
        }
    }

    @Test
    void instanceAdministratorsAndMatchingExecutorsStillReadExistingVersions() {
        assertReadable(generatePAT("TERRAKUBE_ADMIN"));
        assertReadable(generateSystemToken(Map.of("organizationId", ORGANIZATION_ID,
                "workspaceId", workspace.getId().toString())));
        assertReadable(generateExecutorToken());
        given().header("Authorization", "Bearer " + generateSystemToken(Map.of(
                        "organizationId", ORGANIZATION_ID, "workspaceId", UUID.randomUUID().toString(),
                        "groups", List.of("TERRAKUBE_ADMIN", team.getName()))))
                .get(workspacePath() + "/history").then().statusCode(404);
    }

    @Test
    void authorizedCliStateUploadsRemainVisibleAndUnauthorizedUploadsAreDenied() {
        String raw = "{\"version\":4,\"terraform_version\":\"1.13.1\",\"serial\":13,"
                + "\"lineage\":\"state-lineage\",\"outputs\":{},\"resources\":[]}";
        String rendered = "{\"format_version\":\"1.0\",\"terraform_version\":\"1.13.1\",\"values\":{}}";
        String body = "{\"data\":{\"type\":\"state-versions\",\"attributes\":{\"serial\":13,"
                + "\"lineage\":\"state-lineage\",\"state\":\"" + encode(raw) + "\","
                + "\"json-state\":\"" + encode(rendered) + "\"}}}";
        String uploadPath = "/remote/tfe/v2/workspaces/" + workspace.getId() + "/state-versions";
        given().header("Authorization", "Bearer " + generatePAT(team.getName()))
                .contentType("application/vnd.api+json").body(body).post(uploadPath).then().statusCode(403);
        assertEquals(1, historyRepository.findByWorkspace(workspace).size());

        team.setRole("custom");
        team.setManageState(true);
        teamRepository.saveAndFlush(team);
        String id = given().header("Authorization", "Bearer " + generatePAT(team.getName()))
                .contentType("application/vnd.api+json").body(body).post(uploadPath)
                .then().statusCode(200).extract().path("data.id");
        verify(storageTypeService).uploadState(eq(ORGANIZATION_ID), eq(workspace.getId().toString()), eq(raw), eq(id));
        verify(storageTypeService).uploadTerraformStateJson(eq(ORGANIZATION_ID), eq(workspace.getId().toString()), eq(rendered), eq(id));
        given().header("Authorization", "Bearer " + generatePAT(team.getName()))
                .get(workspacePath() + "/history").then().statusCode(200).body("data.id", hasItem(id));
    }

    private void assertReadable(String token) {
        given().header("Authorization", "Bearer " + token)
                .get(workspacePath() + "/history").then().statusCode(200).body("data.id", hasItem(history.getId().toString()));
        given().header("Authorization", "Bearer " + token)
                .get(workspacePath() + "/history/" + history.getId()).then().statusCode(200)
                .body("data.attributes.serial", equalTo(12));
        given().header("Authorization", "Bearer " + token)
                .get(workspacePath() + "?include=history").then().statusCode(200)
                .body("included.findAll { it.type == 'history' }.id", hasItem(history.getId().toString()));
    }

    private void grantLimitedStateAccess(String level) {
        if ("workspace".equals(level)) {
            access = new Access();
            access.setWorkspace(workspace);
            access.setName(team.getName());
            access.setRole("custom");
            access.setManageState(true);
            access = accessRepository.saveAndFlush(access);
        } else {
            project = new Project();
            project.setName("history-project-" + UUID.randomUUID());
            project.setOrganization(workspace.getOrganization());
            project = projectRepository.saveAndFlush(project);
            projectAccess = new ProjectAccess();
            projectAccess.setName(team.getName());
            projectAccess.setRole("custom");
            projectAccess.setManageState(true);
            projectAccess.setProject(project);
            projectAccess = projectAccessRepository.saveAndFlush(projectAccess);
            workspace.setProject(project);
            workspaceRepository.saveAndFlush(workspace);
        }
    }

    private String workspacePath() {
        return "/api/v1/organization/" + ORGANIZATION_ID + "/workspace/" + workspace.getId();
    }

    private String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
