package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.trigger.RunCascadeCoordinatorService;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeStatus;
import io.terrakube.api.rs.job.Job;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real HTTP, same shape as {@code SchedulerReconciliationControllerTest}: the admin gate and the
 * request/response mapping, with {@link RunCascadeCoordinatorService} mocked out - its own
 * behavior is {@code RunCascadeCoordinatorServiceTest}'s job, not this class's.
 */
class RunCascadeOperationsControllerTest extends ServerApplicationTests {

    // "simple" org: TERRAKUBE_DEVELOPERS has manage_job there, AZURE_DEVELOPERS does not.
    private static final UUID SIMPLE_ORG = UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8");

    @MockitoBean
    RunCascadeCoordinatorService coordinatorService;

    @MockitoBean
    io.terrakube.api.repository.RunCascadeRepository cascadeRepository;

    @MockitoBean
    io.terrakube.api.repository.RunCascadeNodeRepository nodeRepository;

    private RunCascade cascade(UUID id, RunCascadeStatus status) {
        RunCascade cascade = new RunCascade();
        cascade.setId(id);
        cascade.setStatus(status);
        return cascade;
    }

    private Job job(int id) {
        Job job = new Job();
        job.setId(id);
        return job;
    }

    @Test
    void cancelRequiresAdminGroup() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().post("/admin/v1/cascades/" + UUID.randomUUID() + "/cancel")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void cancelReturnsTheCascadesNewStatus() {
        UUID cascadeId = UUID.randomUUID();
        when(coordinatorService.cancelCascade(cascadeId)).thenReturn(cascade(cascadeId, RunCascadeStatus.CANCELLED));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/cascades/" + cascadeId + "/cancel")
                .then().statusCode(HttpStatus.OK.value())
                .body("cascadeId", equalTo(cascadeId.toString()))
                .body("status", equalTo("CANCELLED"));
    }

    @Test
    void cancelOfAnUnknownCascadeIs404() {
        UUID cascadeId = UUID.randomUUID();
        when(coordinatorService.cancelCascade(cascadeId))
                .thenThrow(new IllegalArgumentException("No such cascade: " + cascadeId));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/cascades/" + cascadeId + "/cancel")
                .then().statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void retryReturnsTheNewlyDispatchedJobId() {
        UUID nodeId = UUID.randomUUID();
        when(coordinatorService.retryNode(nodeId)).thenReturn(job(42));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/cascades/nodes/" + nodeId + "/retry")
                .then().statusCode(HttpStatus.OK.value())
                .body("nodeId", equalTo(nodeId.toString()))
                .body("status", equalTo("RUNNING"))
                .body("jobId", equalTo(42));
    }

    @Test
    void retryOfANodeInTheWrongStateIsAConflict() {
        UUID nodeId = UUID.randomUUID();
        when(coordinatorService.retryNode(nodeId))
                .thenThrow(new IllegalStateException("Only a failed or skipped node can be retried, not RUNNING"));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/cascades/nodes/" + nodeId + "/retry")
                .then().statusCode(HttpStatus.CONFLICT.value());
    }

    @Test
    void resumeDispatchesWhenReady() {
        UUID nodeId = UUID.randomUUID();
        when(coordinatorService.resumeNode(nodeId)).thenReturn(Optional.of(job(43)));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/cascades/nodes/" + nodeId + "/resume")
                .then().statusCode(HttpStatus.OK.value())
                .body("status", equalTo("RUNNING"))
                .body("jobId", equalTo(43));
    }

    @Test
    void resumeLeavesItPausedWhenStillNotReady() {
        UUID nodeId = UUID.randomUUID();
        when(coordinatorService.resumeNode(nodeId)).thenReturn(Optional.empty());

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/cascades/nodes/" + nodeId + "/resume")
                .then().statusCode(HttpStatus.OK.value())
                .body("status", equalTo("BLOCKED"))
                .body("jobId", equalTo((Object) null));
    }

    @Test
    void aMemberWithManageJobInTheCascadesOrganizationCanOperate() {
        UUID cascadeId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        when(cascadeRepository.findOrganizationId(cascadeId)).thenReturn(Optional.of(SIMPLE_ORG));
        when(nodeRepository.findOrganizationId(nodeId)).thenReturn(Optional.of(SIMPLE_ORG));
        when(coordinatorService.cancelCascade(cascadeId)).thenReturn(cascade(cascadeId, RunCascadeStatus.CANCELLED));
        when(coordinatorService.retryNode(nodeId)).thenReturn(job(44));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().post("/admin/v1/cascades/" + cascadeId + "/cancel")
                .then().statusCode(HttpStatus.OK.value());
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().post("/admin/v1/cascades/nodes/" + nodeId + "/retry")
                .then().statusCode(HttpStatus.OK.value()).body("jobId", equalTo(44));
    }

    @Test
    void aMemberWithoutManageJobInTheOrganizationIsForbidden() {
        UUID cascadeId = UUID.randomUUID();
        when(cascadeRepository.findOrganizationId(cascadeId)).thenReturn(Optional.of(SIMPLE_ORG));

        given().headers("Authorization", "Bearer " + generatePAT("AZURE_DEVELOPERS"))
                .when().post("/admin/v1/cascades/" + cascadeId + "/cancel")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
        verify(coordinatorService, never()).cancelCascade(cascadeId);
    }

    @Test
    void manageJobInAnotherOrganizationDoesNotGrantAccess() {
        UUID cascadeId = UUID.randomUUID();
        when(cascadeRepository.findOrganizationId(cascadeId)).thenReturn(Optional.of(UUID.randomUUID()));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().post("/admin/v1/cascades/" + cascadeId + "/cancel")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
        verify(coordinatorService, never()).cancelCascade(cascadeId);
    }

    @Test
    void anUnknownCascadeIs403ForANonAdminSoIdsCannotBeProbed() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().post("/admin/v1/cascades/" + UUID.randomUUID() + "/cancel")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void capabilitiesAreReportedPerOrganization() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .queryParam("organizationId", SIMPLE_ORG)
                .when().get("/admin/v1/cascades/capabilities")
                .then().statusCode(HttpStatus.OK.value())
                .body("operate", equalTo(true))
                .body("instanceAdmin", equalTo(false));

        given().headers("Authorization", "Bearer " + generatePAT("AZURE_DEVELOPERS"))
                .queryParam("organizationId", SIMPLE_ORG)
                .when().get("/admin/v1/cascades/capabilities")
                .then().statusCode(HttpStatus.OK.value())
                .body("operate", equalTo(false));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .queryParam("organizationId", UUID.randomUUID())
                .when().get("/admin/v1/cascades/capabilities")
                .then().statusCode(HttpStatus.OK.value())
                .body("operate", equalTo(true))
                .body("instanceAdmin", equalTo(true));
    }
}
