package io.terrakube.api.plugin.cost;

import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.FlowType;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkspaceCostTrackingServiceTest {

    private WorkspaceRepository workspaceRepository;
    private TclService tclService;
    private InfracostContextExtractor contextExtractor;
    private WorkspaceCostTrackingService service;

    @BeforeEach
    void setUp() {
        workspaceRepository = Mockito.mock(WorkspaceRepository.class);
        tclService = Mockito.mock(TclService.class);
        contextExtractor = Mockito.mock(InfracostContextExtractor.class);
        when(workspaceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new WorkspaceCostTrackingService(workspaceRepository, tclService, contextExtractor);
    }

    @Test
    void updatesWorkspaceCostOnCompletedApply() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        Job job = job(101, workspace);
        Step plan = step(100, JobStatus.completed);
        Step apply = step(200, JobStatus.completed);

        when(workspaceRepository.findById(workspace.getId())).thenReturn(Optional.of(workspace));
        when(tclService.getFlowTypeForStep(job, 100)).thenReturn(FlowType.terraformPlan.name());
        when(tclService.getFlowTypeForStep(job, 200)).thenReturn(FlowType.terraformApply.name());
        when(contextExtractor.extract(101)).thenReturn(Optional.of(new InfracostSummary(
                "SUCCESS", "EUR",
                new BigDecimal("245.50"), new BigDecimal("100.00"), new BigDecimal("145.50"),
                4, 4, 0, 0, null, null)));

        service.updateWorkspaceCostOnCompletion(job, List.of(plan, apply));

        assertThat(workspace.getCurrentMonthlyCost()).isEqualByComparingTo("245.50");
        assertThat(workspace.getCostCurrency()).isEqualTo("EUR");
        assertThat(workspace.getCostUpdatedAt()).isNotNull();
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void resetsWorkspaceCostToZeroOnCompletedDestroy() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setCurrentMonthlyCost(new BigDecimal("245.50"));
        workspace.setCostCurrency("USD");
        Job job = job(102, workspace);
        Step destroy = step(100, JobStatus.completed);

        when(workspaceRepository.findById(workspace.getId())).thenReturn(Optional.of(workspace));
        when(tclService.getFlowTypeForStep(job, 100)).thenReturn(FlowType.terraformDestroy.name());

        service.updateWorkspaceCostOnCompletion(job, List.of(destroy));

        assertThat(workspace.getCurrentMonthlyCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(workspace.getCostUpdatedAt()).isNotNull();
        verify(workspaceRepository).save(workspace);
        verifyNoInteractions(contextExtractor);
    }

    @Test
    void ignoresSpeculativePlanOnlyJobs() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setCurrentMonthlyCost(new BigDecimal("99.00"));
        Job job = job(103, workspace);
        Step plan = step(100, JobStatus.completed);

        when(tclService.getFlowTypeForStep(job, 100)).thenReturn(FlowType.terraformPlan.name());

        service.updateWorkspaceCostOnCompletion(job, List.of(plan));

        assertThat(workspace.getCurrentMonthlyCost()).isEqualByComparingTo("99.00");
        verify(workspaceRepository, never()).save(any());
        verifyNoInteractions(contextExtractor);
    }

    @Test
    void doesNotOverwriteWorkspaceCostWhenInfracostErrored() {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setCurrentMonthlyCost(new BigDecimal("120.00"));
        Job job = job(104, workspace);
        Step apply = step(200, JobStatus.completed);

        when(workspaceRepository.findById(workspace.getId())).thenReturn(Optional.of(workspace));
        when(tclService.getFlowTypeForStep(job, 200)).thenReturn(FlowType.terraformApply.name());
        when(contextExtractor.extract(104)).thenReturn(Optional.of(new InfracostSummary(
                "ERROR", "USD", null, null, null, 0, 0, 0, 0, null, "API error")));

        service.updateWorkspaceCostOnCompletion(job, List.of(apply));

        assertThat(workspace.getCurrentMonthlyCost()).isEqualByComparingTo("120.00");
        verify(workspaceRepository, never()).save(any());
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
        step.setStepNumber(number);
        step.setStatus(status);
        return step;
    }
}
