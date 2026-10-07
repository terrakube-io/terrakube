package io.terrakube.api;

import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.Mockito.when;

// Same gap as TagPaginationTests (issue #3609), now on the edge the organization dependency
// graph (#3629) fetches in one request: WorkspaceRunTrigger had no @Paginate, so Elide's 500-row
// framework default silently truncated an organization with more than 500 run triggers, dropping
// real edges from the graph rather than erroring. @Paginate raises that ceiling; this guards it.
class WorkspaceRunTriggerPaginationTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    // 25 workspaces give 25*24 = 600 distinct directed (source, destination) pairs - the unique
    // constraint on workspace_run_trigger is per-pair, so this needs far fewer workspace rows
    // than one trigger per workspace would.
    private static final int WORKSPACE_POOL_SIZE = 25;
    private static final int SEEDED_TRIGGERS = 600;

    @Autowired
    private WorkspaceRunTriggerRepository workspaceRunTriggerRepository;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void listMoreThanFiveHundredRunTriggersForAnOrganization() {
        List<Workspace> workspaces = seedWorkspaces();
        List<WorkspaceRunTrigger> triggers = seedTriggers(workspaces);
        try {
            given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                    .queryParam("filter[runTrigger]", "sourceWorkspace.organization.id==" + ORGANIZATION_ID)
                    .when()
                    .get("/api/v1/runTrigger")
                    .then()
                    .assertThat()
                    .log()
                    .ifValidationFails()
                    .statusCode(HttpStatus.OK.value())
                    .body("data.size()", greaterThanOrEqualTo(SEEDED_TRIGGERS));
        } finally {
            workspaceRunTriggerRepository.deleteAll(triggers);
            workspaceRepository.deleteAll(workspaces);
        }
    }

    private List<Workspace> seedWorkspaces() {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).get();
        List<Workspace> workspaces = new ArrayList<>(WORKSPACE_POOL_SIZE);
        for (int i = 0; i < WORKSPACE_POOL_SIZE; i++) {
            Workspace workspace = new Workspace();
            workspace.setName(String.format("pagination-3629-%04d", i));
            workspace.setOrganization(organization);
            workspace.setSource("https://github.com/example/pagination-3629.git");
            workspace.setBranch("main");
            workspace.setTerraformVersion("1.9.0");
            workspaces.add(workspace);
        }
        return workspaceRepository.saveAll(workspaces);
    }

    /**
     * Written directly via the repository, the same way TagPaginationTests seeds its rows -
     * this bypasses Elide's own cycle/fan-out validation entirely, which is fine here since the
     * point is only to prove the read side returns every row, not to exercise write validation.
     */
    private List<WorkspaceRunTrigger> seedTriggers(List<Workspace> workspaces) {
        List<WorkspaceRunTrigger> triggers = new ArrayList<>(SEEDED_TRIGGERS);
        outer:
        for (Workspace source : workspaces) {
            for (Workspace destination : workspaces) {
                if (source == destination) {
                    continue;
                }
                WorkspaceRunTrigger trigger = new WorkspaceRunTrigger();
                trigger.setSourceWorkspace(source);
                trigger.setDestinationWorkspace(destination);
                triggers.add(trigger);
                if (triggers.size() == SEEDED_TRIGGERS) {
                    break outer;
                }
            }
        }
        return workspaceRunTriggerRepository.saveAll(triggers);
    }
}
