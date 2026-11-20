package io.terrakube.api.plugin.scheduler.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.workspace.Workspace;

class DriftCheckServiceTest {

    private static final String PLAN_ONLY = "plan-only-template";

    private JobRepository jobRepository;
    private WorkspaceRepository workspaceRepository;
    private TclService tclService;
    private DriftCheckService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        tclService = mock(TclService.class);
        service = new DriftCheckService(jobRepository, workspaceRepository, tclService);
        when(tclService.isTemplatePlanOnly(PLAN_ONLY)).thenReturn(true);
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("ledger-db");
    }

    private Job check(int id, int add, int change, int destroy) {
        Job job = new Job();
        job.setId(id);
        job.setVia(JobVia.SCHEDULE.getValue());
        job.setTemplateReference(PLAN_ONLY);
        job.setWorkspace(workspace);
        job.setPlanAdd(add);
        job.setPlanChange(change);
        job.setPlanDestroy(destroy);
        job.setCreatedDate(new Date(1_000_000L * id));
        when(jobRepository.findById(id)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    void aScheduledPlanOnlyRunWithChangesMarksTheWorkspaceDrifted() {
        Job job = check(7, 0, 2, 0);

        service.record(7);

        verify(workspaceRepository).updateDrift(eq(workspace.getId()), eq("drifted"), eq(job.getCreatedDate()), any(Date.class), eq(7));
        verify(jobRepository).updateDriftResult(7, "drift");
    }

    @Test
    void theLoadedEntitiesAreLeftUntouchedSoTheCommitCannotWriteWholeRowsBack() {
        Job job = check(7, 0, 2, 0);

        service.record(7);

        assertThat(job.getDriftResult()).isNull();
        assertThat(workspace.getDriftStatus()).isNull();
        assertThat(workspace.getDriftFirstSeen()).isNull();
        assertThat(workspace.getDriftJobId()).isNull();
    }

    @Test
    void driftKeepsTheDateItWasFirstSeenWhileItContinues() {
        Date firstSeen = new Date(5_000L);
        workspace.setDriftStatus("drifted");
        workspace.setDriftFirstSeen(firstSeen);
        check(9, 0, 2, 0);

        service.record(9);

        verify(workspaceRepository).updateDrift(eq(workspace.getId()), eq("drifted"), eq(firstSeen), any(Date.class), eq(9));
    }

    @Test
    void aCheckWithNoChangesMakesItCleanAndForgetsWhenDriftStarted() {
        workspace.setDriftStatus("drifted");
        workspace.setDriftFirstSeen(new Date(5_000L));
        check(9, 0, 0, 0);

        service.record(9);

        verify(workspaceRepository).updateDrift(eq(workspace.getId()), eq("clean"), isNull(), any(Date.class), eq(9));
        verify(jobRepository).updateDriftResult(9, "clean");
    }

    @Test
    void aNewDriftAfterACleanCheckStartsAgain() {
        workspace.setDriftStatus("clean");
        Job again = check(11, 1, 0, 0);

        service.record(11);

        verify(workspaceRepository).updateDrift(eq(workspace.getId()), eq("drifted"), eq(again.getCreatedDate()), any(Date.class), eq(11));
    }

    @Test
    void aRefreshOnlyPlanIsNotACheck() {
        Job job = check(7, 0, 0, 0);
        job.setRefreshOnly(true);

        service.record(7);

        verify(workspaceRepository, never()).updateDrift(any(), any(), any(), any(), any());
        verify(jobRepository, never()).updateDriftResult(any(Integer.class), any());
    }

    @Test
    void aCheckThatANewerOneHasOvertakenIsIgnoredWithoutFailing() {
        check(7, 0, 1, 0);
        when(workspaceRepository.updateDrift(any(), any(), any(), any(), any())).thenReturn(0);

        service.record(7);

        verify(workspaceRepository).updateDrift(eq(workspace.getId()), eq("drifted"), any(Date.class), any(Date.class), eq(7));
    }

    @Test
    void aRunThatIsNotScheduledIsNotACheck() {
        Job job = check(7, 0, 2, 0);
        job.setVia(JobVia.UI.getValue());

        service.record(7);

        assertThat(job.getDriftResult()).isNull();
        assertThat(workspace.getDriftStatus()).isNull();
        verify(workspaceRepository, never()).updateDrift(any(), any(), any(), any(), any());
    }

    @Test
    void aScheduledRunThatCanApplyIsNotACheck() {
        Job job = check(7, 0, 2, 0);
        job.setTemplateReference("plan-and-apply-template");
        when(tclService.isTemplatePlanOnly("plan-and-apply-template")).thenReturn(false);

        service.record(7);

        assertThat(job.getDriftResult()).isNull();
        assertThat(workspace.getDriftStatus()).isNull();
    }

    @Test
    void aRunWithoutPlanCountsLeavesTheWorkspaceAsItWas() {
        Job job = check(7, 0, 0, 0);
        job.setPlanAdd(null);
        workspace.setDriftStatus("drifted");

        service.record(7);

        assertThat(workspace.getDriftStatus()).isEqualTo("drifted");
        verify(workspaceRepository, never()).updateDrift(any(), any(), any(), any(), any());
    }

    @Test
    void aMissingJobOrWorkspaceDoesNothing() {
        service.record(404);
        Job orphan = check(7, 0, 2, 0);
        orphan.setWorkspace(null);

        service.record(7);

        verify(workspaceRepository, never()).updateDrift(any(), any(), any(), any(), any());
    }
}
