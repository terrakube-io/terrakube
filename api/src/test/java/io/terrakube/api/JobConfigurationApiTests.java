package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.ContentRepository;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.content.Content;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties = "spring.quartz.auto-startup=false")
class JobConfigurationApiTests extends ServerApplicationTests {
    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String ROUTE = "/api/v1/organization/" + ORG + "/job";
    @MockitoBean ScheduleJobService scheduleJobService;
    @Autowired ContentRepository contents;
    @Autowired io.terrakube.api.repository.ProjectAccessRepository projectAccesses;

    @Test
    void planOnlyIdentityCanBindItsUploadButCannotOverrideSourcesOrRebindJobs() {
        var workspace = new Workspace();
        workspace.setName("configuration-upload-test");
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.5.4");
        workspace.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        workspace = workspaceRepository.save(workspace);
        var team = new Team();
        team.setName("CONFIGURATION_UPLOAD_TEST");
        team.setRole("plan");
        team.setOrganization(workspace.getOrganization());
        teamRepository.save(team);
        String token = generatePAT(team.getName());
        var content = new Content();
        content.setWorkspace(workspace);
        content.setStatus("uploaded");
        content = contents.save(content);
        var relationships = Map.of("workspace", Map.of("data", Map.of("type", "workspace", "id", workspace.getId().toString())));
        var body = Map.of("data", Map.of("type", "job", "attributes", Map.of(
                "configurationVersion", content.getId().toString(), "commitId", "a".repeat(40)), "relationships", relationships));
        var response = given().auth().oauth2(token).contentType("application/vnd.api+json").body(body).post(ROUTE);
        assertEquals(201, response.statusCode(), response.asString());
        String id = response.path("data.id");
        var stored = jobRepository.findById(Integer.parseInt(id)).orElseThrow();
        assertEquals("remote-content", stored.getOverrideBranch());
        assertTrue(stored.getOverrideSource().endsWith("/" + content.getId() + "/terraformContent.tar.gz"));
        assertEquals("a".repeat(40), stored.getCommitId());
        var update = Map.of("data", Map.of("type", "job", "id", id,
                "attributes", Map.of("configurationVersion", content.getId().toString())));
        assertTrue(given().auth().oauth2(token).contentType("application/vnd.api+json").body(update)
                .patch(ROUTE + "/" + id).statusCode() >= 400);
        var arbitrarySource = Map.of("data", Map.of("type", "job", "attributes",
                Map.of("overrideSource", "https://attacker.example/config.tar.gz"), "relationships", relationships));
        assertEquals(403, given().auth().oauth2(token).contentType("application/vnd.api+json")
                .body(arbitrarySource).post(ROUTE).statusCode());
        content.setStatus("pending");
        contents.save(content);
        long before = jobRepository.count();
        assertEquals(400, given().auth().oauth2(token).contentType("application/vnd.api+json").body(body).post(ROUTE).statusCode());
        assertEquals(before, jobRepository.count());
        content.setWorkspace(workspaceRepository.findAll().stream().filter(w -> !w.getId().equals(stored.getWorkspace().getId())).findFirst().orElseThrow());
        content.setStatus("uploaded");
        contents.save(content);
        assertEquals(400, given().auth().oauth2(token).contentType("application/vnd.api+json").body(body).post(ROUTE).statusCode());
        assertEquals(before, jobRepository.count());
    }
    @Test
    void configurationUploadsHonorProjectScopedPlanRightsWithoutGrantingOtherProjects() {
        var organization = organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();
        var project = new io.terrakube.api.rs.project.Project();
        project.setName("upload-project");
        project.setOrganization(organization);
        project = projectRepository.save(project);
        var workspace = new Workspace();
        workspace.setName("project-configuration-upload");
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.5.4");
        workspace.setOrganization(organization);
        workspace.setProject(project);
        workspace = workspaceRepository.save(workspace);
        var team = new Team();
        team.setName("CONFIGURATION_PROJECT_TEST");
        team.setRole("custom");
        team.setOrganization(organization);
        teamRepository.save(team);
        var access = new io.terrakube.api.rs.project.access.ProjectAccess();
        access.setName(team.getName());
        access.setRole("plan");
        access.setProject(project);
        projectAccesses.save(access);
        String token = generatePAT(team.getName());
        var body = Map.of("data", Map.of("type", "configuration-versions", "attributes",
                Map.of("speculative", false, "auto-queue-runs", false)));
        var response = given().auth().oauth2(token).contentType("application/vnd.api+json").body(body)
                .post("/remote/tfe/v2/workspaces/" + workspace.getId() + "/configuration-versions");
        assertEquals(201, response.statusCode(), response.asString());
        UUID id = UUID.fromString(response.path("data.id"));
        assertEquals(workspace.getId(), contents.findById(id).orElseThrow().getWorkspace().getId());
        UUID other = workspaceRepository.findAll().stream()
                .filter(w -> !w.getId().equals(contents.findById(id).orElseThrow().getWorkspace().getId()))
                .findFirst().orElseThrow().getId();
        assertEquals(403, given().auth().oauth2(token).contentType("application/vnd.api+json").body(body)
                .post("/remote/tfe/v2/workspaces/" + other + "/configuration-versions").statusCode());
    }

}
