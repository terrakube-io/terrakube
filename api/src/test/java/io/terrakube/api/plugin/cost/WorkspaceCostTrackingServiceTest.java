package io.terrakube.api.plugin.cost;

import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkspaceCostTrackingServiceTest {

    private WorkspaceCostWriter workspaceCostWriter;
    private TclService tclService;
    private InfracostContextExtractor contextExtractor;
    private WorkspaceCostTrackingService service;

    @BeforeEach
    void setUp() {
        workspaceCostWriter = mock(WorkspaceCostWriter.class);
        tclService = mock(TclService.class);
        contextExtractor = mock(InfracostContextExtractor.class);
        service = new WorkspaceCostTrackingService(workspaceCostWriter, tclService, contextExtractor);
    }

    @Test
    void updatesWorkspaceCostOnCompletedApply() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Job job = job(101, workspace);
        Step plan = step(100, JobStatus.completed);
        Step apply = step(200, JobStatus.completed);
        List<Step> steps = List.of(plan, apply);

        when(tclService.getFlowTypeForStep(job, 100)).thenReturn(FlowType.terraformPlan.name());
        when(tclService.getFlowTypeForStep(job, 200)).thenReturn(FlowType.terraformApply.name());
        when(contextExtractor.extract(101, steps)).thenReturn(Optional.of(new InfracostSummary(
                "SUCCESS", "eur",
                new BigDecimal("245.50"), new BigDecimal("100.00"), new BigDecimal("145.50"),
                4, 4, 0, 0, null, null)));

        service.updateWorkspaceCostOnCompletion(job, steps);

        verify(workspaceCostWriter).writeCost(eq(workspace.getId()), eq(new BigDecimal("245.5000")), eq("EUR"), any(Date.class));
    }

    @Test
    void resetsWorkspaceCostToZeroOnCompletedDestroy() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setCurrentMonthlyCost(new BigDecimal("245.50"));
        workspace.setCostCurrency("USD");
        Job job = job(102, workspace);
        Step destroy = step(100, JobStatus.completed);

        when(tclService.getFlowTypeForStep(job, 100)).thenReturn(FlowType.terraformDestroy.name());

        service.updateWorkspaceCostOnCompletion(job, List.of(destroy));

        verify(workspaceCostWriter).writeCost(eq(workspace.getId()), eq(BigDecimal.ZERO), eq("USD"), any(Date.class));
        verifyNoInteractions(contextExtractor);
    }

    @Test
    void resetsWorkspaceCostToZeroOnCompletedPlanDestroyThenApplyEvenWithoutInfracost() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setCurrentMonthlyCost(new BigDecimal("199.00"));
        workspace.setCostCurrency("GBP");
        Job job = job(105, workspace);
        Step planDestroy = step(100, JobStatus.completed);
        Step apply = step(200, JobStatus.completed);

        when(tclService.getFlowTypeForStep(job, 100)).thenReturn(FlowType.terraformPlanDestroy.name());
        when(tclService.getFlowTypeForStep(job, 200)).thenReturn(FlowType.terraformApply.name());

        service.updateWorkspaceCostOnCompletion(job, List.of(planDestroy, apply));

        verify(workspaceCostWriter).writeCost(eq(workspace.getId()), eq(BigDecimal.ZERO), eq("GBP"), any(Date.class));
        verifyNoInteractions(contextExtractor);
    }

    @Test
    void ignoresPlanDestroyWithoutApplyAndSpeculativePlanOnlyJobs() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Job job = job(103, workspace);
        Step plan = step(100, JobStatus.completed);
        Step planDestroyOnly = step(200, JobStatus.completed);

        when(tclService.getFlowTypeForStep(job, 100)).thenReturn(FlowType.terraformPlan.name());
        when(tclService.getFlowTypeForStep(job, 200)).thenReturn(FlowType.terraformPlanDestroy.name());

        service.updateWorkspaceCostOnCompletion(job, List.of(plan));
        service.updateWorkspaceCostOnCompletion(job, List.of(planDestroyOnly));

        verifyNoInteractions(workspaceCostWriter);
        verifyNoInteractions(contextExtractor);
    }

    @Test
    void doesNotOverwriteWorkspaceCostWhenInfracostErrored() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Job job = job(104, workspace);
        Step apply = step(200, JobStatus.completed);
        List<Step> steps = List.of(apply);

        when(tclService.getFlowTypeForStep(job, 200)).thenReturn(FlowType.terraformApply.name());
        when(contextExtractor.extract(104, steps)).thenReturn(Optional.of(new InfracostSummary(
                "ERROR", "USD", null, null, null, 0, 0, 0, 0, null, "API error")));

        service.updateWorkspaceCostOnCompletion(job, steps);

        verifyNoInteractions(workspaceCostWriter);
    }

    @Test
    void ignoresOutOfRangeCostAndNormalizesInvalidCurrency() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Job job = job(106, workspace);
        Step apply = step(200, JobStatus.completed);
        List<Step> steps = List.of(apply);

        when(tclService.getFlowTypeForStep(job, 200)).thenReturn(FlowType.terraformApply.name());
        when(contextExtractor.extract(106, steps)).thenReturn(Optional.of(new InfracostSummary(
                "SUCCESS", "SUPERLONGCURRENCY",
                new BigDecimal("9999999999999999.00"), null, null, 1, 1, 0, 0, null, null)));

        service.updateWorkspaceCostOnCompletion(job, steps);

        verifyNoInteractions(workspaceCostWriter);

        when(contextExtractor.extract(106, steps)).thenReturn(Optional.of(new InfracostSummary(
                "SUCCESS", "SUPERLONGCURRENCY",
                new BigDecimal("12.34567"), null, null, 1, 1, 0, 0, null, null)));

        service.updateWorkspaceCostOnCompletion(job, steps);

        verify(workspaceCostWriter).writeCost(eq(workspace.getId()), eq(new BigDecimal("12.3457")), eq("USD"), any(Date.class));
    }

    @Test
    void catchesPersistenceFailureWithoutPropagating() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Job job = job(107, workspace);
        Step destroy = step(100, JobStatus.completed);

        when(tclService.getFlowTypeForStep(job, 100)).thenReturn(FlowType.terraformDestroy.name());
        doThrow(new RuntimeException("DB constraint failure"))
                .when(workspaceCostWriter).writeCost(any(), any(), any(), any());

        service.updateWorkspaceCostOnCompletion(job, List.of(destroy));

        verify(workspaceCostWriter).writeCost(eq(workspace.getId()), eq(BigDecimal.ZERO), eq("USD"), any(Date.class));
        verify(workspaceCostWriter, never()).writeCost(any(), eq(BigDecimal.ONE), any(), any());
    }

    private static Job job(int id, Workspace workspace) {
        Job job = new Job();
        job.setId(id);
        job.setWorkspace(workspace);
        job.setTcl("ZmxvdzoK");
        return job;
    }

    private static Step step(int number, JobStatus status) {
        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setStepNumber(number);
        step.setStatus(status);
        return step;
    }
}
