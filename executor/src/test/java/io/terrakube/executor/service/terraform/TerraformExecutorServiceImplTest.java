package io.terrakube.executor.service.terraform;

import io.terrakube.executor.plugin.tfstate.StateUploadFailedException;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.executor.ExecutorJobResult;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.terraform.TerraformClient;
import io.terrakube.terraform.TerraformProcessData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerraformExecutorServiceImplTest {

    @TempDir
    Path tempDir;

    private final TerraformClient terraformClient = Mockito.mock(TerraformClient.class);
    private final TerraformState terraformState = Mockito.mock(TerraformState.class);
    private final ScriptEngineService scriptEngineService = Mockito.mock(ScriptEngineService.class);
    private final ProcessLogs logsService = Mockito.mock(ProcessLogs.class);
    private final PlanStructuredOutputService planStructuredOutputService = Mockito.mock(PlanStructuredOutputService.class);
    private final RedisTemplate redisTemplate = Mockito.mock(RedisTemplate.class);
    private final StreamOperations streamOperations = Mockito.mock(StreamOperations.class);

    private TerraformExecutorServiceImpl subject() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.size(anyString())).thenReturn(0L);
        when(terraformState.getBackendStateFile(anyString(), anyString(), any(File.class), anyString())).thenReturn("backend.tfvars");

        return new TerraformExecutorServiceImpl(
                terraformClient,
                terraformState,
                scriptEngineService,
                logsService,
                planStructuredOutputService,
                false,
                redisTemplate,
                1);
    }

    private TerraformJob createJob() {
        TerraformJob terraformJob = new TerraformJob();
        terraformJob.setJobId("42");
        terraformJob.setStepId("1");
        terraformJob.setOrganizationId("org");
        terraformJob.setWorkspaceId("workspace");
        terraformJob.setTerraformVersion("1.9.0");
        terraformJob.setBranch("remote-content");
        terraformJob.setFolder("/");
        terraformJob.setVcsType("GIT");
        terraformJob.setShowHeader(true);
        terraformJob.setEnvironmentVariables(new HashMap<>());
        terraformJob.setVariables(new HashMap<>());
        return terraformJob;
    }

    @Test
    void planStopsWhenTerraformInitFails() throws Exception {
        TerraformExecutorServiceImpl subject = subject();
        TerraformJob terraformJob = createJob();

        when(terraformClient.init(
                any(TerraformProcessData.class),
                any(Consumer.class),
                any())).thenReturn(CompletableFuture.completedFuture(false));

        ExecutorJobResult result = subject.plan(terraformJob, tempDir.toFile(), false);

        assertFalse(result.isSuccessfulExecution());
        assertEquals(1, result.getExitCode());
        verify(terraformClient, never()).planDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any());
    }

    @Test
    void planPublishesTerraformInitStderrToJobOutput() throws Exception {
        TerraformExecutorServiceImpl subject = subject();
        TerraformJob terraformJob = createJob();

        when(terraformClient.init(
                any(TerraformProcessData.class),
                any(Consumer.class),
                any())).thenAnswer(invocation -> {
                    Consumer<String> errorOutput = invocation.getArgument(2);
                    errorOutput.accept("init stderr");
                    return CompletableFuture.completedFuture(false);
                });

        ExecutorJobResult result = subject.plan(terraformJob, tempDir.toFile(), false);

        assertTrue(result.getOutputLog().contains("init stderr"));
    }

    @Test
    void planSurfacesStateUploadFailureFromSaveTerraformPlan() throws Exception {
        TerraformExecutorServiceImpl subject = subject();
        TerraformJob terraformJob = createJob();

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        // exit 2 = plan succeeded with changes, so executionPlan is true and saveTerraformPlan is called
        when(terraformClient.planDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(2));
        when(terraformState.saveTerraformPlan(anyString(), anyString(), anyString(), anyString(), any(File.class)))
                .thenThrow(new StateUploadFailedException("API rejected upload: HTTP 500"));

        ExecutorJobResult result = subject.plan(terraformJob, tempDir.toFile(), false);

        assertFalse(result.isSuccessfulExecution());
        assertEquals(1, result.getExitCode());
        // The upload failure is appended to the error buffer (and surfaced via
        // surfaceUploadFailure -> setOutputErrorLog) so the failed job carries
        // the user-facing reason. The success-path outputLog snapshot was taken
        // before the catch block, so we don't assert on it here.
        assertTrue(result.getOutputErrorLog().contains("API rejected upload: HTTP 500"),
                "Expected error log to include the upload failure reason, got: " + result.getOutputErrorLog());
    }
}
