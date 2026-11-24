package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import io.restassured.response.ValidatableResponse;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.access.Access;

// The organization and workspace permissions the UI reads for its "your access" chip: besides what the caller can do,
// they say through which teams and with which role.
class WorkspacePermissionsTests extends ServerApplicationTests {

    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String ORG_TEAM = "TERRAKUBE_DEVELOPERS";
    private static final String PROJECT_TEAM = "PERMS_WORKSPACE_PROJECT_READ";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Project project;
    private Workspace workspace;

    @BeforeEach
    void seed() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Organization organization = organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();
        project = new Project();
        project.setName("perms-ws-" + UUID.randomUUID());
        project.setOrganization(organization);
        project = projectRepository.save(project);
        ProjectAccess access = new ProjectAccess();
        access.setName(PROJECT_TEAM);
        access.setRole("read");
        access.setProject(project);
        project.getProjectAccess().add(access);
        project = projectRepository.save(project);

        workspace = new Workspace();
        workspace.setName("perms-ws-" + UUID.randomUUID());
        workspace.setSource("empty");
        workspace.setBranch("remote-content");
        workspace.setTerraformVersion("1.14.0");
        workspace.setOrganization(organization);
        workspace.setProject(project);
        workspace = workspaceRepository.save(workspace);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from access where workspace_id = ?", workspace.getId().toString());
        jdbcTemplate.update("update workspace set project_id = null, deleted = true where id = ?",
                workspace.getId().toString());
        jdbcTemplate.update("delete from project_access where project_id = ?", project.getId().toString());
        jdbcTemplate.update("delete from project where id = ?", project.getId().toString());
    }

    @Test
    void workspacePermissionsNameTheProjectTeamAndRoleThatGrantThem() {
        workspacePermissions(PROJECT_TEAM)
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("manageWorkspace", is(false))
                .body("grantedThrough", contains(PROJECT_TEAM))
                .body("accessLevel", is("read"));
    }

    @Test
    void workspacePermissionsNameTheOrganizationTeamThatGrantsThem() {
        workspacePermissions(ORG_TEAM)
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("grantedThrough", contains(ORG_TEAM))
                .body("accessLevel", notNullValue());
    }

    @Test
    void organizationPermissionsNameTheTeamsThatGrantThem() {
        organizationPermissions(ORG_TEAM)
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("grantedThrough", contains(ORG_TEAM))
                .body("accessLevel", notNullValue());
    }

    @Test
    void aCallerInNoOrganizationTeamGetsNoRoleAndTheResponseKeepsItsOldShape() {
        organizationPermissions(PROJECT_TEAM)
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("manageWorkspace", is(false))
                .body("grantedThrough", nullValue())
                .body("accessLevel", nullValue());
    }

    @Test
    void theAccessRowOfATeamTheCallerIsNotInIsNeitherCountedNorNamed() {
        Access others = new Access();
        others.setName("PERMS_WORKSPACE_OTHER_TEAM");
        others.setRole("admin");
        others.setWorkspace(workspace);
        workspace.setAccess(new java.util.ArrayList<>(java.util.List.of(others)));
        workspace = workspaceRepository.save(workspace);

        workspacePermissions(PROJECT_TEAM)
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("manageWorkspace", is(false))
                .body("planJob", is(false))
                .body("grantedThrough", contains(PROJECT_TEAM))
                .body("accessLevel", is("read"));
    }

    @Test
    void aWorkspaceOfAnotherOrganizationIsReportedAsNotFound() {
        get(ORG_TEAM, "/access-token/v1/teams/permissions/organization/" + UUID.randomUUID() + "/workspace/"
                + workspace.getId())
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    private ValidatableResponse workspacePermissions(String team) {
        return get(team, "/access-token/v1/teams/permissions/organization/" + ORG + "/workspace/" + workspace.getId());
    }

    private ValidatableResponse organizationPermissions(String team) {
        return get(team, "/access-token/v1/teams/permissions/organization/" + ORG);
    }

    private ValidatableResponse get(String team, String path) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(team), "Content-Type", "application/json")
                .when()
                .get(path)
                .then();
    }
}
