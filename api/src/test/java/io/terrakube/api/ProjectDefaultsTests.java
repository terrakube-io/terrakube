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
    private io.terrakube.api.repository.TemplateRepository templateRepository;
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
        project.setDefaultTemplate("11111111-1111-4111-8111-111111111111");
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
        assertThat(created.getDefaultTemplate()).isEqualTo("11111111-1111-4111-8111-111111111111");
    }

    @Test
    void valuesTheRequestSendsAlwaysWin() {
        Workspace created = create(
                "{\"name\":\"%s\",\"source\":\"empty\",\"branch\":\"remote-content\",\"executionMode\":\"remote\",\"terraformVersion\":\"1.14.0\",\"defaultTemplate\":\"22222222-2222-4222-8222-222222222222\"}");

        assertThat(created.getDefaultTemplate()).isEqualTo("22222222-2222-4222-8222-222222222222");
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

    @Test
    void aWorkspaceCreatedThroughTheRemoteApiGetsTheProjectDefaults() {
        Workspace created = createRemote("{\"name\":\"%s\"}");

        assertThat(created.getExecutionMode()).isEqualTo(ExecutionMode.local);
        assertThat(created.getTerraformVersion()).isEqualTo("1.9.8");
    }

    @Test
    void valuesTheRemoteApiRequestSendsStillWinOverTheProject() {
        Workspace created = createRemote("{\"name\":\"%s\",\"terraform-version\":\"1.14.0\",\"execution-mode\":\"remote\"}");

        assertThat(created.getExecutionMode()).isEqualTo(ExecutionMode.remote);
        assertThat(created.getTerraformVersion()).isEqualTo("1.14.0");
    }

    @Test
    void aBlankOrPaddedDefaultTerraformVersionIsRejected() {
        for (String version : new String[] {"   ", " 1.9.8", "1.9.8 "}) {
            given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                    .body("{\"data\":{\"type\":\"project\",\"id\":\"" + project.getId() + "\",\"attributes\":{\"defaultTerraformVersion\":\"" + version + "\"}}}")
                    .when()
                    .patch("/api/v1/organization/" + ORG + "/project/" + project.getId())
                    .then()
                    .statusCode(HttpStatus.BAD_REQUEST.value());
        }
    }

    @Test
    void theDefaultTemplateMustBeATemplateOfTheSameOrganization() {
        io.terrakube.api.rs.template.Template real = new io.terrakube.api.rs.template.Template();
        real.setName("defaults-tpl-" + UUID.randomUUID());
        real.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        real.setTcl("ZmxvdzogW10=");
        real = templateRepository.save(real);
        io.terrakube.api.rs.template.Template foreign = new io.terrakube.api.rs.template.Template();
        foreign.setName("defaults-foreign-" + UUID.randomUUID());
        foreign.setOrganization(organizationRepository.findById(UUID.fromString("3a130a1c-d96f-4f99-83b8-58d472567e3a")).orElseThrow());
        foreign.setTcl("ZmxvdzogW10=");
        foreign = templateRepository.save(foreign);
        try {
            patchDefaultTemplate("\"" + real.getId() + "\"", HttpStatus.NO_CONTENT.value());
            patchDefaultTemplate("\"" + UUID.randomUUID() + "\"", HttpStatus.BAD_REQUEST.value());
            patchDefaultTemplate("\"" + foreign.getId() + "\"", HttpStatus.BAD_REQUEST.value());
            patchDefaultTemplate("\"not-a-template\"", HttpStatus.BAD_REQUEST.value());
            // Blank means no default, so a project can be cleared.
            patchDefaultTemplate("\"  \"", HttpStatus.NO_CONTENT.value());
        } finally {
            jdbcTemplate.update("update project set default_template = null where id = ?", project.getId().toString());
            jdbcTemplate.update("delete from template where id in (?, ?)", real.getId().toString(), foreign.getId().toString());
        }
    }

    private void patchDefaultTemplate(String value, int expectedStatus) {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"project\",\"id\":\"" + project.getId() + "\",\"attributes\":{\"defaultTemplate\":" + value + "}}}")
                .when()
                .patch("/api/v1/organization/" + ORG + "/project/" + project.getId())
                .then()
                .statusCode(expectedStatus);
    }

    private Workspace createRemote(String attributesTemplate) {
        String name = "defaults-remote-" + UUID.randomUUID();
        names.add(name);
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspaces\",\"attributes\":" + String.format(attributesTemplate, name)
                        + ",\"relationships\":{\"project\":{\"data\":{\"type\":\"projects\",\"id\":\"" + project.getId() + "\"}}}}}")
                .when()
                .post("/remote/tfe/v2/organizations/simple/workspaces")
                .then()
                .log().ifValidationFails()
                .statusCode(HttpStatus.CREATED.value());
        return workspaceRepository.findAll().stream().filter(w -> name.equals(w.getName())).findFirst().orElseThrow();
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
