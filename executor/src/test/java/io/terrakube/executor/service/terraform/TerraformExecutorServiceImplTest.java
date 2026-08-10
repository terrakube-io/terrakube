package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.artifact.ArtifactPackagingService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
    private final ApplyStructuredOutputService applyStructuredOutputService = Mockito.mock(ApplyStructuredOutputService.class);
    private final TerraformOutputsService terraformOutputsService = Mockito.mock(TerraformOutputsService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RedisTemplate redisTemplate = Mockito.mock(RedisTemplate.class);
    private final StreamOperations streamOperations = Mockito.mock(StreamOperations.class);
    private final ArtifactPackagingService artifactPackagingService = Mockito.mock(ArtifactPackagingService.class);

    private TerraformExecutorServiceImpl subject() throws Exception {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.size(anyString())).thenReturn(0L);
        when(terraformState.getBackendStateFile(anyString(), anyString(), any(File.class), anyString())).thenReturn("backend.tfvars");
        when(artifactPackagingService.packageArtifacts(any(TerraformJob.class), any(File.class))).thenReturn(Optional.empty());

        return new TerraformExecutorServiceImpl(
                terraformClient,
                terraformState,
                scriptEngineService,
                logsService,
                planStructuredOutputService,
                applyStructuredOutputService,
                terraformOutputsService,
                objectMapper,
                artifactPackagingService,
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
    void publishesSeededApplyStatusBeforeRunningApply() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();

        Map<String, Object> seededChange = new HashMap<>();
        seededChange.put("address", "aws_instance.example");
        seededChange.put("status", "pending");
        when(applyStructuredOutputService.seedFromPlan("org", "42")).thenReturn(List.of(seededChange));

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.show(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(terraformClient.statePull(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));

        TerraformClient jsonApplyClient = Mockito.mock(TerraformClient.class);
        when(jsonApplyClient.apply(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        doReturn(jsonApplyClient).when(subject).buildJsonEnabledApplyClient();

        subject.apply(terraformJob, tempDir.toFile());

        verify(applyStructuredOutputService, Mockito.atLeastOnce()).publishApplyProgress(
                eq("org"), eq("42"), eq("1"), eq(List.of(seededChange)), any());
    }

    @Test
    void jsonApplyClientMergesErrorStream() throws Exception {
        TerraformClient jsonApplyClient = subject().buildJsonEnabledApplyClient();

        assertTrue(jsonApplyClient.isJsonOutput());
        assertTrue(jsonApplyClient.isRedirectErrorStream());
    }

    @Test
    void jsonPlanClientMergesErrorStream() throws Exception {
        TerraformClient jsonPlanClient = subject().buildJsonEnabledPlanClient();

        assertTrue(jsonPlanClient.isJsonOutput());
        assertTrue(jsonPlanClient.isRedirectErrorStream());
    }

    @Test
    void fallsBackToSharedClientWhenThereIsNoSeedData() throws Exception {
        TerraformExecutorServiceImpl subject = subject();
        TerraformJob terraformJob = createJob();

        when(applyStructuredOutputService.seedFromPlan("org", "42")).thenReturn(List.of());

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.apply(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.show(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(terraformClient.statePull(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));

        ExecutorJobResult result = subject.apply(terraformJob, tempDir.toFile());

        assertTrue(result.isSuccessfulExecution());
        verify(terraformClient).apply(any(TerraformProcessData.class), any(Consumer.class), any());
        verify(applyStructuredOutputService, never()).publishApplyProgress(anyString(), anyString(), anyString(), any(), any());
    }

    // Regression test for a real bug: a fast plan (all json lines emitted well within the 2s
    // progress-flush throttle) that then fails never got a single live structured-output push,
    // live or final - unlike apply's runJsonApply, plan() had no unconditional "flush once more"
    // call after the client returned. That left stale/empty progress data (or nothing at all) in
    // the context, which the UI rendered as a false-positive "no changes needed" instead of
    // surfacing the failure.
    @Test
    void planPublishesFinalStructuredUpdateEvenWhenPlanFails() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        TerraformClient jsonPlanClient = Mockito.mock(TerraformClient.class);
        when(jsonPlanClient.planDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(1));
        doReturn(jsonPlanClient).when(subject).buildJsonEnabledPlanClient();

        subject.plan(terraformJob, tempDir.toFile(), false);

        verify(planStructuredOutputService, Mockito.atLeastOnce()).publishPlanProgress(
                eq("org"), eq("42"), eq("1"), any(), any());
        verify(logsService, Mockito.atLeastOnce()).sendStructuredUpdate(eq(42), eq("1"), anyString());
    }

    @Test
    void planPackagesArtifactsWhenChangesAreFound() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();
        terraformJob.setArtifactPatterns(List.of("build/**"));
        terraformJob.setMultiStepJob(true);

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        TerraformClient jsonPlanClient = Mockito.mock(TerraformClient.class);
        when(jsonPlanClient.planDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(2));
        doReturn(jsonPlanClient).when(subject).buildJsonEnabledPlanClient();

        when(artifactPackagingService.packageArtifacts(eq(terraformJob), any(File.class)))
                .thenReturn(Optional.of("deadbeef"));
        when(terraformState.saveArtifacts(eq("org"), eq("workspace"), eq("42"), eq("1"), any(File.class)))
                .thenReturn("https://example.com/plan-artifacts.tar.gz");

        subject.plan(terraformJob, tempDir.toFile(), false);

        verify(artifactPackagingService).packageArtifacts(eq(terraformJob), any(File.class));
        assertEquals("https://example.com/plan-artifacts.tar.gz", terraformJob.getArtifactsUrl());
        assertEquals("deadbeef", terraformJob.getArtifactsChecksum());
    }

    @Test
    void planDoesNotPackageArtifactsWhenNoChangesFound() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();
        terraformJob.setArtifactPatterns(List.of("build/**"));
        terraformJob.setMultiStepJob(true);

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        TerraformClient jsonPlanClient = Mockito.mock(TerraformClient.class);
        when(jsonPlanClient.planDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(0));
        doReturn(jsonPlanClient).when(subject).buildJsonEnabledPlanClient();

        subject.plan(terraformJob, tempDir.toFile(), false);

        verify(artifactPackagingService, never()).packageArtifacts(any(TerraformJob.class), any(File.class));
    }

    @Test
    void planDoesNotPackageArtifactsForDestroyPlanEvenWithChanges() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();
        terraformJob.setArtifactPatterns(List.of("build/**"));
        terraformJob.setMultiStepJob(true);

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        TerraformClient jsonPlanDestroyClient = Mockito.mock(TerraformClient.class);
        when(jsonPlanDestroyClient.planDestroyDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(2));
        doReturn(jsonPlanDestroyClient).when(subject).buildJsonEnabledPlanClient();

        subject.plan(terraformJob, tempDir.toFile(), true);

        verify(artifactPackagingService, never()).packageArtifacts(any(TerraformJob.class), any(File.class));
    }

    @Test
    void planDoesNotPackageArtifactsForSingleStepPlanOnlyTemplate() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();
        terraformJob.setArtifactPatterns(List.of("build/**"));
        terraformJob.setMultiStepJob(false);

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        TerraformClient jsonPlanClient = Mockito.mock(TerraformClient.class);
        when(jsonPlanClient.planDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(2));
        doReturn(jsonPlanClient).when(subject).buildJsonEnabledPlanClient();

        subject.plan(terraformJob, tempDir.toFile(), false);

        verify(artifactPackagingService, never()).packageArtifacts(any(TerraformJob.class), any(File.class));
    }

    @Test
    void applyFailsBeforeInitWhenArtifactChecksumMismatches() throws Exception {
        TerraformExecutorServiceImpl subject = subject();
        TerraformJob terraformJob = createJob();

        doThrow(new ArtifactVerificationException("checksum mismatch"))
                .when(terraformState).downloadArtifacts(eq("org"), eq("workspace"), eq("42"), eq("1"), any(File.class));

        ExecutorJobResult result = subject.apply(terraformJob, tempDir.toFile());

        assertFalse(result.isSuccessfulExecution());
        assertTrue(result.getOutputErrorLog().contains("Plan artifacts verification failed"));
        verify(terraformClient, never()).init(any(TerraformProcessData.class), any(Consumer.class), any());
    }

    @Test
    void applyProceedsWhenNoArtifactsDeclared() throws Exception {
        TerraformExecutorServiceImpl subject = subject();
        TerraformJob terraformJob = createJob();

        when(terraformState.downloadArtifacts(eq("org"), eq("workspace"), eq("42"), eq("1"), any(File.class)))
                .thenReturn(false);
        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(applyStructuredOutputService.seedFromPlan("org", "42")).thenReturn(List.of());
        when(terraformClient.apply(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.show(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(terraformClient.statePull(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));

        subject.apply(terraformJob, tempDir.toFile());

        verify(terraformState).downloadArtifacts(eq("org"), eq("workspace"), eq("42"), eq("1"), any(File.class));
        verify(terraformClient).init(any(TerraformProcessData.class), any(Consumer.class), any());
    }
}
