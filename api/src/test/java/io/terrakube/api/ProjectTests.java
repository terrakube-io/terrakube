package io.terrakube.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.workspace.Workspace;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

public class ProjectTests extends ServerApplicationTests {

    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void createHistoryAsOrgMember() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),"Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"test-project\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/project")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),"Content-Type", "application/vnd.api+json")
                .when()
                .get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/project/" + projectId )
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/project/"+projectId)
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }

    @Test
    void createHistoryAsOrgMemberCli() {
        String projectId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),"Content-Type", "application/vnd.api+json")
                .body("{\n" +
                        "  \"data\": {\n" +
                        "    \"type\": \"project\",\n" +
                        "    \"attributes\": {\n" +
                        "      \"name\": \"test-sample\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .when()
                .post("/remote/tfe/v2/organizations/simple/projects")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.CREATED.value()).extract().path("data.id");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"),"Content-Type", "application/vnd.api+json")
                .when()
                .get("/remote/tfe/v2/organizations/simple/projects")
                .then()
                .assertThat()
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());

        // Delete only the project created by this test. Using deleteAll() would also remove seeded
        // fixture projects that have workspace FK references, causing a referential integrity violation.
        projectRepository.deleteById(java.util.UUID.fromString(projectId));
    }




    @Test
    void duplicateProjectNameIsRejectedWithConflict() {
        String id = createProject("duplicate-name-check", HttpStatus.CREATED.value());
        try {
            createProject("duplicate-name-check", HttpStatus.CONFLICT.value());
        } finally {
            deleteProject(id);
        }
    }

    @Test
    void oversizedAndBlankProjectNamesAreRejectedAsBadRequests() {
        createProject("x".repeat(257), HttpStatus.BAD_REQUEST.value());
        createProject("   ", HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void projectNameAtTheLimitIsAccepted() {
        deleteProject(createProject("y".repeat(256), HttpStatus.CREATED.value()));
    }

    @Test
    void projectWithWorkspacesCannotBeDeleted() {
        String projectId = createProject("delete-guard-check", HttpStatus.CREATED.value());
        String workspaceName = "delete-guard-" + UUID.randomUUID();
        try {
            Workspace workspace = new Workspace();
            workspace.setName(workspaceName);
            workspace.setSource("empty");
            workspace.setBranch("remote-content");
            workspace.setTerraformVersion("1.14.0");
            workspace.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
            workspace.setProject(projectRepository.findById(UUID.fromString(projectId)).orElseThrow());
            workspaceRepository.save(workspace);

            given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                    .when()
                    .delete("/api/v1/organization/" + ORG + "/project/" + projectId)
                    .then()
                    .statusCode(HttpStatus.CONFLICT.value());
        } finally {
            jdbcTemplate.update("delete from workspace where name = ?", workspaceName);
            deleteProject(projectId);
        }
    }

    private String createProject(String name, int expectedStatus) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"project\",\"attributes\":{\"name\":\"" + name + "\"}}}")
                .when()
                .post("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/project")
                .then()
                .statusCode(expectedStatus)
                .extract()
                .path("data.id");
    }

    private void deleteProject(String id) {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8/project/" + id)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
    }
}
