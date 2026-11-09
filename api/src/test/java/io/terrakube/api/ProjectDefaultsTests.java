package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.ExecutionMode;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.workspace.Workspace;

// A project's defaults fill in what a new workspace's request left out.
class ProjectDefaultsTests extends ServerApplicationTests {

    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Project project;
    private final List<String> names = new ArrayList<>();

    @BeforeEach
    void seed() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        project = new Project();
        project.setName("defaults-" + UUID.randomUUID());
        project.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        project.setDefaultExecutionMode("local");
        project.setDefaultTerraformVersion("1.9.8");
        project = projectRepository.save(project);
    }

    @AfterEach
    void cleanUp() {
        for (String name : names) {
            jdbcTemplate.update("delete from workspace where name = ?", name);
        }
        jdbcTemplate.update("delete from project where id = ?", project.getId().toString());
    }

    @Test
    void aWorkspaceCreatedWithoutValuesGetsTheProjectDefaults() {
        Workspace created = create("{\"name\":\"%s\",\"source\":\"empty\",\"branch\":\"remote-content\"}");

        assertThat(created.getExecutionMode()).isEqualTo(ExecutionMode.local);
        assertThat(created.getTerraformVersion()).isEqualTo("1.9.8");
    }

    @Test
    void valuesTheRequestSendsAlwaysWin() {
        Workspace created = create(
                "{\"name\":\"%s\",\"source\":\"empty\",\"branch\":\"remote-content\",\"executionMode\":\"remote\",\"terraformVersion\":\"1.14.0\"}");

        assertThat(created.getExecutionMode()).isEqualTo(ExecutionMode.remote);
        assertThat(created.getTerraformVersion()).isEqualTo("1.14.0");
    }

    @Test
    void anUnknownDefaultExecutionModeIsRejected() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"project\",\"id\":\"" + project.getId() + "\",\"attributes\":{\"defaultExecutionMode\":\"sideways\"}}}")
                .when()
                .patch("/api/v1/organization/" + ORG + "/project/" + project.getId())
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    private Workspace create(String attributesTemplate) {
        String name = "defaults-ws-" + UUID.randomUUID();
        names.add(name);
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspace\",\"attributes\":" + String.format(attributesTemplate, name)
                        + ",\"relationships\":{\"project\":{\"data\":{\"type\":\"project\",\"id\":\"" + project.getId() + "\"}}}}}")
                .when()
                .post("/api/v1/organization/" + ORG + "/workspace")
                .then()
                .log().ifValidationFails()
                .statusCode(HttpStatus.CREATED.value());
        return workspaceRepository.findAll().stream().filter(w -> name.equals(w.getName())).findFirst().orElseThrow();
    }
}
