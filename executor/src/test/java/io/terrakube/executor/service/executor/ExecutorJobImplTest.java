package io.terrakube.executor.service.executor;

import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.shutdown.ShutdownServiceImpl;
import io.terrakube.executor.service.status.UpdateJobStatus;
import io.terrakube.executor.service.terraform.TerraformExecutor;
import io.terrakube.executor.service.workspace.SetupWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ExecutorJobImplTest {

    @TempDir
    Path tempDir;

    private final SetupWorkspace setupWorkspace = Mockito.mock(SetupWorkspace.class);
    private final TerraformExecutor terraformExecutor = Mockito.mock(TerraformExecutor.class);
    private final UpdateJobStatus updateJobStatus = Mockito.mock(UpdateJobStatus.class);
    private final ExecutorFlagsProperties executorFlagsProperties = new ExecutorFlagsProperties();
    private final ShutdownServiceImpl shutdownService = Mockito.mock(ShutdownServiceImpl.class);
    private final ScriptEngineService scriptEngineService = Mockito.mock(ScriptEngineService.class);
    private final JobExecutionWatchdog jobExecutionWatchdog = Mockito.mock(JobExecutionWatchdog.class);
    private final List<Object> publishedEvents = new ArrayList<>();
    private final ApplicationEventPublisher eventPublisher = publishedEvents::add;

    private ExecutorJobImpl subject(ApplicationEventPublisher publisher) {
        return new ExecutorJobImpl(setupWorkspace, terraformExecutor, updateJobStatus, executorFlagsProperties,
                shutdownService, scriptEngineService, publisher, jobExecutionWatchdog);
    }

    private TerraformJob createJob(String type) {
        TerraformJob job = new TerraformJob();
        job.setJobId("42");
        job.setStepId("1");
        job.setOrganizationId("org");
        job.setWorkspaceId("workspace");
        job.setType(type);
        // Skips commitHash.info lookup so the test doesn't need a real git working dir.
        job.setBranch("remote-content");
        job.setEnvironmentVariables(new HashMap<>());
        job.setVariables(new HashMap<>());
        return job;
    }

    @SuppressWarnings("unchecked")
    private ReadinessState stateAt(int index) {
        return ((AvailabilityChangeEvent<ReadinessState>) publishedEvents.get(index)).getState();
    }

    @Test
    void togglesReadinessAndWatchdogAroundASuccessfulJob() throws Exception {
        TerraformJob job = createJob("terraformPlan");
        File workDir = tempDir.toFile();
        when(setupWorkspace.prepareWorkspace(job)).thenReturn(workDir);
        ExecutorJobResult result = new ExecutorJobResult();
        result.setSuccessfulExecution(true);
        when(terraformExecutor.plan(eq(job), eq(workDir), eq(false))).thenReturn(result);

        subject(eventPublisher).createJob(job);

        assertEquals(2, publishedEvents.size());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, stateAt(0));
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, stateAt(1));

        InOrder inOrder = inOrder(jobExecutionWatchdog, updateJobStatus);
        inOrder.verify(jobExecutionWatchdog).markBusy();
        inOrder.verify(updateJobStatus).setCompletedStatus(eq(true), anyBoolean(), anyInt(), eq(job), any(), any(), any(), any());
        inOrder.verify(jobExecutionWatchdog).markFree();
    }

    @Test
    void doesNotRestoreReadinessInEphemeralModeSinceThePodIsShuttingDownAnyway() throws Exception {
        executorFlagsProperties.setEphemeral(true);
        TerraformJob job = createJob("terraformPlan");
        File workDir = tempDir.toFile();
        when(setupWorkspace.prepareWorkspace(job)).thenReturn(workDir);
        when(terraformExecutor.plan(any(), any(), anyBoolean())).thenReturn(new ExecutorJobResult());

        subject(eventPublisher).createJob(job);

        assertEquals(1, publishedEvents.size());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, stateAt(0));
        verify(jobExecutionWatchdog).markFree();
        verify(shutdownService).shutdownApplication();
    }

    @Test
    void aReadinessListenerThrowingNeverBlocksJobExecutionOrLeavesThePodMarkedBusy() throws Exception {
        ApplicationEventPublisher throwingPublisher = event -> {
            throw new RuntimeException("listener exploded");
        };
        TerraformJob job = createJob("terraformPlan");
        File workDir = tempDir.toFile();
        when(setupWorkspace.prepareWorkspace(job)).thenReturn(workDir);
        ExecutorJobResult result = new ExecutorJobResult();
        result.setSuccessfulExecution(true);
        when(terraformExecutor.plan(any(), any(), anyBoolean())).thenReturn(result);

        subject(throwingPublisher).createJob(job);

        verify(jobExecutionWatchdog).markBusy();
        verify(jobExecutionWatchdog).markFree();
        verify(updateJobStatus).setCompletedStatus(eq(true), anyBoolean(), anyInt(), eq(job), any(), any(), any(), any());
    }

    @Test
    void marksBusyBeforeWorkspaceSetupSoAFailureStillCountsAsBusyThenFree() throws Exception {
        TerraformJob job = createJob("terraformPlan");
        when(setupWorkspace.prepareWorkspace(job)).thenThrow(new io.terrakube.executor.service.workspace.WorkspaceException(new RuntimeException("boom")));

        subject(eventPublisher).createJob(job);

        assertEquals(2, publishedEvents.size());
        InOrder inOrder = inOrder(jobExecutionWatchdog, updateJobStatus);
        inOrder.verify(jobExecutionWatchdog).markBusy();
        inOrder.verify(updateJobStatus).setCompletedStatus(eq(false), eq(false), eq(-1), eq(job), any(), any(), any(), any());
        inOrder.verify(jobExecutionWatchdog).markFree();
        verifyNoMoreInteractions(terraformExecutor);
    }

    @Test
    void marksJobFailedInsteadOfHangingWhenTerraformExecutorThrowsUnexpectedly() throws Exception {
        // Regression test: an unexpected exception from deep inside the terraform executor (e.g.
        // the terraform/tofu binary download failing) used to propagate out of this @Async method
        // uncaught - Spring's SimpleAsyncUncaughtExceptionHandler just logs it, and the job was
        // left stuck "running" forever with nothing to retry it.
        TerraformJob job = createJob("terraformPlan");
        File workDir = tempDir.toFile();
        when(setupWorkspace.prepareWorkspace(job)).thenReturn(workDir);
        when(terraformExecutor.plan(any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("406 Not Acceptable from GET https://releases.hashicorp.com/terraform/index.json"));

        subject(eventPublisher).createJob(job);

        assertEquals(2, publishedEvents.size());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, stateAt(0));
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, stateAt(1));

        InOrder inOrder = inOrder(jobExecutionWatchdog, updateJobStatus);
        inOrder.verify(jobExecutionWatchdog).markBusy();
        inOrder.verify(updateJobStatus).setCompletedStatus(eq(false), eq(false), eq(-1), eq(job), any(), any(), any(), any());
        inOrder.verify(jobExecutionWatchdog).markFree();
    }
}
