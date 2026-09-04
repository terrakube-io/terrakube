package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.reconciliation.DerivedOutcome;
import io.terrakube.api.plugin.scheduler.reconciliation.JobReconciliationService;
import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationResult;
import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationResult.ReconciliationDisposition;
import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerReconciliationControllerTest extends ServerApplicationTests {

    @MockitoBean
    JobReconciliationService reconciliationService;

    private ReconciliationResult dryRun(int jobId, DerivedOutcome outcome, JobStatus target) {
        return new ReconciliationResult(jobId, JobStatus.approved, outcome, target,
                outcome.isTerminalTransition() ? ReconciliationDisposition.DRY_RUN
                        : ReconciliationDisposition.HELD_ANOMALY,
                List.of());
    }

    @Test
    void reportRequiresAdminGroup() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().get("/admin/v1/scheduler/reconciliation")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void reportReturnsStuckJobsForAdmin() {
        when(reconciliationService.report())
                .thenReturn(List.of(dryRun(755, DerivedOutcome.COMPLETED, JobStatus.completed)));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().get("/admin/v1/scheduler/reconciliation")
                .then().statusCode(HttpStatus.OK.value())
                .body("[0].jobId", equalTo(755))
                .body("[0].targetStatus", equalTo("completed"));
    }

    @Test
    void applyWithoutConfirmIsRejected() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/json").body("{\"confirm\":false,\"jobIds\":[755]}")
                .when().post("/admin/v1/scheduler/reconciliation")
                .then().statusCode(HttpStatus.BAD_REQUEST.value());
        verify(reconciliationService, never()).reconcile(anyInt(), anyBoolean());
    }

    @Test
    void applyRunsOnlyDeterministicTargetsAndSkipsAnomalies() {
        when(reconciliationService.report()).thenReturn(List.of(
                dryRun(755, DerivedOutcome.COMPLETED, JobStatus.completed),
                dryRun(756, DerivedOutcome.ANOMALY, null)));
        when(reconciliationService.reconcile(755, false)).thenReturn(new ReconciliationResult(
                755, JobStatus.approved, DerivedOutcome.COMPLETED, JobStatus.completed,
                ReconciliationDisposition.APPLIED, List.of()));

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/json").body("{\"confirm\":true,\"jobIds\":[755,756]}")
                .when().post("/admin/v1/scheduler/reconciliation")
                .then().statusCode(HttpStatus.OK.value())
                .body("size()", equalTo(1))
                .body("[0].jobId", equalTo(755));

        verify(reconciliationService).reconcile(755, false);
        verify(reconciliationService, never()).reconcile(eq(756), anyBoolean());
    }
}
