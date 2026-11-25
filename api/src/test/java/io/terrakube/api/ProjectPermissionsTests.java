package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.access.ProjectAccess;

// The caller's effective permissions on a project, as the UI reads them to enable or disable actions.
class ProjectPermissionsTests extends ServerApplicationTests {

    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String OTHER_ORG = "3a130a1c-d96f-4f99-83b8-58d472567e3a";
    private static final String ADMIN_TEAM = "PERMS_PROJECT_ADMIN";
    private static final String READ_TEAM = "PERMS_PROJECT_READ";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Project project;

    @BeforeEach
    void seed() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Organization organization = organizationRepository.findById(UUID.fromString(ORG)).orElseThrow();
        project = new Project();
        project.setName("perms-" + UUID.randomUUID());
        project.setOrganization(organization);
        project = projectRepository.save(project);
        project.getProjectAccess().add(access(ADMIN_TEAM, "admin"));
        project.getProjectAccess().add(access(READ_TEAM, "read"));
        project = projectRepository.save(project);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from project_access where project_id = ?", project.getId().toString());
        jdbcTemplate.update("delete from project where id = ?", project.getId().toString());
    }

    @Test
    void projectAdminCanManageTheProject() {
        permissions(ADMIN_TEAM, ORG, project.getId().toString())
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("manageProject", is(true))
                .body("manageWorkspace", is(true))
                .body("planJob", is(true))
                .body("grantedThrough", org.hamcrest.Matchers.contains(ADMIN_TEAM))
                .body("accessLevel", is("admin"));
    }

    @Test
    void readOnlyProjectTeamCannotChangeAnything() {
        permissions(READ_TEAM, ORG, project.getId().toString())
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("manageProject", is(false))
                .body("manageWorkspace", is(false))
                .body("planJob", is(false))
                .body("grantedThrough", org.hamcrest.Matchers.contains(READ_TEAM))
                .body("accessLevel", is("read"));
    }

    @Test
    void projectFromAnotherOrganizationIsNotFound() {
        permissions(ADMIN_TEAM, OTHER_ORG, project.getId().toString()).statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void aCallerWhoCannotSeeTheProjectIsToldItDoesNotExist() {
        permissions("PERMS_NOT_A_MEMBER", ORG, project.getId().toString()).statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void anOrganizationTeamThatManagesWorkspacesSeesTheProjectWithoutAProjectGrant() {
        permissions("TERRAKUBE_DEVELOPERS", ORG, project.getId().toString())
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("manageWorkspace", is(true));
    }

    @Test
    void theInstanceOwnerSeesEveryProject() {
        permissions("TERRAKUBE_ADMIN", ORG, project.getId().toString())
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("manageProject", is(true));
    }

    @Test
    void aCustomTeamWithFullRightsIsNotOutvotedByARoleBelowIt() {
        io.terrakube.api.rs.team.Team custom = new io.terrakube.api.rs.team.Team();
        custom.setName("PERMS_CUSTOM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        custom.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        custom.setRole("custom");
        custom.setManageWorkspace(true);
        custom.setManageJob(true);
        custom.setPlanJob(true);
        custom.setApproveJob(true);
        custom = teamRepository.save(custom);
        try {
            given()
                    .headers("Authorization", "Bearer " + generatePAT(custom.getName(), READ_TEAM), "Content-Type", "application/json")
                    .when()
                    .get("/access-token/v1/teams/permissions/organization/" + ORG + "/project/" + project.getId())
                    .then()
                    .statusCode(HttpStatus.ACCEPTED.value())
                    .body("manageProject", is(true))
                    // The flags allow managing the project, so the label is not "read" because the read team ranks.
                    .body("accessLevel", is("admin"));
        } finally {
            jdbcTemplate.update("delete from team where id = ?", custom.getId().toString());
        }
    }

    @Test
    void aReadOrPlanOrganizationTeamDoesNotCountAsAGrantOnTheProject() {
        permissions(READ_TEAM, ORG, project.getId().toString())
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("grantedThrough", org.hamcrest.Matchers.contains(READ_TEAM))
                .body("accessLevel", is("read"));
    }

    @Test
    void malformedIdsAreNotFound() {
        permissions(ADMIN_TEAM, ORG, "not-a-uuid").statusCode(HttpStatus.NOT_FOUND.value());
    }

    private ProjectAccess access(String team, String role) {
        ProjectAccess access = new ProjectAccess();
        access.setName(team);
        access.setRole(role);
        access.setProject(project);
        return access;
    }

    private io.restassured.response.ValidatableResponse permissions(String team, String org, String projectId) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT(team), "Content-Type", "application/json")
                .when()
                .get("/access-token/v1/teams/permissions/organization/" + org + "/project/" + projectId)
                .then();
    }
}
