package io.terrakube.api;

import io.terrakube.api.plugin.cost.WorkspaceCostWriter;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only super-service tokens (reconciliation / system updates) may set workspace cost tracking fields.
 * Workspace managers must be rejected on both CREATE and UPDATE so cost columns and rollups cannot be spoofed.
 */
class WorkspaceCostPermissionTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String TEAM_ID = "58529721-425e-44d7-8b0d-1d515043c2f7";

    private String previousRole;
    private boolean previousManageWorkspace;
    private UUID createdWorkspaceId;

    @Autowired
    private WorkspaceCostWriter workspaceCostWriter;

    @BeforeEach
    void grantManageWorkspaceToDevelopers() {
        Team team = teamRepository.findById(UUID.fromString(TEAM_ID)).orElseThrow();
        previousRole = team.getRole();
        previousManageWorkspace = team.isManageWorkspace();
        team.setRole("admin");
        team.setManageWorkspace(true);
        teamRepository.save(team);
    }

    @AfterEach
    void restoreTeamAndCleanUp() {
        if (createdWorkspaceId != null) {
            workspaceRepository.deleteById(createdWorkspaceId);
            createdWorkspaceId = null;
        }
        Team team = teamRepository.findById(UUID.fromString(TEAM_ID)).orElseThrow();
        team.setRole(previousRole);
        team.setManageWorkspace(previousManageWorkspace);
        teamRepository.save(team);
    }

    @Test
    void workspaceCreatorCannotSetCostFieldsOnCreateOrUpdate() {
        String token = generatePAT("TERRAKUBE_DEVELOPERS");

        for (String extraAttr : new String[]{
                "\"currentMonthlyCost\": 999.99",
                "\"costCurrency\": \"EUR\"",
                "\"costUpdatedAt\": \"2026-01-01T00:00:00Z\""
        }) {
            String workspaceName = "spoof-cost-" + UUID.randomUUID();
            int createStatus = given()
                    .headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                    .body("""
                            {
                              "data": {
                                "type": "workspace",
                                "attributes": {
                                  "name": "%s",
                                  "source": "https://github.com/AzBuilder/terraform-sample-repository.git",
                                  "branch": "main",
                                  "terraformVersion": "1.5.7",
                                  %s
                                }
                              }
                            }
                            """.formatted(workspaceName, extraAttr))
                    .when()
                    .post("/api/v1/organization/" + ORGANIZATION_ID + "/workspace")
                    .then()
                    .extract().statusCode();

            assertThat(createStatus).as("POST with %s", extraAttr).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(workspaceRepository.getByOrganizationNameAndName("simple", workspaceName)).isNull();
        }

        int patchStatus = given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"workspace\",\"id\":\"" + WORKSPACE_ID
                        + "\",\"attributes\":{\"currentMonthlyCost\":123.45}}}")
                .when()
                .patch("/api/v1/organization/" + ORGANIZATION_ID + "/workspace/" + WORKSPACE_ID)
                .then()
                .extract().statusCode();
        assertThat(patchStatus).isEqualTo(HttpStatus.FORBIDDEN.value());

        Workspace ws = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
        assertThat(ws.getCurrentMonthlyCost()).isNull();
    }

    @Test
    void workspaceCreatorCanStillCreateWorkspaceWithoutCostFields() {
        String token = generatePAT("TERRAKUBE_DEVELOPERS");
        String workspaceName = "valid-cost-perm-" + UUID.randomUUID();

        var response = given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/vnd.api+json")
                .body("""
                        {
                          "data": {
                            "type": "workspace",
                            "attributes": {
                              "name": "%s",
                              "source": "https://github.com/AzBuilder/terraform-sample-repository.git",
                              "branch": "main",
                              "terraformVersion": "1.5.7"
                            }
                          }
                        }
                        """.formatted(workspaceName))
                .when()
                .post("/api/v1/organization/" + ORGANIZATION_ID + "/workspace")
                .then()
                .extract();

        assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        createdWorkspaceId = UUID.fromString(response.jsonPath().getString("data.id"));
    }

    @Test
    void staleWorkspaceEntitySaveDoesNotOverwriteUpdatedCostColumns() {
        UUID wsId = UUID.fromString(WORKSPACE_ID);
        Workspace staleWorkspace = workspaceRepository.findById(wsId).orElseThrow();
        boolean originalLocked = staleWorkspace.isLocked();
        Date updatedAt = new Date();

        try {
            workspaceCostWriter.writeCost(wsId, new BigDecimal("314.1500"), "EUR", updatedAt);

            staleWorkspace.setLocked(!originalLocked);
            workspaceRepository.save(staleWorkspace);

            Workspace reloaded = workspaceRepository.findById(wsId).orElseThrow();
            assertThat(reloaded.isLocked()).isEqualTo(!originalLocked);
            assertThat(reloaded.getCurrentMonthlyCost()).isEqualByComparingTo(new BigDecimal("314.1500"));
            assertThat(reloaded.getCostCurrency()).isEqualTo("EUR");
            assertThat(reloaded.getCostUpdatedAt()).isNotNull();
        } finally {
            staleWorkspace.setLocked(originalLocked);
            workspaceRepository.save(staleWorkspace);
            workspaceCostWriter.writeCost(wsId, null, "USD", null);
        }
    }
}
