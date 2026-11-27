package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectStateSharingIntegrationTest extends ServerApplicationTests {
    private static final UUID ORG_ID = UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8");
    private static final String ATOMIC_JSON = "application/vnd.api+json;ext=\"https://jsonapi.org/ext/atomic\"";
    private Project project;
    private Workspace source;
    private Workspace sameProject;
    private Workspace otherProject;
    private String token;

    @BeforeEach
    void createFixtures() {
        Organization org = organizationRepository.findById(ORG_ID).orElseThrow();
        project = new Project();
        project.setName("state-sharing-" + UUID.randomUUID());
        project.setOrganization(org);
        project = projectRepository.saveAndFlush(project);
        source = createWorkspace(org, project);
        sameProject = createWorkspace(org, project);
        otherProject = createWorkspace(org, null);
        token = generatePAT("TERRAKUBE_DEVELOPERS");
    }

    @AfterEach
    void removeFixtures() {
        if (source != null) workspaceRepository.deleteById(source.getId());
        if (sameProject != null) workspaceRepository.deleteById(sameProject.getId());
        if (otherProject != null) workspaceRepository.deleteById(otherProject.getId());
        if (project != null) projectRepository.deleteById(project.getId());
    }

    private Workspace createWorkspace(Organization org, Project project) {
        Workspace workspace = new Workspace();
        workspace.setName("state-sharing-" + UUID.randomUUID());
        workspace.setOrganization(org);
        workspace.setProject(project);
        workspace.setSource("https://github.com/terrakube-io/terrakube.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.13.1");
        return workspaceRepository.saveAndFlush(workspace);
    }

    private int update(String attributes) {
        String body = """
                {"atomic:operations":[{"op":"update","href":"/organization/%s/workspace/%s",
                "data":{"type":"workspace","id":"%s","attributes":%s}}]}
                """.formatted(ORG_ID, source.getId(), source.getId(), attributes);
        return given().headers("Authorization", "Bearer " + token, "Content-Type", ATOMIC_JSON, "Accept", ATOMIC_JSON)
                .body(body).when().post("/api/v1/operations").statusCode();
    }

    private Workspace currentSource() {
        return workspaceRepository.findById(source.getId()).orElseThrow();
    }

    @Test
    void atomicUpdatesEnforceScopesAndPreserveExistingIndividualGrants() {
        assertEquals(200, update("{\"globalRemoteState\":false,\"sharedIds\":\"" + sameProject.getId() + "\"}"));
        assertEquals(200, update("{\"projectRemoteState\":true}"));
        assertEquals(sameProject.getId().toString(), currentSource().getSharedIds());
        assertTrue(currentSource().isProjectRemoteState());

        assertEquals(400, update("{\"globalRemoteState\":true}"));
        assertFalse(currentSource().isGlobalRemoteState());
        assertTrue(currentSource().isProjectRemoteState());

        assertEquals(200, update("{\"sharedIds\":\"" + sameProject.getId() + "," + otherProject.getId() + "\"}"));
        assertEquals(200, update("{\"sharedIds\":\"" + otherProject.getId() + "\"}"));
        assertEquals(400, update("{\"sharedIds\":\"" + otherProject.getId() + "," + sameProject.getId() + "\"}"));
        assertEquals(otherProject.getId().toString(), currentSource().getSharedIds());

        assertEquals(200, update("{\"projectRemoteState\":false}"));
        assertEquals(200, update("{\"sharedIds\":\"" + otherProject.getId() + "," + sameProject.getId() + "\"}"));
    }

    @Test
    void atomicUpdateCannotEnableProjectSharingAndAddCoveredWorkspaceTogether() {
        assertEquals(400, update("{\"globalRemoteState\":false,\"projectRemoteState\":true,\"sharedIds\":\""
                + sameProject.getId() + "\"}"));
        assertTrue(currentSource().isGlobalRemoteState());
        assertFalse(currentSource().isProjectRemoteState());
    }

    @Test
    void projectSharingRequiresProjectWhenEnabledButAllowsRemovalAndRecovery() {
        given().headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\"data\":null}")
                .when().patch("/api/v1/organization/" + ORG_ID + "/workspace/" + source.getId()
                        + "/relationships/project")
                .then().statusCode(204);
        assertEquals(400, update("{\"globalRemoteState\":false,\"projectRemoteState\":true}"));
        assertTrue(currentSource().isGlobalRemoteState());
        assertFalse(currentSource().isProjectRemoteState());
        given().headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspace\",\"id\":\"" + source.getId()
                        + "\",\"attributes\":{\"globalRemoteState\":false,\"projectRemoteState\":true}}}")
                .when().patch("/api/v1/organization/" + ORG_ID + "/workspace/" + source.getId())
                .then().statusCode(400);
        assertTrue(currentSource().isGlobalRemoteState());

        given().headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"project\",\"id\":\"" + project.getId() + "\"}}")
                .when().patch("/api/v1/organization/" + ORG_ID + "/workspace/" + source.getId()
                        + "/relationships/project")
                .then().statusCode(204);
        assertEquals(200, update("{\"globalRemoteState\":false,\"projectRemoteState\":true}"));
        given().headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\"data\":null}")
                .when().patch("/api/v1/organization/" + ORG_ID + "/workspace/" + source.getId()
                        + "/relationships/project")
                .then().statusCode(204);
        assertTrue(currentSource().isProjectRemoteState());
        assertEquals(200, update("{\"projectRemoteState\":true}"));
        assertEquals(200, update("{\"projectRemoteState\":false}"));
        assertFalse(currentSource().isProjectRemoteState());
    }

    @Test
    void workspaceSearchIncludesProjectRelationshipForPicker() {
        given().header("Authorization", "Bearer " + token)
                .queryParam("fields[workspace]", "name,project")
                .queryParam("filter[workspace]", "name=='*" + sameProject.getName() + "*'")
                .queryParam("include", "project")
                .when().get("/api/v1/organization/" + ORG_ID + "/workspace")
                .then().statusCode(200)
                .body("data[0].id", equalTo(sameProject.getId().toString()))
                .body("data[0].relationships.project.data.id", equalTo(project.getId().toString()));
    }
}
