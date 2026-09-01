package io.terrakube.executor.service.terraform;

import com.diogonunes.jcolor.AnsiFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.StructuredOutputProperties;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.executor.ExecutorJobResult;
import io.terrakube.executor.service.logs.LogsConsumer;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.terraform.structured.StructuredOutputPersistenceQueue;
import io.terrakube.terraform.TerraformClient;
import io.terrakube.terraform.TerraformDownloader;
import io.terrakube.terraform.TerraformProcessData;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.text.TextStringBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.diogonunes.jcolor.Ansi.colorize;
import static com.diogonunes.jcolor.Attribute.*;
import static io.terrakube.executor.service.workspace.SetupWorkspaceImpl.SSH_DIRECTORY;
import static io.terrakube.executor.service.workspace.SetupWorkspaceImpl.SSH_DIRECTORY_MODULE;

@Slf4j
@Service
public class TerraformExecutorServiceImpl implements TerraformExecutor {

    private static final String STEP_SEPARATOR = "***************************************";
    // Was 2000ms: the structured panel could sit on stale (or, worse, entirely empty - see
    // lastFlush below) data for up to 2s at a time while resources were actively transitioning,
    // which reads as sluggish for anything that completes faster than that. Halved rather than
    // dropped further since each flush is a full HTTP round trip to /context/v1 (GET-merge-POST)
    // per plan/apply/destroy step - this is a rate-limiting ceiling, not a per-event push.
    private static final long APPLY_PROGRESS_FLUSH_INTERVAL_MS = 1000;

    TerraformClient terraformClient;
    TerraformState terraformState;
    ScriptEngineService scriptEngineService;
    RedisTemplate redisTemplate;
    boolean enableColorOutput;
    ProcessLogs logsService;
    int redisTimeout;
    PlanStructuredOutputService planStructuredOutputService;
    ApplyStructuredOutputService applyStructuredOutputService;
    TerraformOutputsService terraformOutputsService;
    ObjectMapper objectMapper;
    StructuredOutputPersistenceQueue structuredOutputPersistenceQueue;
    ExecutorFlagsProperties executorFlagsProperties;
    StructuredOutputProperties structuredOutputProperties;
    MeterRegistry meterRegistry;

    public TerraformExecutorServiceImpl(TerraformClient terraformClient, TerraformState terraformState, ScriptEngineService scriptEngineService, ProcessLogs logsService, PlanStructuredOutputService planStructuredOutputService, ApplyStructuredOutputService applyStructuredOutputService, TerraformOutputsService terraformOutputsService, ObjectMapper objectMapper, @Value("${io.terrakube.terraform.flags.enableColor}") boolean enableColorOutput, RedisTemplate redisTemplate, @Value("${io.terrakube.executor.redis.timeout}") int redisTimeout, StructuredOutputPersistenceQueue structuredOutputPersistenceQueue, ExecutorFlagsProperties executorFlagsProperties, StructuredOutputProperties structuredOutputProperties, MeterRegistry meterRegistry) {
        this.terraformClient = terraformClient;
        this.terraformState = terraformState;
        this.scriptEngineService = scriptEngineService;
        this.redisTemplate = redisTemplate;
        this.logsService = logsService;
        this.planStructuredOutputService = planStructuredOutputService;
        this.applyStructuredOutputService = applyStructuredOutputService;
        this.terraformOutputsService = terraformOutputsService;
        this.objectMapper = objectMapper;
        this.enableColorOutput = enableColorOutput;
        this.redisTimeout = redisTimeout;
        this.structuredOutputPersistenceQueue = structuredOutputPersistenceQueue;
        this.executorFlagsProperties = executorFlagsProperties;
        this.structuredOutputProperties = structuredOutputProperties;
        this.meterRegistry = meterRegistry;
    }

    public File getTerraformWorkingDir(TerraformJob terraformJob, File workingDirectory) throws IOException {
        File terraformWorkingDir = workingDirectory;
        try {
            if (!terraformJob.getBranch().equals("remote-content") || (terraformJob.getFolder() != null && !terraformJob.getFolder().split(",")[0].equals("/"))) {
                terraformWorkingDir = new File(Path.of(workingDirectory.getCanonicalPath(), terraformJob.getFolder().split(",")[0]).toString());
                if (!terraformWorkingDir.getCanonicalPath().startsWith(workingDirectory.getCanonicalPath())) {
                    throw new IOException(String.format("Invalid workspace folder path traversal attempt: %s", terraformJob.getFolder()));
                }
                if (!terraformWorkingDir.isDirectory()) {
                    throw new IOException(String.format("Terraform Working Directory does not exist: %s", terraformWorkingDir.getCanonicalPath()));
                }
            }
        } catch (IOException e) {
            log.error(e.getMessage());
            throw e;
        }
        log.info("Terraform Working Directory: {}", terraformWorkingDir.getCanonicalPath());
        return terraformWorkingDir;
    }

    private void waitForStreamCompletion(String jobId, int maxWaitSeconds) {
        int pollInterval = 1000; // 1 second
        int totalWait = 0;
        long lastMessageCount = -1;
        int stableCount = 0;

        while (totalWait < maxWaitSeconds * 1000) {
            try {
                // Check if there are pending messages in the stream
                Long streamLength = redisTemplate.opsForStream().size(jobId);

                if (streamLength != null) {
                    if (streamLength.equals(lastMessageCount)) {
                        stableCount++;
                        // If stream size hasn't changed for 3 consecutive checks, consider it complete
                        if (stableCount >= 3) {
                            log.info("Stream appears complete for job {}", jobId);
                            break;
                        }
                    } else {
                        stableCount = 0;
                        lastMessageCount = streamLength;
                    }
                }

                Thread.sleep(pollInterval);
                totalWait += pollInterval;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for stream completion", e);
                break;
            }
        }

        log.info("Waited {} ms for stream completion", totalWait);
    }


    @Override
    public ExecutorJobResult plan(TerraformJob terraformJob, File executorTempDirectory, boolean isDestroy) {
        logsService.setupConsumerGroups(terraformJob.getJobId());
        ExecutorJobResult result;

        TextStringBuilder jobOutput = new TextStringBuilder();
        TextStringBuilder jobErrorOutput = new TextStringBuilder();
        // Method-scoped so a mid-stream failure (see the catch below) can still publish a final
        // structured snapshot carrying whatever changes/diagnostics were parsed before it broke.
        List<Map<String, Object>> liveChanges = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();
        try {
            File terraformWorkingDir = getTerraformWorkingDir(terraformJob, executorTempDirectory);
            boolean executionPlan = false;
            boolean planCommandExecuted = false;
            int exitCode = 0;
            boolean scriptAfterSuccessPlan;

            Consumer<String> planOutput = LogsConsumer.builder()
                    .jobId(Integer.valueOf(terraformJob.getJobId()))
                    .terraformOutput(jobOutput)
                    .stepId(terraformJob.getStepId())
                    .processLogs(logsService)
                    .lineNumber(new AtomicInteger(0))
                    .build();

            boolean initSuccessful = prepareTerraformOperation(terraformJob, executorTempDirectory, terraformWorkingDir, planOutput);

            if (initSuccessful) {
                boolean scriptBeforeSuccessPlan = executePreOperationScripts(terraformJob, terraformWorkingDir, planOutput);

                showTerraformMessage(terraformJob, "PLAN", planOutput);

                if (scriptBeforeSuccessPlan) {
                    planCommandExecuted = true;
                    TerraformJsonEventParser eventParser = new TerraformJsonEventParser(objectMapper);
                    // Starting at 0 (not now()) guarantees the very first json line always
                    // passes the "now - lastFlush > interval" check below and flushes
                    // immediately - otherwise the structured panel stayed on console-only for
                    // this step's *entire* duration whenever the whole plan finished in under
                    // APPLY_PROGRESS_FLUSH_INTERVAL_MS (common for small/fast plans), only
                    // flipping to Structured after the step had already completed.
                    AtomicLong lastFlush = new AtomicLong(0);

                    Consumer<String> jsonLineConsumer = (line) -> {
                        String humanMessage = eventParser.parseLine(line, liveChanges, jobDiagnostics);
                        if (humanMessage != null) {
                            acceptConsoleLines(planOutput, humanMessage);
                        }

                        // Cheap pre-filter only: the persistence queue coalesces per step, so this
                        // just bounds how often we build a snapshot copy on the reader thread. The
                        // GET/merge/POST and the SSE push happen on the queue worker, never here.
                        long now = System.currentTimeMillis();
                        if (now - lastFlush.get() > APPLY_PROGRESS_FLUSH_INTERVAL_MS) {
                            lastFlush.set(now);
                            planStructuredOutputService.publishPlanProgress(
                                    terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), liveChanges, jobDiagnostics);
                        }
                    };

                    TerraformClient jsonPlanClient = buildJsonEnabledPlanClient();
                    Consumer<String> guardedJsonLineConsumer = guardConsumer(jsonLineConsumer);

                    if (isDestroy) {
                        log.warn("Executor running a plan to destroy resources...");
                        exitCode = jsonPlanClient.planDestroyDetailExitCode(
                                getTerraformProcessData(terraformJob, terraformWorkingDir, executorTempDirectory),
                                guardedJsonLineConsumer,
                                null).get();
                    } else {
                        exitCode = jsonPlanClient.planDetailExitCode(
                                getTerraformProcessData(terraformJob, terraformWorkingDir, executorTempDirectory),
                                guardedJsonLineConsumer,
                                null).get();
                    }

                    // Unconditional final snapshot - the periodic flush above only fires when a json
                    // line arrives more than APPLY_PROGRESS_FLUSH_INTERVAL_MS after the last one, so
                    // a plan whose lines all land in a single burst (typical for small/fast plans)
                    // would otherwise exit having enqueued nothing. Attempted even when the plan
                    // failed, so a diagnostic (e.g. an unset required variable) is the last word on
                    // this step rather than stale/empty progress data.
                    planStructuredOutputService.publishFinalPlanSnapshot(
                            terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), liveChanges, jobDiagnostics);

                    terraformJob.setLiveChanges(liveChanges);
                    terraformJob.setJobDiagnostics(jobDiagnostics);
                } else {
                    exitCode = 1;
                    executeOnFailureOperationScripts(terraformJob, terraformWorkingDir, planOutput);
                }
            } else {
                exitCode = 1;
                executeOnFailureOperationScripts(terraformJob, terraformWorkingDir, planOutput);
            }

            if (planCommandExecuted && (exitCode != 1 || terraformJob.isIgnoreError())) {
                executionPlan = true;
            } else if (planCommandExecuted) {
                executeOnFailureOperationScripts(terraformJob, terraformWorkingDir, planOutput);
            }

            if (executionPlan) {
                // The live -json stream only ever produced terse one-line-per-resource messages
                // (structured data goes to the panel above, not the console) - render the
                // classic human-readable diff from the plan file and append it to console, so
                // anything reading this step's console output (raw-log download,
                // PrCommentService's PR/MR comment) still gets a real diff, not just those
                // lines. Must run before waitForStreamCompletion below - that call declares the
                // console stream "done" once it goes quiet, and anything appended afterwards
                // arrives too late for whatever reads the stream at that signal.
                String humanReadablePlan = planStructuredOutputService.getPlanAsHumanText(terraformJob, terraformWorkingDir);
                if (humanReadablePlan != null && !humanReadablePlan.isBlank()) {
                    for (String line : humanReadablePlan.split("\n", -1)) {
                        planOutput.accept(line);
                    }
                }
            }

            log.warn("Terraform plan Executed: {} Exit Code: {}", executionPlan, exitCode);

            scriptAfterSuccessPlan = executePostOperationScripts(terraformJob, terraformWorkingDir, planOutput, executionPlan);

            waitForStreamCompletion(terraformJob.getJobId(), 300);
            drainStructuredOutputQueue(terraformJob.getJobId());

            result = generateJobResult(scriptAfterSuccessPlan, jobOutput.toString(), jobErrorOutput.toString());
            result.setPlanFile(executionPlan ? terraformState.saveTerraformPlan(terraformJob.getOrganizationId(),
                    terraformJob.getWorkspaceId(), terraformJob.getJobId(), terraformJob.getStepId(), terraformWorkingDir)
                    : "");
            if (executionPlan) {
                planStructuredOutputService.publishPlanSummary(terraformJob, terraformWorkingDir, terraformJob.getLiveChanges(), terraformJob.getJobDiagnostics());
            }
            result.setPlan(true);
            result.setExitCode(exitCode);
        } catch (IOException | ExecutionException | InterruptedException exception) {
            // A stream-drain failure or late exception must not swallow the plan diagnostics the
            // UI needs - publish what was parsed before it broke, then let it drain.
            try {
                planStructuredOutputService.publishFinalPlanSnapshot(
                        terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(),
                        liveChanges, jobDiagnostics);
                drainStructuredOutputQueue(terraformJob.getJobId());
            } catch (Exception e) {
                log.warn("Unable to publish final plan snapshot after failure for job {}", terraformJob.getJobId(), e);
            }
            result = setError(exception, jobOutput.toString());
            result.setPlan(true);
            result.setExitCode(1);
        }
        return result;
    }

    // Give the async structured-output queue a bounded window to finish persisting before the job
    // result is finalised. A timeout is logged, never fatal - the run's outcome does not depend on
    // best-effort context writes.
    private void drainStructuredOutputQueue(String jobId) {
        if (structuredOutputPersistenceQueue == null || executorFlagsProperties == null
                || !executorFlagsProperties.isAsyncStructuredOutput()) {
            return;
        }
        long drainTimeoutMs = structuredOutputProperties != null ? structuredOutputProperties.getDrainTimeoutMs() : 30000L;
        boolean drained = structuredOutputPersistenceQueue.awaitDrain(java.time.Duration.ofMillis(drainTimeoutMs));
        if (!drained) {
            log.warn("Structured-output queue did not drain within {} ms for job {}", drainTimeoutMs, jobId);
        }
    }

    @Override
    public ExecutorJobResult apply(TerraformJob terraformJob, File executorTempDirectory) {
        logsService.setupConsumerGroups(terraformJob.getJobId());
        ExecutorJobResult result;

        TextStringBuilder terraformOutput = new TextStringBuilder();
        TextStringBuilder terraformErrorOutput = new TextStringBuilder();
        try {
            File terraformWorkingDir = getTerraformWorkingDir(terraformJob, executorTempDirectory);
            Consumer<String> applyOutput = LogsConsumer.builder()
                    .jobId(Integer.valueOf(terraformJob.getJobId()))
                    .lineNumber(new AtomicInteger(0))
                    .terraformOutput(terraformOutput)
                    .stepId(terraformJob.getStepId())
                    .processLogs(logsService)
                    .build();

            HashMap<String, String> terraformParameters = getWorkspaceParameters(terraformJob.getVariables());

            boolean execution = false;
            boolean scriptAfterSuccess;
            boolean initSuccessful = prepareTerraformOperation(terraformJob, executorTempDirectory, terraformWorkingDir, applyOutput);

            if (initSuccessful) {
                boolean scriptBeforeSuccess = executePreOperationScripts(terraformJob, terraformWorkingDir, applyOutput);

                showTerraformMessage(terraformJob, "APPLY", applyOutput);

                if (scriptBeforeSuccess) {
                    TerraformProcessData terraformProcessData = getTerraformProcessData(terraformJob, terraformWorkingDir, executorTempDirectory);
                    boolean planFileDownloaded = terraformState.downloadTerraformPlan(terraformJob.getOrganizationId(),
                            terraformJob.getWorkspaceId(), terraformJob.getJobId(), terraformJob.getStepId(),
                            terraformWorkingDir);
                    terraformProcessData.setTerraformVariables(planFileDownloaded ? new HashMap<>() : terraformParameters);

                    execution = runJsonApply(terraformJob, terraformProcessData, applyOutput);

                    handleTerraformStateChange(terraformJob, terraformWorkingDir, executorTempDirectory);

                    // apply -json's event stream only ever carries terse per-resource one-liners
                    // ("aws_instance.foo: Creating...", "...Creation complete after 3s [id=...]")
                    // plus the final change-summary line - unlike plan(), which appends
                    // getPlanAsHumanText's classic rendered diff, apply never appended anything
                    // resembling a `terraform show`/CLI-style closing readout. Mirrors plan()'s
                    // append (same reasoning: must run before waitForStreamCompletion below), just
                    // rendered from the plan file apply already downloaded above instead of one it
                    // computed itself - real `terraform apply <planfile>` reprints this same diff
                    // before executing it, so this restores that content even though Terrakube
                    // renders it after the resource-by-resource lines rather than before.
                    if (execution && planFileDownloaded) {
                        String humanReadablePlan = planStructuredOutputService.getPlanAsHumanText(terraformJob, terraformWorkingDir);
                        if (humanReadablePlan != null && !humanReadablePlan.isBlank()) {
                            for (String line : humanReadablePlan.split("\n", -1)) {
                                applyOutput.accept(line);
                            }
                        }
                    }

                    if (execution) {
                        appendHumanReadableOutputs(terraformJob, applyOutput);
                    }
                }
            }

            if (!execution) {
                executeOnFailureOperationScripts(terraformJob, terraformWorkingDir, applyOutput);
            }

            log.warn("Terraform apply Executed Successfully: {}", execution);
            scriptAfterSuccess = executePostOperationScripts(terraformJob, terraformWorkingDir, applyOutput, execution || terraformJob.isIgnoreError());

            waitForStreamCompletion(terraformJob.getJobId(), 300);
            drainStructuredOutputQueue(terraformJob.getJobId());
            result = generateJobResult(scriptAfterSuccess, terraformOutput.toString(), terraformErrorOutput.toString());
        } catch (IOException | ExecutionException | InterruptedException exception) {
            drainStructuredOutputQueue(terraformJob.getJobId());
            result = setError(exception, terraformOutput.toString());
            result.setExitCode(1);
        }
        return result;
    }

    private boolean runJsonApply(TerraformJob terraformJob, TerraformProcessData terraformProcessData, Consumer<String> applyOutput)
            throws IOException, ExecutionException, InterruptedException {
        List<Map<String, Object>> changes = applyStructuredOutputService.seedFromPlan(
                terraformJob.getOrganizationId(), terraformJob.getJobId());

        if (changes.isEmpty()) {
            // No plan structured output to seed from (custom TCL with 0/2+ plan steps, or plan
            // publishing failed) — fall back to a plain apply through the shared client, exactly
            // as before this feature existed.
            return terraformClient.apply(terraformProcessData, guardConsumer(applyOutput), null).get();
        }

        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();
        applyStructuredOutputService.publishApplyProgress(
                terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), changes, jobDiagnostics);

        TerraformJsonEventParser eventParser = new TerraformJsonEventParser(objectMapper);
        // See the matching comment in plan(): starting at 0 flushes the first line immediately.
        // Apply already seeds the panel with pending rows before this point, so this mostly
        // matters for the first real status transition landing without a multi-second delay.
        AtomicLong lastFlush = new AtomicLong(0);

        Consumer<String> jsonLineConsumer = (line) -> {
            String humanMessage = eventParser.parseLine(line, changes, jobDiagnostics);
            if (humanMessage != null) {
                acceptConsoleLines(applyOutput, humanMessage);
            }

            long now = System.currentTimeMillis();
            if (now - lastFlush.get() > APPLY_PROGRESS_FLUSH_INTERVAL_MS) {
                lastFlush.set(now);
                applyStructuredOutputService.publishApplyProgress(
                        terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), changes, jobDiagnostics);
            }
        };

        TerraformClient jsonApplyClient = buildJsonEnabledApplyClient();

        boolean execution = jsonApplyClient.apply(terraformProcessData, guardConsumer(jsonLineConsumer), null).get();

        String stateJson = getCurrentStateJson(terraformJob, terraformProcessData);
        if (stateJson != null) {
            applyStructuredOutputService.resolveFinalValues(changes, stateJson);
        }

        applyStructuredOutputService.publishFinalApplySnapshot(
                terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), changes, jobDiagnostics);

        return execution;
    }

    // destroy() previously ran a plain (non-JSON) terraform destroy, so it never got the
    // structured per-resource status view plan()/apply() get - unlike apply(), there's no
    // separate prior plan step to seed rows from (a "Destroy" workflow can run destroy directly,
    // with no plan step at all), so this starts from an empty list and lets `destroy -json`'s own
    // planned_change/apply_* events populate it, exactly the way plan() populates its own
    // liveChanges from empty rather than seeding them.
    private boolean runJsonDestroy(TerraformJob terraformJob, TerraformProcessData terraformProcessData, Consumer<String> destroyOutput)
            throws IOException, ExecutionException, InterruptedException {
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> jobDiagnostics = new ArrayList<>();
        TerraformJsonEventParser eventParser = new TerraformJsonEventParser(objectMapper);
        AtomicLong lastFlush = new AtomicLong(0);

        Consumer<String> jsonLineConsumer = (line) -> {
            String humanMessage = eventParser.parseLine(line, changes, jobDiagnostics);
            if (humanMessage != null) {
                acceptConsoleLines(destroyOutput, humanMessage);
            }

            long now = System.currentTimeMillis();
            if (now - lastFlush.get() > APPLY_PROGRESS_FLUSH_INTERVAL_MS) {
                lastFlush.set(now);
                // Published under the same "apply" phase/key as runJsonApply - a destroy is
                // rendered by the UI as an apply of all-delete actions, reusing
                // applyStructuredOutput/StructuredPlanOutput's applyMode rather than adding a
                // third parallel structured-output shape for what's functionally the same view.
                applyStructuredOutputService.publishApplyProgress(
                        terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), changes, jobDiagnostics);
            }
        };

        TerraformClient jsonDestroyClient = buildJsonEnabledDestroyClient();

        boolean execution = jsonDestroyClient.destroy(terraformProcessData, guardConsumer(jsonLineConsumer), null).get();

        applyStructuredOutputService.publishFinalApplySnapshot(
                terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), changes, jobDiagnostics);

        return execution;
    }

    // The json event stream is one event per line, but a single diagnostic event renders as a
    // multi-line block (header, source snippet, explanatory detail). Split it so every line gets
    // its own log record and line number, instead of one record carrying embedded newlines.
    private static void acceptConsoleLines(Consumer<String> output, String message) {
        for (String line : message.split("\n", -1)) {
            output.accept(line);
        }
    }

    // Package-private (not private) so tests can spy/stub this one seam instead of letting
    // runJsonApply construct a real TerraformClient that would launch an actual OS process.
    TerraformClient buildJsonEnabledApplyClient() {
        return TerraformClient.builder()
                .jsonOutput(true)
                .showColor(false)
                // The normal client merges stderr before every Terraform operation. Keep that
                // behaviour for the JSON client too: runJsonApply has no separate stderr
                // listener, so otherwise diagnostics are silently discarded.
                .redirectErrorStream(true)
                .terraformReleasesUrl(terraformClient.getTerraformReleasesUrl())
                .tofuReleasesUrl(terraformClient.getTofuReleasesUrl())
                .build();
    }

    // Package-private (not private) so tests can spy/stub this one seam, same reasoning as
    // buildJsonEnabledApplyClient.
    TerraformClient buildJsonEnabledPlanClient() {
        return TerraformClient.builder()
                .jsonOutput(true)
                .showColor(false)
                .redirectErrorStream(true)
                .terraformReleasesUrl(terraformClient.getTerraformReleasesUrl())
                .tofuReleasesUrl(terraformClient.getTofuReleasesUrl())
                .build();
    }

    // Package-private (not private) so tests can spy/stub this one seam, same reasoning as
    // buildJsonEnabledApplyClient.
    TerraformClient buildJsonEnabledDestroyClient() {
        return TerraformClient.builder()
                .jsonOutput(true)
                .showColor(false)
                .redirectErrorStream(true)
                .terraformReleasesUrl(terraformClient.getTerraformReleasesUrl())
                .tofuReleasesUrl(terraformClient.getTofuReleasesUrl())
                .build();
    }

    private String getCurrentStateJson(TerraformJob terraformJob, TerraformProcessData terraformProcessData)
            throws IOException, ExecutionException, InterruptedException {
        TextStringBuilder stateOutput = new TextStringBuilder();
        TextStringBuilder stateErrorOutput = new TextStringBuilder();
        boolean success = terraformClient.show(terraformProcessData, stateOutput::append, stateErrorOutput::append).get();
        if (!success) {
            log.warn("Unable to read current state for job {} step {}. Error: {}", terraformJob.getJobId(),
                    terraformJob.getStepId(), stateErrorOutput);
            return null;
        }

        return stateOutput.toString();
    }

    @Override
    public ExecutorJobResult destroy(TerraformJob terraformJob, File executorTempDirectory) {
        logsService.setupConsumerGroups(terraformJob.getJobId());
        ExecutorJobResult result;

        TextStringBuilder jobOutput = new TextStringBuilder();
        TextStringBuilder jobErrorOutput = new TextStringBuilder();
        try {
            File terraformWorkingDir = getTerraformWorkingDir(terraformJob, executorTempDirectory);
            Consumer<String> outputDestroy = LogsConsumer.builder()
                    .jobId(Integer.valueOf(terraformJob.getJobId()))
                    .terraformOutput(jobOutput)
                    .stepId(terraformJob.getStepId())
                    .processLogs(logsService)
                    .lineNumber(new AtomicInteger(0))
                    .build();

            boolean execution = false;
            boolean scriptAfterSuccess;
            boolean initSuccessful = prepareTerraformOperation(terraformJob, executorTempDirectory, terraformWorkingDir, outputDestroy);

            if (initSuccessful) {
                boolean scriptBeforeSuccess = executePreOperationScripts(terraformJob, terraformWorkingDir, outputDestroy);

                showTerraformMessage(terraformJob, "DESTROY", outputDestroy);

                if (scriptBeforeSuccess) {
                    execution = runJsonDestroy(terraformJob,
                            getTerraformProcessData(terraformJob, terraformWorkingDir, executorTempDirectory),
                            outputDestroy);

                    handleTerraformStateChange(terraformJob, terraformWorkingDir, executorTempDirectory);
                }
            }

            if (!execution) {
                executeOnFailureOperationScripts(terraformJob, terraformWorkingDir, outputDestroy);
            }

            log.warn("Terraform destroy Executed Successfully: {}", execution);
            scriptAfterSuccess = executePostOperationScripts(terraformJob, terraformWorkingDir, outputDestroy, execution);

            waitForStreamCompletion(terraformJob.getJobId(), 300);
            drainStructuredOutputQueue(terraformJob.getJobId());
            result = generateJobResult(scriptAfterSuccess, jobOutput.toString(), jobErrorOutput.toString());
        } catch (IOException | ExecutionException | InterruptedException exception) {
            drainStructuredOutputQueue(terraformJob.getJobId());
            result = setError(exception, jobOutput.toString());
            result.setExitCode(1);
        }
        return result;
    }

    private ExecutorJobResult generateJobResult(boolean scriptAfterSuccess, String jobOutput, String jobErrorOutput) {
        ExecutorJobResult jobResult = new ExecutorJobResult();
        jobResult.setSuccessfulExecution(scriptAfterSuccess);
        jobResult.setOutputLog(jobOutput);
        jobResult.setOutputErrorLog(jobErrorOutput);

        return jobResult;
    }

    private boolean executePreOperationScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output) {
        boolean scriptBeforeSuccess;
        if (terraformJob.getCommandList() != null) {
            scriptBeforeSuccess = scriptEngineService.execute(
                    terraformJob,
                    terraformJob
                            .getCommandList()
                            .stream()
                            .filter(command -> command.isBefore() && !command.isBeforeInit())
                            .collect(Collectors.toCollection(LinkedList::new)),
                    workingDirectory,
                    output);
        } else {
            log.warn("No commands to run before terraform operation Job {}", terraformJob.getJobId());
            scriptBeforeSuccess = true;
        }
        return scriptBeforeSuccess;
    }

    private boolean executePreInitScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output) {
        boolean scriptBeforeInitSuccess;
        if (terraformJob.getCommandList() != null) {
            scriptBeforeInitSuccess = scriptEngineService.execute(
                    terraformJob,
                    terraformJob
                            .getCommandList()
                            .stream()
                            .filter(command -> command.isBeforeInit())
                            .collect(Collectors.toCollection(LinkedList::new)),
                    workingDirectory,
                    output);
        } else {
            log.warn("No commands to run before terraform init Job {}", terraformJob.getJobId());
            scriptBeforeInitSuccess = true;
        }
        return scriptBeforeInitSuccess;
    }

    private boolean executePostOperationScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output, boolean execution) {
        boolean scriptAfterSuccess;
        if (execution) {
            if (terraformJob.getCommandList() != null) {
                scriptAfterSuccess = scriptEngineService.execute(
                        terraformJob,
                        terraformJob
                                .getCommandList()
                                .stream()
                                .filter(command -> command.isAfter())
                                .collect(Collectors.toCollection(LinkedList::new)),
                        workingDirectory,
                        output);
            } else {
                scriptAfterSuccess = true;
            }
        } else {
            scriptAfterSuccess = false;
        }

        log.warn("No commands to run after terraform operation Job {}", scriptAfterSuccess);
        return scriptAfterSuccess;
    }

    private void executeOnFailureOperationScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output) {
            log.warn("Terraform operation failed, running onFailure scripts");
            if (terraformJob.getOnFailureList() != null) {
                scriptEngineService.execute(
                        terraformJob,
                        new LinkedList<>(terraformJob
                                .getOnFailureList()),
                        workingDirectory,
                        output);
            }

        log.warn("Terraform operation failed, running onFailure scripts completed");
    }

    private void handleTerraformStateChange(TerraformJob terraformJob, File terraformWorkingDirectory, File executorTempDirectory)
            throws IOException, ExecutionException, InterruptedException {
        log.info("Running Terraform show");
        TextStringBuilder jsonState = new TextStringBuilder();
        TextStringBuilder rawTfState = new TextStringBuilder();
        Consumer<String> applyJSON = getStringConsumer(jsonState);
        Consumer<String> rawStateJSON = getStringConsumer(rawTfState);
        TerraformProcessData terraformProcessData = getTerraformProcessData(terraformJob, terraformWorkingDirectory, executorTempDirectory);
        terraformProcessData.setTerraformVariables(new HashMap());
        terraformProcessData.setTerraformEnvironmentVariables(new HashMap());
        Boolean showJsonState = terraformClient.show(terraformProcessData, applyJSON, applyJSON).get();
        Boolean showRawState = terraformClient.statePull(terraformProcessData, rawStateJSON, rawStateJSON).get();

        Thread.sleep(5000);

        if (Boolean.TRUE.equals(showRawState)) {
            terraformJob.setRawState(rawStateJSON.toString());
        }

        if (Boolean.TRUE.equals(showJsonState)) {
            log.info("Uploading terraform state json");
            terraformState.saveStateJson(terraformJob, jsonState.toString(), rawTfState.toString());

            TextStringBuilder jsonOutput = new TextStringBuilder();
            Consumer<String> terraformJsonOutput = getStringConsumer(jsonOutput);

            log.info("Checking terraform output json");
            Boolean showOutput = terraformClient.output(terraformProcessData, terraformJsonOutput, terraformJsonOutput).get();
            if (Boolean.TRUE.equals(showOutput)) {
                terraformJob.setTerraformOutput(jsonOutput.toString());
                terraformOutputsService.publishOutputs(
                        terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), jsonOutput.toString());
            }

        }
    }

    // Mirrors what the real `terraform apply`/`terraform output` CLI prints after a successful
    // apply, rendered from the same JSON handleTerraformStateChange already fetched for the
    // structured outputs panel (terraformOutputsService.buildOutputsFromJson), rather than
    // re-running `terraform output` a second time.
    private void appendHumanReadableOutputs(TerraformJob terraformJob, Consumer<String> output) {
        String outputJson = terraformJob.getTerraformOutput();
        if (outputJson == null || outputJson.isBlank()) {
            return;
        }

        try {
            List<Map<String, Object>> outputs = terraformOutputsService.buildOutputsFromJson(outputJson);
            if (outputs.isEmpty()) {
                return;
            }

            output.accept("");
            output.accept("Outputs:");
            output.accept("");
            for (Map<String, Object> entry : outputs) {
                String name = String.valueOf(entry.get("name"));
                boolean sensitive = Boolean.TRUE.equals(entry.get("sensitive"));
                output.accept(name + " = " + (sensitive ? "<sensitive>" : renderOutputValue(entry.get("value"))));
            }
        } catch (IOException e) {
            log.warn("Unable to render human-readable outputs for job {}", terraformJob.getJobId(), e);
        }
    }

    private String renderOutputValue(Object value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof String stringValue) {
            return "\"" + stringValue + "\"";
        }

        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    @Override
    public String version() {
        String terraformVersion = "";
        TextStringBuilder terraformOutput = new TextStringBuilder();
        TextStringBuilder terraformErrorOutput = new TextStringBuilder();
        try {
            terraformClient.setOutputListener(response -> {
                terraformOutput.appendln(response);
            });
            terraformClient.setErrorListener(response -> {
                terraformErrorOutput.appendln(response);
            });
            terraformVersion = terraformClient.version().get();
        } catch (IOException | ExecutionException | InterruptedException exception) {
            setError(exception);
        }
        return terraformVersion;
    }

    private ExecutorJobResult setError(Exception exception) {
        return setError(exception, "");
    }

    // Keep everything Terraform/OpenTofu already streamed to the console before the failure. The
    // previous behaviour replaced it all with just exception.getMessage(), so an operation that
    // failed late - a data source erroring after the plan diff was already printed, or the
    // terraform-spring-boot client's stream-drain watchdog firing on a large burst of output -
    // reached the UI and CLI as a bare "java.lang.RuntimeException: Failed to capture process
    // output stream" with none of the real Terraform output, and none of the real error (the
    // "Error: External Program Lookup Failed" / missing-python diagnostic, in the reported case)
    // that actually caused it.
    private ExecutorJobResult setError(Exception exception, String partialConsoleOutput) {
        String message = exception.getMessage() != null ? exception.getMessage() : exception.toString();
        log.error("Terraform operation failed: {}", message, exception);

        TextStringBuilder consoleOutput = new TextStringBuilder();
        if (partialConsoleOutput != null && !partialConsoleOutput.isBlank()) {
            consoleOutput.append(partialConsoleOutput);
            if (!partialConsoleOutput.endsWith("\n")) {
                consoleOutput.appendNewLine();
            }
            consoleOutput.appendNewLine();
        }
        consoleOutput.appendln("Error: the Terrakube executor could not finish this operation");
        consoleOutput.appendln(message);

        ExecutorJobResult error = generateJobResult(false, consoleOutput.toString(), message);

        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return error;
    }

    // terraform-spring-boot invokes the -json line consumer from inside its process-output reader
    // loop; any exception thrown out of the consumer propagates back into that loop, kills the
    // reader thread and fails the whole run with "Failed to capture process output stream",
    // discarding every line already read. A dropped structured-progress update is recoverable; a
    // killed reader is not. (LogsServiceRedis.sendLogs guards the plain-log consumer for the same
    // reason.)
    private Consumer<String> guardConsumer(Consumer<String> lineConsumer) {
        return line -> {
            try {
                lineConsumer.accept(line);
            } catch (Exception e) {
                log.warn("Skipping a terraform output line that could not be processed: {}", e.getMessage(), e);
            }
        };
    }

    private boolean prepareTerraformOperation(TerraformJob terraformJob, File executorTempDirectory, File terraformWorkingDirectory, Consumer<String> output)
            throws IOException, ExecutionException, InterruptedException {
        terraformClient.setRedirectErrorStream(true);

        if (!executePreInitScripts(terraformJob, terraformWorkingDirectory, output)) {
            log.warn("Skipping terraform init because before-init scripts failed for Job {}", terraformJob.getJobId());
            return false;
        }

        return executeTerraformInit(terraformJob, executorTempDirectory, terraformWorkingDirectory, output, output);
    }

    private boolean executeTerraformInit(TerraformJob terraformJob, File executorTempDirectory, File terraformWorkingDirectory, Consumer<String> output,
                                         Consumer<String> errorOutput) throws IOException, ExecutionException, InterruptedException {
        if (terraformJob.isShowHeader()) {
            initBanner(terraformJob, output);
        }

        TerraformProcessData terraformProcessData = getTerraformProcessData(terraformJob, terraformWorkingDirectory, executorTempDirectory);
        terraformProcessData.setTerraformEnvironmentVariables(terraformProcessData.getTerraformEnvironmentVariables());
        terraformProcessData.setTerraformVariables(new HashMap<>());

        // Binary cache: try to restore from cloud storage before init triggers a download.
        boolean binaryWasAlreadyCached = ensureBinaryCached(terraformJob);

        boolean initSuccessful;

        if (terraformJob.isShowHeader()) {
            initSuccessful = Boolean.TRUE.equals(terraformClient.init(terraformProcessData, output, errorOutput).get());
        } else {
            // Remote operations (CLI-driven runs) keep init quiet on success, but the
            // stream must still reach the step output when init fails; otherwise the
            // error is only visible in the executor log and the client sees an empty
            // step. Buffer the lines (stderr is merged into stdout via
            // setRedirectErrorStream) and flush them on failure.
            TextStringBuilder initOutput = new TextStringBuilder();
            Consumer<String> quietOutput = s -> {
                log.info(s);
                initOutput.appendln(s);
            };
            initSuccessful = Boolean.TRUE.equals(terraformClient.init(terraformProcessData, quietOutput, quietOutput).get());
            if (!initSuccessful) {
                output.accept(initOutput.toString());
            }
        }

        // Binary cache: if the binary was freshly downloaded (not previously in storage),
        // upload it to storage so the next pod can restore it.
        if (initSuccessful && !binaryWasAlreadyCached) {
            saveBinaryToCache(terraformJob);
        }

        log.warn("Terraform init Executed Successfully: {}", initSuccessful);
        Thread.sleep(5000);
        return initSuccessful;
    }

    /**
     * Ensure the terraform/tofu binary is available locally, restoring it from
     * cloud storage if possible. This avoids downloading from HashiCorp/GitHub
     * when a fresh executor pod starts.
     *
     * @return true if the binary was already cached (locally or in storage),
     *         false if it needs to be downloaded by the library and then cached.
     */
    private boolean ensureBinaryCached(TerraformJob terraformJob) {
        try {
            TerraformDownloader downloader = terraformClient.createTerraformDownloader();
            boolean tofu = terraformJob.isTofu();
            String resolvedVersion = tofu
                    ? downloader.resolveTofuVersion(terraformJob.getTerraformVersion())
                    : downloader.resolveTerraformVersion(terraformJob.getTerraformVersion());

            String binaryPath = downloader.getTerraformBinaryPath(resolvedVersion, tofu);
            File binaryFile = new File(binaryPath);

            // 1. Binary already exists locally (e.g. previous job with same version on this pod)
            if (binaryFile.exists()) {
                log.info("Binary already exists locally at {}, skipping cache check", binaryPath);
                return true;
            }

            // 2. Try restoring from cloud storage
            log.info("Binary not found locally, attempting to restore from cloud storage for version {}", resolvedVersion);
            boolean restored = terraformState.downloadTerraformBinary(resolvedVersion, tofu, binaryFile);
            if (restored) {
                log.info("Successfully restored binary from cloud storage to {}", binaryPath);
                return true;
            }

            // 3. Not in storage either — let the library download it, then we'll cache it after init
            log.info("Binary not found in cloud storage, will be downloaded by terraform client library");
            return false;
        } catch (Exception e) {
            log.warn("Binary cache check failed, falling back to normal download: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Save the terraform/tofu binary to cloud storage after it was freshly
     * downloaded by the library.
     */
    private void saveBinaryToCache(TerraformJob terraformJob) {
        try {
            TerraformDownloader downloader = terraformClient.createTerraformDownloader();
            boolean tofu = terraformJob.isTofu();
            String resolvedVersion = tofu
                    ? downloader.resolveTofuVersion(terraformJob.getTerraformVersion())
                    : downloader.resolveTerraformVersion(terraformJob.getTerraformVersion());

            String binaryPath = downloader.getTerraformBinaryPath(resolvedVersion, tofu);
            File binaryFile = new File(binaryPath);

            if (binaryFile.exists()) {
                log.info("Caching binary to cloud storage: {} version {}", tofu ? "tofu" : "terraform", resolvedVersion);
                terraformState.saveTerraformBinary(resolvedVersion, tofu, binaryFile);
            } else {
                log.warn("Binary file not found at {} after init, cannot cache to storage", binaryPath);
            }
        } catch (Exception e) {
            log.warn("Failed to cache binary to cloud storage: {}", e.getMessage());
        }
    }

    private HashMap<String, String> getWorkspaceParameters(HashMap<String, String> parameters) {
        return parameters != null ? parameters : new HashMap<>();
    }

    private Consumer<String> getStringConsumer(TextStringBuilder terraformOutput) {
        return responseOutput -> {
            log.info(responseOutput);
            terraformOutput.appendln(responseOutput);
        };
    }

    private void initBanner(TerraformJob terraformJob, Consumer<String> output) {
        AnsiFormat colorMessage = enableColorOutput ? new AnsiFormat(GREEN_TEXT(), BLACK_BACK(), BOLD()) : new AnsiFormat(WHITE_TEXT(), BLACK_BACK(), BOLD());
        output.accept(colorize(STEP_SEPARATOR, colorMessage));
        output.accept(
                colorize("Initializing Terrakube Job " + terraformJob.getJobId() + " Step " + terraformJob.getStepId(),
                        colorMessage));
        output.accept(colorize(String.format("Running %s ", getIaCType(terraformJob)) + terraformJob.getTerraformVersion(), colorMessage));
        output.accept(colorize("\n\n" + STEP_SEPARATOR, colorMessage));
        output.accept(colorize(String.format("Running %s Init: ", getIaCType(terraformJob)), colorMessage));
    }

    private String getIaCType(TerraformJob terraformJob) {
        return terraformJob.isTofu() ? "Tofu" : "Terraform";
    }

    private void showTerraformMessage(TerraformJob terraformJob, String operation, Consumer<String> output) throws InterruptedException {
        AnsiFormat colorMessage = enableColorOutput ? new AnsiFormat(GREEN_TEXT(), BLACK_BACK(), BOLD()) : new AnsiFormat(WHITE_TEXT(), BLACK_BACK(), BOLD());
        output.accept(colorize(STEP_SEPARATOR, colorMessage));
        output.accept(colorize(String.format("Running %s ", getIaCType(terraformJob)) + operation, colorMessage));
        output.accept(colorize(STEP_SEPARATOR, colorMessage));
        Thread.sleep(2000);
    }

    private TerraformProcessData getTerraformProcessData(
            TerraformJob terraformJob,
            File terraformWorkingDir,
            File workspaceRootDirectory
    ) {

        terraformState.getBackendStateFile(
                terraformJob.getOrganizationId(),
                terraformJob.getWorkspaceId(),
                terraformWorkingDir,
                terraformJob.getTerraformVersion()
        );

        File sshKeyFile = null;

        if (terraformJob.getVcsType() != null
                && terraformJob.getVcsType().startsWith("SSH")
                && terraformJob.getModuleSshKey() != null
                && !terraformJob.getModuleSshKey().isEmpty()) {

            sshKeyFile = getFile(workspaceRootDirectory, sshKeyFile);

            log.warn("1 - Using module SSH key from root workspace: {}",
                    sshKeyFile != null ? sshKeyFile.getAbsolutePath() : null);

        } else if (terraformJob.getVcsType() != null
                && terraformJob.getVcsType().startsWith("SSH")) {

            sshKeyFile = getSshFile(workspaceRootDirectory, terraformJob);

            log.warn("2 - Using SSH key from: {}",
                    sshKeyFile != null ? sshKeyFile.getAbsolutePath() : null);

        } else if (terraformJob.getModuleSshKey() != null
                && !terraformJob.getModuleSshKey().isEmpty()) {

            sshKeyFile = getFile(workspaceRootDirectory, sshKeyFile);

            log.warn("3 - Using module SSH key from root workspace: {}",
                    sshKeyFile != null ? sshKeyFile.getAbsolutePath() : null);

        } else {
            log.warn("Not using any SSH key to download modules");
        }

        return TerraformProcessData.builder()
                .terraformVersion(terraformJob.getTerraformVersion())
                .terraformVariables(terraformJob.getVariables())
                .terraformEnvironmentVariables(loadTempEnvironmentVariables(
                        workspaceRootDirectory,
                        terraformWorkingDir,
                        terraformJob
                ))
                .workingDirectory(terraformWorkingDir)
                .refresh(terraformJob.isRefresh())
                .refreshOnly(terraformJob.isRefreshOnly())
                .tofu(terraformJob.isTofu())
                .sshFile(sshKeyFile)
                .build();
    }

    private File getFile(File workspaceRootDirectory, File sshKeyFile) {

        if (workspaceRootDirectory == null) {
            log.error("Error SSH getFile - workspaceRootDirectory is null");
            return sshKeyFile;
        }

        String folderPath = String.format(SSH_DIRECTORY_MODULE, workspaceRootDirectory);

        File folder = new File(folderPath);

        if (!folder.exists() || !folder.isDirectory()) {
            log.error("Error SSH getFile - invalid SSH module folder='{}'", folder.getAbsolutePath());
            return sshKeyFile;
        }

        Collection<File> files = FileUtils.listFiles(folder, null, false);

        for (File file : files) {

            if (file.getName().startsWith("id_")) {
                sshKeyFile = file;
            }
        }

        return sshKeyFile;
    }

    private File getSshFile(File workspaceRootDirectory, TerraformJob terraformJob) {

        if (workspaceRootDirectory == null) {
            log.error("Error SSH getSshFile - workspaceRootDirectory is null");
            return null;
        }

        String sshFileName = terraformJob.getVcsType().split("~")[1];
        File sshDirectory = new File(String.format(SSH_DIRECTORY, workspaceRootDirectory));

        return new File(sshDirectory, sshFileName);
    }

    public HashMap<String, String> loadTempEnvironmentVariables(File workspaceRootDirectory, File workingDirectory, TerraformJob terraformJob) {
        String workingEnvTemp = workingDirectory.getAbsolutePath() + "/.terrakube_temp_env";
        Path pathEnv = Paths.get(workingEnvTemp);
        if (Files.exists(pathEnv)) {
            log.info("File .terrakube_env exists, loading environment variables to terraform/tofu process");
            try (BufferedReader reader = Files.newBufferedReader(pathEnv)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] split = line.split("=");
                    log.info("Loading {}", split[0]);
                    terraformJob.getEnvironmentVariables().put(split[0], split[1]);
                }
            } catch (IOException e) {
                log.error("Error reading file: {}", e.getMessage());
            }
        } else {
            log.info("File terrakube_env does not exist");
        }

        if (terraformJob.getEnvironmentVariables().containsKey("ENABLE_DYNAMIC_CREDENTIALS_AWS")) {
            log.info("AWS_WEB_IDENTITY_TOKEN_FILE updating location to: {}", workspaceRootDirectory.getAbsolutePath() + "/terrakube_config_dynamic_credentials_aws.txt");
            terraformJob.getEnvironmentVariables().put("AWS_WEB_IDENTITY_TOKEN_FILE", workspaceRootDirectory.getAbsolutePath() + "/terrakube_config_dynamic_credentials_aws.txt");
        }

        if (terraformJob.getEnvironmentVariables().containsKey("ENABLE_DYNAMIC_CREDENTIALS_GCP")) {
            log.info("GOOGLE_APPLICATION_CREDENTIALS updating location to: {}", workspaceRootDirectory.getAbsolutePath() + "/terrakube_config_dynamic_credentials.json");
            terraformJob.getEnvironmentVariables().put("GOOGLE_APPLICATION_CREDENTIALS", workspaceRootDirectory.getAbsolutePath() + "/terrakube_config_dynamic_credentials.json");
        }

        return terraformJob.getEnvironmentVariables();
    }
}
