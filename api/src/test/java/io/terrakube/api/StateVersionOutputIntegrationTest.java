package io.terrakube.api;

import io.restassured.path.json.JsonPath;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The Terraform and OpenTofu cloud backends list the current outputs of a workspace and then
 * fetch every sensitive one again by its id (#3925).
 */
class StateVersionOutputIntegrationTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String STATE = """
            {
              "version": 4,
              "serial": 3,
              "outputs": {
                "endpoint": {"value": "https://example.com", "type": "string"},
                "replicas": {"value": 3, "type": "number"},
                "db_password": {"value": "s3cr3t", "type": "string", "sensitive": true},
                "zones": {"value": ["a", "b"], "type": ["list", "string"], "sensitive": true}
              },
              "resources": []
            }
            """;

    @MockitoBean
    private StorageTypeService storageTypeService;

    private Workspace workspace;
    private Team team;

    @BeforeEach
    void prepareWorkspace() {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        team = new Team();
        team.setName("outputs-team-" + UUID.randomUUID());
        team.setRole("custom");
        team.setManageState(true);
        team.setOrganization(organization);
        team = teamRepository.saveAndFlush(team);

        workspace = new Workspace();
        workspace.setName("outputs-workspace-" + UUID.randomUUID());
        workspace.setSource("https://github.com/terrakube-io/terrakube.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.13.1");
        workspace.setOrganization(organization);
        workspace = workspaceRepository.saveAndFlush(workspace);

        when(storageTypeService.getCurrentTerraformState(ORGANIZATION_ID, workspace.getId().toString()))
                .thenReturn(STATE.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void cleanUp() {
        if (workspace != null) workspaceRepository.delete(workspace);
        if (team != null) teamRepository.delete(team);
    }

    @Test
    void sensitiveOutputsListedForAWorkspaceCanBeReadByTheirId() {
        String token = generatePAT(team.getName());
        JsonPath outputs = given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/remote/tfe/v2/workspaces/" + workspace.getId() + "/current-state-version-outputs")
                .then()
                .statusCode(200)
                .body("data.size()", equalTo(4))
                .body("data.find { it.attributes.name == 'replicas' }.attributes.'detailed-type'", equalTo("number"))
                .body("data.find { it.attributes.name == 'zones' }.attributes.'detailed-type'", contains("list", "string"))
                .extract().jsonPath();

        // go-tfe refuses to request an output whose id does not match this pattern
        outputs.getList("data.id", String.class)
                .forEach(id -> assertTrue(id.matches("^[a-zA-Z0-9\\-._]+$"), id));

        String passwordId = outputs.getString("data.find { it.attributes.name == 'db_password' }.id");
        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/remote/tfe/v2/state-version-outputs/" + passwordId)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(passwordId))
                .body("data.type", equalTo("state-version-outputs"))
                .body("data.attributes.name", equalTo("db_password"))
                .body("data.attributes.sensitive", equalTo(true))
                .body("data.attributes.value", equalTo("s3cr3t"))
                .body("data.attributes.'detailed-type'", equalTo("string"));
    }

    @Test
    void readingAnOutputRequiresPermissionToReadTheWorkspaceState() {
        String outputId = given()
                .header("Authorization", "Bearer " + generatePAT(team.getName()))
                .get("/remote/tfe/v2/workspaces/" + workspace.getId() + "/current-state-version-outputs")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("data[0].id");

        team.setManageState(false);
        teamRepository.saveAndFlush(team);

        given()
                .header("Authorization", "Bearer " + generatePAT(team.getName()))
                .when()
                .get("/remote/tfe/v2/state-version-outputs/" + outputId)
                .then()
                .statusCode(403)
                .body("errors[0].status", equalTo("403"));

        given()
                .header("Authorization", "Bearer " + generatePAT("INVALID_GROUP"))
                .when()
                .get("/remote/tfe/v2/state-version-outputs/" + outputId)
                .then()
                .statusCode(403);
    }

    @Test
    void unknownOutputIdsAreNotFound() {
        String token = generatePAT(team.getName());
        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/remote/tfe/v2/state-version-outputs/" + UUID.randomUUID())
                .then()
                .statusCode(404);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/remote/tfe/v2/state-version-outputs/wsout-not_base64!")
                .then()
                .statusCode(404);
    }
}
