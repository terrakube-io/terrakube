package io.terrakube.executor.service.mode.batch;

import io.terrakube.executor.service.hardened.HardenedRunnerGuard;
import io.terrakube.executor.service.hardened.HardenedRunnerGuardTest;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.service.executor.ExecutorCapacityGate;
import io.terrakube.executor.service.executor.ExecutorJob;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.mode.online.OnlineModeServiceImpl;
import io.terrakube.executor.service.shutdown.ShutdownServiceImpl;
import io.terrakube.executor.service.status.UpdateJobStatus;
import io.terrakube.executor.service.workspace.security.RunnerTokenHolder;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurityImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class HardenedIntakeTest {

    ExecutorFlagsProperties flags = new ExecutorFlagsProperties();
    HardenedRunnerGuard guard = mock(HardenedRunnerGuard.class);
    UpdateJobStatus updateJobStatus = mock(UpdateJobStatus.class);
    BatchModeServiceImpl batch = new BatchModeServiceImpl();

    @BeforeEach
    void setup() {
        batch.executorFlagsProperties = flags;
        batch.hardenedRunnerGuard = guard;
        batch.updateJobStatus = updateJobStatus;
        batch.shutdownService = mock(ShutdownServiceImpl.class);
        RunnerTokenHolder.clear();
    }

    @AfterEach
    void cleanup() {
        RunnerTokenHolder.clear();
    }

    private TerraformJob job(String token) {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org");
        job.setWorkspaceId("ws");
        job.setJobId("1");
        job.setStepId("step");
        job.setRunnerToken(token);
        return job;
    }

    @Test
    void legacyRunnerKeepsWorkingAndRefusesHardenedJobs() {
        assertThat(batch.admit(job(null))).isTrue();
        assertThat(batch.admit(job(HardenedRunnerGuardTest.token(true, "ws", "1")))).isFalse();
        verifyNoInteractions(guard, updateJobStatus);
    }

    @Test
    void hardenedRunnerRunsACleanJobWithItsToken() {
        flags.setHardened(true);
        String token = HardenedRunnerGuardTest.token(true, "ws", "1");
        when(guard.violations(any())).thenReturn(List.of());

        assertThat(batch.admit(job(token))).isTrue();
        assertThat(RunnerTokenHolder.require()).isEqualTo(token);
        assertThat(new WorkspaceSecurityImpl(null, "registry", "https://api", "").generateAccessToken(5)).isEqualTo(token);
    }

    @Test
    void hardenedRunnerRefusesAndReportsViolations() {
        flags.setHardened(true);
        when(guard.violations(any())).thenReturn(List.of("environment variable AWS_ROLE_ARN is set"));

        assertThat(batch.admit(job(HardenedRunnerGuardTest.token(true, "ws", "1")))).isFalse();
        verify(updateJobStatus).setCompletedStatus(eq(false), eq(false), eq(1), any(), eq(""),
                contains("AWS_ROLE_ARN"), isNull(), isNull());
    }

    @Test
    void refusalWithoutTokenIsNotReported() {
        flags.setHardened(true);
        when(guard.violations(any())).thenReturn(List.of("the job carries no runner token"));
        assertThat(batch.admit(job(null))).isFalse();
        verifyNoInteractions(updateJobStatus);
    }

    @Test
    void reportingFailuresDoNotUnblockTheRefusal() {
        flags.setHardened(true);
        when(guard.violations(any())).thenReturn(List.of("x"));
        doThrow(new IllegalStateException("api down")).when(updateJobStatus)
                .setCompletedStatus(anyBoolean(), anyBoolean(), anyInt(), any(), anyString(), anyString(), any(), any());
        assertThat(batch.admit(job(HardenedRunnerGuardTest.token(true, "ws", "1")))).isFalse();
    }

    @Test
    void ephemeralIntakeShutsDownInsteadOfRunningARefusedJob() throws Exception {
        ExecutorJob executorJob = mock(ExecutorJob.class);
        batch.executorJob = executorJob;
        flags.setEphemeral(true);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(job(HardenedRunnerGuardTest.token(true, "ws", "1")));
        flags.setEphemeralJobData(java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        batch.onApplicationEvent(null);
        verifyNoInteractions(executorJob);
        verify(batch.shutdownService).shutdownApplication();

        flags.setEphemeralJobData(java.util.Base64.getEncoder().encodeToString(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(job(null)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        batch.onApplicationEvent(null);
        verify(executorJob).createJob(any());

        flags.setEphemeral(false);
        batch.onApplicationEvent(null);
        verifyNoMoreInteractions(executorJob);
    }

    @Test
    void persistentExecutorRefusesHardenedJobs() {
        ExecutorJob executorJob = mock(ExecutorJob.class);
        OnlineModeServiceImpl online = new OnlineModeServiceImpl(executorJob, new ExecutorCapacityGate(),
                event -> { }, mock(TerrakubeClient.class));
        assertThat(online.terraformJob(job(HardenedRunnerGuardTest.token(true, "ws", "1"))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(executorJob);
    }
}
