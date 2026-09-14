package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.Mockito.when;

// Elide returns at most its default page size (500) when a client does not paginate, and the Workspace read
// permission is evaluated in memory so clients cannot paginate explicitly. Organizations with more than 500
// workspaces were silently truncated in both the JSON:API relationship endpoint and the GraphQL query the UI
// uses (issue #3552). Workspace carries @Paginate to raise that ceiling; this test guards it.
class WorkspacePaginationTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final int SEEDED_WORKSPACES = 600;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void listMoreThanFiveHundredWorkspacesViaJsonApiAndGraphQl() {
        List<Workspace> seeded = seedWorkspaces();
        try {
            given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                    .when()
                    .get("/api/v1/organization/" + ORGANIZATION_ID + "/workspace")
                    .then()
                    .assertThat()
                    .log()
                    .ifValidationFails()
                    .statusCode(HttpStatus.OK.value())
                    .body("data.size()", greaterThanOrEqualTo(SEEDED_WORKSPACES));

            String query = "{ organization(ids: [\"" + ORGANIZATION_ID + "\"]) { edges { node { "
                    + "workspace(sort: \"name\") { edges { node { id name } } } } } } }";

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
                    .body("data.organization.edges[0].node.workspace.edges.size()",
                            greaterThanOrEqualTo(SEEDED_WORKSPACES));
        } finally {
            workspaceRepository.deleteAll(seeded);
        }
    }

    @Test
    void listSeededSimpleBigWorkspacesViaJsonApiAndGraphQl() {
        String simpleBigOrgId = "80000000-0000-0000-0000-000000000001";
        int expectedWorkspaces = 1500;

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/organization/" + simpleBigOrgId + "/workspace")
                .then()
                .assertThat()
                .log()
                .ifValidationFails()
                .statusCode(HttpStatus.OK.value())
                .body("data.size()", greaterThanOrEqualTo(expectedWorkspaces));

        String query = "{ organization(ids: [\"" + simpleBigOrgId + "\"]) { edges { node { "
                + "workspace(sort: \"name\") { edges { node { id name } } } } } } }";

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
                .body("data.organization.edges[0].node.workspace.edges.size()",
                        greaterThanOrEqualTo(expectedWorkspaces));
    }

    private List<Workspace> seedWorkspaces() {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).get();
        List<Workspace> workspaces = new ArrayList<>(SEEDED_WORKSPACES);
        for (int i = 0; i < SEEDED_WORKSPACES; i++) {
            Workspace workspace = new Workspace();
            workspace.setName(String.format("pagination-3552-%04d", i));
            workspace.setSource("https://github.com/terrakube-io/terrakube.git");
            workspace.setBranch("main");
            workspace.setIacType("terraform");
            workspace.setTerraformVersion("1.0");
            workspace.setOrganization(organization);
            workspaces.add(workspace);
        }
        return workspaceRepository.saveAll(workspaces);
    }
}
