package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.project.Project;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

// Project's @Paginate raises the implicit collection limit to 10,000 without changing the
// in-memory read permissions, which prevent clients from using explicit pagination.
class ProjectPaginationTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final int SEEDED_PROJECTS = 501;

    private List<Project> seeded = new ArrayList<>();

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        List<Project> projects = new ArrayList<>(SEEDED_PROJECTS);
        for (int i = 0; i < SEEDED_PROJECTS; i++) {
            Project project = new Project();
            project.setName(String.format("pagination-3783-%04d", i));
            project.setDescription("Project pagination regression");
            project.setOrganization(organization);
            projects.add(project);
        }
        seeded = projectRepository.saveAll(projects);
    }

    @AfterEach
    void cleanupProjects() {
        projectRepository.deleteAll(seeded);
    }

    @Test
    void listMoreThanFiveHundredProjectsViaJsonApi() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/" + ORGANIZATION_ID + "/project")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", hasItems(seededProjectIds()));
    }

    @Test
    void listMoreThanFiveHundredProjectsViaProjectsPageGraphQl() {
        String query = "{ organization(ids: [\"" + ORGANIZATION_ID + "\"]) { edges { node { "
                + "project { edges { node { id name description } } } } } } }";

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .contentType("application/json")
                .body(Map.of("query", query))
                .when()
                .post("/graphql/api/v1")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .statusCode(HttpStatus.OK.value())
                .body("$", not(hasKey("errors")))
                .body("data.organization.edges[0].node.project.edges.node.id", hasItems(seededProjectIds()));
    }

    private String[] seededProjectIds() {
        return seeded.stream().map(project -> project.getId().toString()).toArray(String[]::new);
    }
}
