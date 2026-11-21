package io.terrakube.api.plugin.scheduler.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

        assertThat(job.getDriftResult()).isEqualTo("drift");
        assertThat(workspace.getDriftStatus()).isEqualTo("drifted");
        assertThat(workspace.getDriftFirstSeen()).isEqualTo(job.getCreatedDate());
        assertThat(workspace.getDriftJobId()).isEqualTo(7);
        assertThat(workspace.getDriftLastChecked()).isNotNull();
        verify(workspaceRepository).updateDrift(eq(workspace.getId()), eq("drifted"), eq(job.getCreatedDate()), any(Date.class), eq(7));
        verify(jobRepository).updateDriftResult(7, "drift");
    }

    @Test
    void driftKeepsTheDateItWasFirstSeenWhileItContinues() {
        Job first = check(7, 0, 1, 0);
        service.record(7);
        Date firstSeen = workspace.getDriftFirstSeen();

        check(9, 0, 2, 0);
        service.record(9);

        assertThat(workspace.getDriftFirstSeen()).isEqualTo(firstSeen).isEqualTo(first.getCreatedDate());
        assertThat(workspace.getDriftJobId()).isEqualTo(9);
    }

    @Test
    void aCheckWithNoChangesMakesItCleanAndForgetsWhenDriftStarted() {
        check(7, 0, 1, 0);
        service.record(7);
        Job clean = check(9, 0, 0, 0);

        service.record(9);

        assertThat(clean.getDriftResult()).isEqualTo("clean");
        assertThat(workspace.getDriftStatus()).isEqualTo("clean");
        assertThat(workspace.getDriftFirstSeen()).isNull();
    }

    @Test
    void aNewDriftAfterACleanCheckStartsAgain() {
        check(7, 0, 1, 0);
        service.record(7);
        check(9, 0, 0, 0);
        service.record(9);
        Job again = check(11, 1, 0, 0);

        service.record(11);

        assertThat(workspace.getDriftStatus()).isEqualTo("drifted");
        assertThat(workspace.getDriftFirstSeen()).isEqualTo(again.getCreatedDate());
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
