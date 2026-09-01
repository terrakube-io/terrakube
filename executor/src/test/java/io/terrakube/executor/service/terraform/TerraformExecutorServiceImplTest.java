package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.StructuredOutputProperties;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.executor.ExecutorJobResult;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.terraform.structured.StructuredOutputPersistenceQueue;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
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
    private final StructuredOutputPersistenceQueue structuredOutputPersistenceQueue = Mockito.mock(StructuredOutputPersistenceQueue.class);
    private final ExecutorFlagsProperties executorFlagsProperties = new ExecutorFlagsProperties();
    private final StructuredOutputProperties structuredOutputProperties = new StructuredOutputProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private TerraformExecutorServiceImpl subject() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.size(anyString())).thenReturn(0L);
        when(terraformState.getBackendStateFile(anyString(), anyString(), any(File.class), anyString())).thenReturn("backend.tfvars");
        when(structuredOutputPersistenceQueue.awaitDrain(any())).thenReturn(true);
        structuredOutputProperties.setDrainTimeoutMs(1000);

        return new TerraformExecutorServiceImpl(
                terraformClient,
                terraformState,
                scriptEngineService,
                logsService,
                planStructuredOutputService,
                applyStructuredOutputService,
                terraformOutputsService,
                objectMapper,
                false,
                redisTemplate,
                1,
                structuredOutputPersistenceQueue,
                executorFlagsProperties,
                structuredOutputProperties,
                meterRegistry);
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

    // apply -json's event stream only ever forwards terse per-resource one-liners plus the final
    // change-summary line - unlike plan(), which appends getPlanAsHumanText's rendered diff to
    // the console, apply had nothing resembling a `terraform show`/CLI-style closing readout.
    // appendHumanReadableOutputs should append an Outputs: section mirroring what the real CLI
    // prints after a successful apply.
    @Test
    void appendsHumanReadableOutputsAfterSuccessfulApply() throws Exception {
        TerraformExecutorServiceImpl subject = subject();
        TerraformJob terraformJob = createJob();

        when(applyStructuredOutputService.seedFromPlan("org", "42")).thenReturn(List.of());

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.apply(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.show(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.statePull(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(terraformClient.output(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    Consumer<String> outputConsumer = invocation.getArgument(1);
                    outputConsumer.accept("{\"foo\":{\"value\":\"bar\",\"type\":\"string\",\"sensitive\":false}}");
                    return CompletableFuture.completedFuture(true);
                });

        Map<String, Object> outputEntry = new HashMap<>();
        outputEntry.put("name", "foo");
        outputEntry.put("value", "bar");
        outputEntry.put("sensitive", false);
        when(terraformOutputsService.buildOutputsFromJson(anyString())).thenReturn(List.of(outputEntry));

        ExecutorJobResult result = subject.apply(terraformJob, tempDir.toFile());

        assertTrue(result.isSuccessfulExecution());
        assertTrue(result.getOutputLog().contains("Outputs:"));
        assertTrue(result.getOutputLog().contains("foo = \"bar\""));
    }

    // Real `terraform apply <planfile>` reprints the plan's classic HCL diff before executing it -
    // apply -json never does, since -json mode has no such event. When apply is running from a
    // downloaded plan file (the normal plan-then-apply workflow), the console should get that same
    // diff back via getPlanAsHumanText, mirroring plan()'s own append.
    @Test
    void appendsHumanReadablePlanDiffWhenApplyingFromDownloadedPlanFile() throws Exception {
        TerraformExecutorServiceImpl subject = subject();
        TerraformJob terraformJob = createJob();

        when(applyStructuredOutputService.seedFromPlan("org", "42")).thenReturn(List.of());
        when(terraformState.downloadTerraformPlan(eq("org"), eq("workspace"), eq("42"), eq("1"), any(File.class)))
                .thenReturn(true);
        when(planStructuredOutputService.getPlanAsHumanText(eq(terraformJob), any(File.class)))
                .thenReturn("-/+ resource \"random_pet\" \"this\" {\n      ~ id = \"a\" -> (known after apply)\n    }");

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
        assertTrue(result.getOutputLog().contains("-/+ resource \"random_pet\" \"this\" {"));
        assertTrue(result.getOutputLog().contains("~ id = \"a\" -> (known after apply)"));
    }

    // No plan file means apply ran directly against HCL (e.g. a Destroy-workflow-style apply with
    // no prior plan step) - there's no saved plan for getPlanAsHumanText to render, so it must not
    // be called at all rather than rendering stale/empty content.
    @Test
    void doesNotRenderPlanDiffWhenNoPlanFileWasDownloaded() throws Exception {
        TerraformExecutorServiceImpl subject = subject();
        TerraformJob terraformJob = createJob();

        when(applyStructuredOutputService.seedFromPlan("org", "42")).thenReturn(List.of());
        when(terraformState.downloadTerraformPlan(eq("org"), eq("workspace"), eq("42"), eq("1"), any(File.class)))
                .thenReturn(false);

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.apply(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.show(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(terraformClient.statePull(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));

        subject.apply(terraformJob, tempDir.toFile());

        verify(planStructuredOutputService, never()).getPlanAsHumanText(any(), any());
    }

    @Test
    void doesNotAppendOutputsToConsoleWhenApplyItselfFailed() throws Exception {
        TerraformExecutorServiceImpl subject = subject();
        TerraformJob terraformJob = createJob();

        when(applyStructuredOutputService.seedFromPlan("org", "42")).thenReturn(List.of());

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.apply(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(terraformClient.show(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.statePull(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(terraformClient.output(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    Consumer<String> outputConsumer = invocation.getArgument(1);
                    outputConsumer.accept("{\"foo\":{\"value\":\"bar\",\"type\":\"string\",\"sensitive\":false}}");
                    return CompletableFuture.completedFuture(true);
                });

        Map<String, Object> outputEntry = new HashMap<>();
        outputEntry.put("name", "foo");
        outputEntry.put("value", "bar");
        outputEntry.put("sensitive", false);
        when(terraformOutputsService.buildOutputsFromJson(anyString())).thenReturn(List.of(outputEntry));

        ExecutorJobResult result = subject.apply(terraformJob, tempDir.toFile());

        assertFalse(result.isSuccessfulExecution());
        assertFalse(result.getOutputLog().contains("Outputs:"));
    }

    @Test
    void jsonApplyClientMergesErrorStream() {
        TerraformClient jsonApplyClient = subject().buildJsonEnabledApplyClient();

        assertTrue(jsonApplyClient.isJsonOutput());
        assertTrue(jsonApplyClient.isRedirectErrorStream());
    }

    @Test
    void jsonPlanClientMergesErrorStream() {
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

    // destroy() previously ran a plain (non-JSON) terraform destroy with none of the structured
    // per-resource status view plan()/apply() get. It now goes through the same JSON event
    // parser and publishes under the same "apply" phase/key the UI's applyMode rendering already
    // understands, starting from an empty change list (there's no prior plan step to seed from).
    @Test
    void destroyPublishesStructuredOutputFromJsonEvents() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.show(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(terraformClient.statePull(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));

        String plannedChangeLine = "{\"type\":\"planned_change\",\"change\":{\"resource\":{\"addr\":\"aws_instance.example\"},\"action\":\"delete\"}}";
        String applyCompleteLine = "{\"type\":\"apply_complete\",\"hook\":{\"resource\":{\"addr\":\"aws_instance.example\"},\"elapsed_seconds\":2}}";

        TerraformClient jsonDestroyClient = Mockito.mock(TerraformClient.class);
        when(jsonDestroyClient.destroy(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> lineConsumer = invocation.getArgument(1);
                    lineConsumer.accept(plannedChangeLine);
                    lineConsumer.accept(applyCompleteLine);
                    return CompletableFuture.completedFuture(true);
                });
        doReturn(jsonDestroyClient).when(subject).buildJsonEnabledDestroyClient();

        ExecutorJobResult result = subject.destroy(terraformJob, tempDir.toFile());

        assertTrue(result.isSuccessfulExecution());
        verify(applyStructuredOutputService, Mockito.atLeastOnce()).publishApplyProgress(
                eq("org"), eq("42"), eq("1"),
                argThat(changes -> changes.size() == 1
                        && "aws_instance.example".equals(changes.get(0).get("address"))
                        && "delete".equals(changes.get(0).get("action"))
                        && "applied".equals(changes.get(0).get("status"))),
                any());
    }

    @Test
    void jsonDestroyClientMergesErrorStream() {
        TerraformClient jsonDestroyClient = subject().buildJsonEnabledDestroyClient();

        assertTrue(jsonDestroyClient.isJsonOutput());
        assertTrue(jsonDestroyClient.isRedirectErrorStream());
    }

    // Regression test for a real UX gap: the structured panel stayed on console-only for a
    // plan/apply step's *entire* duration whenever it finished faster than the 2s (now 1s)
    // progress-flush interval, since lastFlush used to start at "now" instead of 0 - only the
    // very last, unconditional flush after the client returned would ever populate it, so a fast
    // plan flashed from console straight to "done" with no visible in-between structured state.
    @Test
    void plansFirstJsonLineFlushesStructuredOutputImmediately() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        String plannedChangeLine = "{\"type\":\"planned_change\",\"change\":{\"resource\":{\"addr\":\"aws_instance.example\"},\"action\":\"create\"}}";

        TerraformClient jsonPlanClient = Mockito.mock(TerraformClient.class);
        when(jsonPlanClient.planDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> lineConsumer = invocation.getArgument(1);
                    lineConsumer.accept(plannedChangeLine);
                    return CompletableFuture.completedFuture(0);
                });
        doReturn(jsonPlanClient).when(subject).buildJsonEnabledPlanClient();

        subject.plan(terraformJob, tempDir.toFile(), false);

        verify(planStructuredOutputService, Mockito.atLeastOnce()).publishPlanProgress(
                eq("org"), eq("42"), eq("1"),
                argThat(changes -> changes.size() == 1
                        && "aws_instance.example".equals(changes.get(0).get("address"))),
                any());
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

        verify(planStructuredOutputService, Mockito.atLeastOnce()).publishFinalPlanSnapshot(
                eq("org"), eq("42"), eq("1"), any(), any());
    }

    // Regression test for the reported bug: running `tofu plan` from the CLI showed only a bare
    // "Error: Unsupported attribute" header with no file, line or explanation. Under `-json` the
    // diagnostic's detail lives in the structured event, not in @message - the executor must
    // reconstruct the full rendering into the console stream the CLI reads.
    @Test
    void planForwardsTheFullDiagnosticRenderingToTheConsoleStream() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        String diagnosticLine = "{\"@message\":\"Error: Unsupported attribute\",\"type\":\"diagnostic\","
                + "\"diagnostic\":{\"severity\":\"error\",\"summary\":\"Unsupported attribute\","
                + "\"detail\":\"This object has no argument, nested block, or exported attribute named \\\"identifier\\\".\","
                + "\"range\":{\"filename\":\"main.tf\",\"start\":{\"line\":12,\"column\":12}}}}";

        TerraformClient jsonPlanClient = Mockito.mock(TerraformClient.class);
        when(jsonPlanClient.planDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> lineConsumer = invocation.getArgument(1);
                    lineConsumer.accept(diagnosticLine);
                    return CompletableFuture.completedFuture(1);
                });
        doReturn(jsonPlanClient).when(subject).buildJsonEnabledPlanClient();

        subject.plan(terraformJob, tempDir.toFile(), false);

        verify(logsService, Mockito.atLeastOnce()).sendLogs(eq(42), eq("1"), anyInt(),
                argThat(line -> line != null && line.contains("no argument, nested block")));
        verify(logsService, Mockito.atLeastOnce()).sendLogs(eq(42), eq("1"), anyInt(),
                argThat(line -> line != null && line.contains("on main.tf line 12")));
    }

    // Same regression as the plan case, on the apply path: apply -json's diagnostic events must
    // land in the console stream with their detail and location, not just the "Error:" header.
    @Test
    void applyForwardsTheFullDiagnosticRenderingToTheConsoleStream() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();

        Map<String, Object> seededChange = new HashMap<>();
        seededChange.put("address", "null_resource.fails");
        seededChange.put("status", "pending");
        when(applyStructuredOutputService.seedFromPlan("org", "42")).thenReturn(List.of(seededChange));

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.show(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(terraformClient.statePull(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));

        String diagnosticLine = "{\"@message\":\"Error: Missing required argument\",\"type\":\"diagnostic\","
                + "\"diagnostic\":{\"severity\":\"error\",\"summary\":\"Missing required argument\","
                + "\"detail\":\"The argument \\\"triggers\\\" is required, but no definition was found.\","
                + "\"range\":{\"filename\":\"main.tf\",\"start\":{\"line\":5,\"column\":1}}}}";

        TerraformClient jsonApplyClient = Mockito.mock(TerraformClient.class);
        when(jsonApplyClient.apply(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> lineConsumer = invocation.getArgument(1);
                    lineConsumer.accept(diagnosticLine);
                    return CompletableFuture.completedFuture(false);
                });
        doReturn(jsonApplyClient).when(subject).buildJsonEnabledApplyClient();

        ExecutorJobResult result = subject.apply(terraformJob, tempDir.toFile());

        assertTrue(result.getOutputLog().contains("The argument \"triggers\" is required, but no definition was found."),
                result.getOutputLog());
        assertTrue(result.getOutputLog().contains("on main.tf line 5"), result.getOutputLog());
    }

    // Same regression on the destroy path.
    @Test
    void destroyForwardsTheFullDiagnosticRenderingToTheConsoleStream() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(terraformClient.show(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(terraformClient.statePull(any(TerraformProcessData.class), any(Consumer.class), any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(false));

        String diagnosticLine = "{\"@message\":\"Error: Provider configuration not present\",\"type\":\"diagnostic\","
                + "\"diagnostic\":{\"severity\":\"error\",\"summary\":\"Provider configuration not present\","
                + "\"detail\":\"To work with null_resource.fails its original provider configuration is required.\"}}";

        TerraformClient jsonDestroyClient = Mockito.mock(TerraformClient.class);
        when(jsonDestroyClient.destroy(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> lineConsumer = invocation.getArgument(1);
                    lineConsumer.accept(diagnosticLine);
                    return CompletableFuture.completedFuture(false);
                });
        doReturn(jsonDestroyClient).when(subject).buildJsonEnabledDestroyClient();

        ExecutorJobResult result = subject.destroy(terraformJob, tempDir.toFile());

        assertTrue(result.getOutputLog().contains(
                "To work with null_resource.fails its original provider configuration is required."),
                result.getOutputLog());
    }

    // Regression test for the reported bug: a plan that fails late (a data source erroring after
    // the diff was already streamed) surfaced in the UI/CLI as a bare
    // "java.lang.RuntimeException: Failed to capture process output stream" - the executor's
    // catch block replaced every line Terraform had already printed with just the exception
    // message. The console output that was already collected must be preserved.
    @Test
    void planKeepsAlreadyStreamedOutputWhenTheClientFutureFails() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        String plannedChangeLine = "{\"type\":\"planned_change\",\"change\":{\"resource\":"
                + "{\"addr\":\"aws_instance.example\"},\"action\":\"create\"},"
                + "\"@message\":\"aws_instance.example: Plan to create\"}";

        TerraformClient jsonPlanClient = Mockito.mock(TerraformClient.class);
        when(jsonPlanClient.planDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> lineConsumer = invocation.getArgument(1);
                    lineConsumer.accept(plannedChangeLine);
                    return CompletableFuture.failedFuture(
                            new RuntimeException("Failed to capture process output stream"));
                });
        doReturn(jsonPlanClient).when(subject).buildJsonEnabledPlanClient();

        ExecutorJobResult result = subject.plan(terraformJob, tempDir.toFile(), false);

        assertFalse(result.isSuccessfulExecution());
        assertEquals(1, result.getExitCode());
        assertTrue(result.getOutputLog().contains("aws_instance.example: Plan to create"), result.getOutputLog());
        assertTrue(result.getOutputLog().contains("Failed to capture process output stream"), result.getOutputLog());
        assertTrue(result.getOutputErrorLog().contains("Failed to capture process output stream"));
    }

    // A failure inside the -json line consumer (here: the live structured-update push blowing up)
    // must not propagate into terraform-spring-boot's process-output reader loop - that kills the
    // reader and fails the whole run with "Failed to capture process output stream", losing the
    // console output that had already been read.
    @Test
    void planSurvivesAFailureRaisedFromInsideTheJsonLineConsumer() throws Exception {
        TerraformExecutorServiceImpl subject = spy(subject());
        TerraformJob terraformJob = createJob();

        when(terraformClient.init(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        Mockito.doThrow(new RuntimeException("redis is unreachable"))
                .when(planStructuredOutputService).publishPlanProgress(anyString(), anyString(), anyString(), any(), any());

        String plannedChangeLine = "{\"type\":\"planned_change\",\"change\":{\"resource\":"
                + "{\"addr\":\"aws_instance.example\"},\"action\":\"create\"},"
                + "\"@message\":\"aws_instance.example: Plan to create\"}";

        TerraformClient jsonPlanClient = Mockito.mock(TerraformClient.class);
        when(jsonPlanClient.planDetailExitCode(any(TerraformProcessData.class), any(Consumer.class), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> lineConsumer = invocation.getArgument(1);
                    lineConsumer.accept(plannedChangeLine);
                    return CompletableFuture.completedFuture(2);
                });
        doReturn(jsonPlanClient).when(subject).buildJsonEnabledPlanClient();

        ExecutorJobResult result = subject.plan(terraformJob, tempDir.toFile(), false);

        assertEquals(2, result.getExitCode());
        assertTrue(result.getOutputLog().contains("aws_instance.example: Plan to create"), result.getOutputLog());
    }

    @Test
    void streamDrainFailureKeepsTheTerraformDiagnosticPrimaryAndCountsIt() {
        TerraformExecutorServiceImpl subject = subject();
        String realDiagnostic = "Error: Reference to undeclared input variable\n\n  on main.tf line 3\n";

        ExecutorJobResult result = subject.setError(
                new RuntimeException("Failed to capture process output stream"), realDiagnostic);

        int diagnosticIndex = result.getOutputLog().indexOf("undeclared input variable");
        int captureIndex = result.getOutputLog().indexOf("Failed to capture process output stream");
        assertTrue(diagnosticIndex >= 0, result.getOutputLog());
        assertTrue(captureIndex > diagnosticIndex, "real diagnostic must precede the capture note: " + result.getOutputLog());
        assertTrue(result.getOutputLog().contains("The Terraform/OpenTofu diagnostic above is the primary error."));
        assertEquals(1.0, meterRegistry.get("terrakube.executor.process.stream.drain.timeouts").counter().count());
    }
}
