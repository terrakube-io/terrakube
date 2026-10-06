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
import static org.mockito.Mockito.when;

/**
 * Real HTTP, same shape as {@code SchedulerReconciliationControllerTest}: the admin gate and the
 * request/response mapping, with {@link RunCascadeCoordinatorService} mocked out - its own
 * behavior is {@code RunCascadeCoordinatorServiceTest}'s job, not this class's.
 */
class RunCascadeOperationsControllerTest extends ServerApplicationTests {

    @MockitoBean
    RunCascadeCoordinatorService coordinatorService;

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
    void resumeLeavesItPendingWhenStillNotReady() {
        UUID nodeId = UUID.randomUUID();
        when(coordinatorService.resumeNode(nodeId)).thenReturn(Optional.empty());

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/cascades/nodes/" + nodeId + "/resume")
                .then().statusCode(HttpStatus.OK.value())
                .body("status", equalTo("PENDING"))
                .body("jobId", equalTo((Object) null));
    }
}
