package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import io.terrakube.api.rs.project.Project;

// The team that owns a project is a plain name the people who manage the project can set and clear.
class ProjectOwnerTests extends ServerApplicationTests {

    private static final String ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Project project;

    @BeforeEach
    void seed() {
        project = new Project();
        project.setName("owner-" + UUID.randomUUID());
        project.setOrganization(organizationRepository.findById(UUID.fromString(ORG)).orElseThrow());
        project = projectRepository.save(project);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from project where id = ?", project.getId().toString());
    }

    private int patch(String attributes) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"project\",\"id\":\"" + project.getId() + "\",\"attributes\":" + attributes + "}}")
                .when()
                .patch("/api/v1/organization/" + ORG + "/project/" + project.getId())
                .then()
                .extract().statusCode();
    }

    @Test
    void aProjectStartsWithoutAnOwner() {
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getOwnerTeam()).isNull();
    }

    @Test
    void aTeamThatManagesProjectsSetsAndClearsTheOwner() {
        assertThat(patch("{\"ownerTeam\":\"payments-devs\"}")).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getOwnerTeam()).isEqualTo("payments-devs");

        assertThat(patch("{\"ownerTeam\":null}")).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getOwnerTeam()).isNull();
    }

    @Test
    void anOwnerNameOverTheLimitIsRejected() {
        assertThat(patch("{\"ownerTeam\":\"" + "x".repeat(257) + "\"}")).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getOwnerTeam()).isNull();
    }
}
