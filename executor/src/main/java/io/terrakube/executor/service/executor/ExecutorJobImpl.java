package io.terrakube.executor.service.executor;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.TextStringBuilder;
import io.terrakube.executor.service.logs.StepOutputArchiveService;
import io.terrakube.executor.service.logs.StepOutputBuffer;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.workspace.SetupWorkspace;
import io.terrakube.executor.service.workspace.WorkspaceException;
import io.terrakube.executor.service.shutdown.ShutdownServiceImpl;
import io.terrakube.executor.service.status.UpdateJobStatus;
import io.terrakube.executor.service.terraform.ContextRevision;
import io.terrakube.executor.service.terraform.TerraformExecutor;
import io.terrakube.executor.service.workspace.security.JobContextHolder;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.Charset;
import java.util.function.Consumer;

import io.terrakube.executor.service.logs.ExecutorLogsProperties;
import io.terrakube.executor.service.logs.LogsConsumer;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.opa.OpaExecutorService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class ExecutorJobImpl implements ExecutorJob {

    SetupWorkspace setupWorkspace;
    TerraformExecutor terraformExecutor;
    UpdateJobStatus updateJobStatus;
    ExecutorFlagsProperties executorFlagsProperties;
    ShutdownServiceImpl shutdownService;
    ScriptEngineService scriptEngineService;
    ApplicationEventPublisher eventPublisher;
    JobExecutionWatchdog jobExecutionWatchdog;
    ExecutorCapacityGate executorCapacityGate;
    RedisTemplate<String, Object> redisTemplate;
    OpaExecutorService opaExecutorService;
    ProcessLogs logsService;
    WorkspaceSecurity workspaceSecurity;
    private ExecutorLogsProperties logsProperties = new ExecutorLogsProperties();
    private StepOutputArchiveService stepOutputArchiveService;

    @Autowired(required = false)
    void setStepOutputArchiveService(StepOutputArchiveService stepOutputArchiveService) {
        this.stepOutputArchiveService = stepOutputArchiveService;
    }

    @Autowired(required = false)
    void setLogsProperties(ExecutorLogsProperties logsProperties) {
        this.logsProperties = logsProperties;
    }

    public ExecutorJobImpl(
            SetupWorkspace setupWorkspace,
            TerraformExecutor terraformExecutor,
            UpdateJobStatus updateJobStatus,
            ExecutorFlagsProperties executorFlagsProperties,
            ShutdownServiceImpl shutdownService,
            ScriptEngineService scriptEngineService,
            ApplicationEventPublisher eventPublisher,
            JobExecutionWatchdog jobExecutionWatchdog,
            ExecutorCapacityGate executorCapacityGate,
            RedisTemplate<String, Object> redisTemplate) {
        this(setupWorkspace, terraformExecutor, updateJobStatus, executorFlagsProperties,
                shutdownService, scriptEngineService, eventPublisher, jobExecutionWatchdog,
                executorCapacityGate, redisTemplate, null, null, null);
    }

    public ExecutorJobImpl(
            SetupWorkspace setupWorkspace,
            TerraformExecutor terraformExecutor,
            UpdateJobStatus updateJobStatus,
            ExecutorFlagsProperties executorFlagsProperties,
            ShutdownServiceImpl shutdownService,
            ScriptEngineService scriptEngineService,
            ApplicationEventPublisher eventPublisher,
            JobExecutionWatchdog jobExecutionWatchdog,
            ExecutorCapacityGate executorCapacityGate,
            RedisTemplate<String, Object> redisTemplate,
            @Autowired(required = false) OpaExecutorService opaExecutorService) {
        this(setupWorkspace, terraformExecutor, updateJobStatus, executorFlagsProperties,
                shutdownService, scriptEngineService, eventPublisher, jobExecutionWatchdog,
                executorCapacityGate, redisTemplate, opaExecutorService, null, null);
    }

    public ExecutorJobImpl(
            SetupWorkspace setupWorkspace,
            TerraformExecutor terraformExecutor,
            UpdateJobStatus updateJobStatus,
            ExecutorFlagsProperties executorFlagsProperties,
            ShutdownServiceImpl shutdownService,
            ScriptEngineService scriptEngineService,
            ApplicationEventPublisher eventPublisher,
            JobExecutionWatchdog jobExecutionWatchdog,
            ExecutorCapacityGate executorCapacityGate,
            RedisTemplate<String, Object> redisTemplate,
            @Autowired(required = false) OpaExecutorService opaExecutorService,
            @Autowired(required = false) ProcessLogs logsService) {
        this(setupWorkspace, terraformExecutor, updateJobStatus, executorFlagsProperties,
                shutdownService, scriptEngineService, eventPublisher, jobExecutionWatchdog,
                executorCapacityGate, redisTemplate, opaExecutorService, logsService, null);
    }

    @Autowired
    public ExecutorJobImpl(
            SetupWorkspace setupWorkspace,
            TerraformExecutor terraformExecutor,
            UpdateJobStatus updateJobStatus,
            ExecutorFlagsProperties executorFlagsProperties,
            ShutdownServiceImpl shutdownService,
            ScriptEngineService scriptEngineService,
            ApplicationEventPublisher eventPublisher,
            JobExecutionWatchdog jobExecutionWatchdog,
            ExecutorCapacityGate executorCapacityGate,
            RedisTemplate<String, Object> redisTemplate,
            @Autowired(required = false) OpaExecutorService opaExecutorService,
            @Autowired(required = false) ProcessLogs logsService,
            @Autowired(required = false) WorkspaceSecurity workspaceSecurity) {
        this.setupWorkspace = setupWorkspace;
        this.terraformExecutor = terraformExecutor;
        this.updateJobStatus = updateJobStatus;
        this.executorFlagsProperties = executorFlagsProperties;
        this.shutdownService = shutdownService;
        this.scriptEngineService = scriptEngineService;
        this.eventPublisher = eventPublisher;
        this.jobExecutionWatchdog = jobExecutionWatchdog;
        this.executorCapacityGate = executorCapacityGate;
        this.redisTemplate = redisTemplate;
        this.opaExecutorService = opaExecutorService;
        this.logsService = logsService;
        this.workspaceSecurity = workspaceSecurity;
    }

    @Async
    @Override

    public void createJob(TerraformJob terraformJob) {
        log.info("Create Job for Organization {} Workspace {} ", terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
        jobExecutionWatchdog.markBusy(terraformJob);
        File terraformWorkingDir = null;
        try {
            ContextRevision.begin(terraformJob.getJobId(), terraformJob.getExecutionEpoch());
            JobContextHolder.set(terraformJob);
            try {
                terraformWorkingDir = setupWorkspace.prepareWorkspace(terraformJob);
            } catch (WorkspaceException e) {
                log.error("Failed to prepare work dir: ", e);
                try (StepOutputBuffer output = StepOutputBuffer.ofMessage("Failed to prepare work dir\n")) {
                    updateJobStatus.setCompletedStatus(false, false, -1, terraformJob, output, e.getMessage(), null, "");
                }
                return;
            }
            try {
                executeJob(terraformJob, terraformWorkingDir);
            } catch (Exception e) {
                // executeJob has no throws clause of its own, so anything reaching here is an
                // unexpected failure (e.g. the terraform/tofu binary download erroring out) deep
                // inside the terraform executor - without this catch it propagates out of this
                // @Async method uncaught, Spring's SimpleAsyncUncaughtExceptionHandler just logs
                // it, and the job is left stuck in "running" forever with no failure status and
                // nothing to retry.
                log.error("Unexpected error executing job for Organization {} Workspace {}: {}",
                        terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), e.getMessage(), e);
                try (StepOutputBuffer output = StepOutputBuffer.ofMessage("Unexpected error executing job\n")) {
                    updateJobStatus.setCompletedStatus(false, false, -1, terraformJob, output, e.getMessage(), null, "");
                }
            }
        } finally {
            // After executeJob, so the structured-output drain has already minted every revision.
            ContextRevision.end(terraformJob.getJobId(), terraformJob.getExecutionEpoch());
            JobContextHolder.clear();
            if (workspaceSecurity != null) {
                workspaceSecurity.cleanTerraformCredentials();
            }
            try {
                if (terraformWorkingDir != null) {
                    FileUtils.cleanDirectory(terraformWorkingDir);
                }
            } catch (IOException e) {
                log.error(e.getMessage());
            }

            jobExecutionWatchdog.markFree();
            executorCapacityGate.release();
            if (executorFlagsProperties.isEphemeral()) {
                shutdownService.shutdownApplication();
            } else {
                publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
                publishExecutorAvailable();
            }
        }
    }

    private void publishReadiness(ReadinessState state) {
        try {
            AvailabilityChangeEvent.publish(eventPublisher, this, state);
        } catch (Exception e) {
            // A misbehaving listener must never block a job from starting, and must never
            // leave this method's finally block before the pod is marked free again.
            log.error("Failed to publish readiness state {}: {}", state, e.getMessage());
        }
    }

    // Doorbell for the api module's FIFO dispatch queue - lets a waiting job retry within about
    // one Redis round-trip instead of its own 30s interval. Channel name is duplicated as a
    // literal in ExecutorAvailabilityListener (api module has no shared module with this one).
    private void publishExecutorAvailable() {
        try {
            redisTemplate.convertAndSend("terrakube:executor-available", "");
        } catch (DataAccessException e) {
            // A missed wake-up only costs latency - the waiting job's own 30s retry still covers it.
            log.error("Failed to publish executor-available signal: {}", e.getMessage());
        }
    }

    private void executeJob(TerraformJob terraformJob, File terraformWorkingDir) {
        String commitId = "000000000";
        ExecutorJobResult terraformResult = new ExecutorJobResult();

        if (!terraformJob.getBranch().equals("remote-content"))
            commitId = getCommitId(terraformWorkingDir);

        updateJobStatus.setRunningStatus(terraformJob, commitId);

        switch (terraformJob.getType()) {
            case "terraformPlanDestroy":
            case "terraformPlan":
                log.info("Execute Plan for Organization {} Workspace {} ", terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
                terraformResult = terraformExecutor.plan(terraformJob, terraformWorkingDir, terraformJob.getType().equals("terraformPlanDestroy"));
                break;
            case "terraformApply":
                log.info("Execute Apply for Organization {} Workspace {} ", terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
                terraformResult = terraformExecutor.apply(terraformJob, terraformWorkingDir);
                break;
            case "terraformDestroy":
                log.info("Execute Destroy for Organization {} Workspace {} ", terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
                terraformResult = terraformExecutor.destroy(terraformJob, terraformWorkingDir);
                break;
            case "policyEvaluation":
                log.info("Execute Policy Evaluation for Organization {} Workspace {} ", terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
                if (opaExecutorService != null) {
                    terraformResult = opaExecutorService.evaluateJob(terraformJob, terraformWorkingDir);
                } else {
                    terraformResult = new ExecutorJobResult();
                    terraformResult.setOutputBuffer(StepOutputBuffer.ofMessage("OPA Executor Service is not available."));
                    terraformResult.setOutputErrorLog("OpaExecutorService is null.");
                    terraformResult.setSuccessfulExecution(false);
                    terraformResult.setExitCode(1);
                }
                break;
            case "customScripts":
            case "approval":
                terraformResult = executeScripts(terraformJob, terraformWorkingDir, terraformResult);
                break;
            default:
                terraformResult = new ExecutorJobResult();
                terraformResult.setOutputBuffer(StepOutputBuffer.ofMessage("Command Completed"));
                terraformResult.setOutputErrorLog("Command type not defined");
                terraformResult.setSuccessfulExecution(false);
                break;
        }

        boolean executionSuccess = terraformResult.isSuccessfulExecution();
        // Every step hands over its console output in a buffer, which is closed here once it is saved.
        try (StepOutputBuffer outputBuffer = terraformResult.getOutputBuffer()) {
            updateJobStatus.setCompletedStatus(executionSuccess, terraformResult.isPlan, terraformResult.getExitCode(), terraformJob, outputBuffer, terraformResult.getOutputErrorLog(), terraformResult.getPlanFile(), commitId, terraformResult.isHasSoftMandatoryViolations(), terraformResult.getApprovalTeam(), terraformResult.getDeliveryState(), terraformResult.getDeliveryRevision());
        }
    }

    private ExecutorJobResult executeScripts(TerraformJob terraformJob, File terraformWorkingDir, ExecutorJobResult terraformResult) {
        log.info("Execute Groovy Script for Organization {} Workspace {} ", terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
        StepOutputBuffer scriptOutput = StepOutputBuffer.forStep(logsProperties.getStepOutputMemoryBytes(), terraformWorkingDir);
        if (stepOutputArchiveService != null) {
            stepOutputArchiveService.start(scriptOutput, terraformJob);
        }
        TextStringBuilder scriptErrorOutput = new TextStringBuilder();
        Consumer<String> output;
        if (logsService != null) {
            output = LogsConsumer.builder()
                    .jobId(Integer.valueOf(terraformJob.getJobId()))
                    .terraformOutput(scriptOutput)
                    .stepId(terraformJob.getStepId())
                    .processLogs(logsService)
                    .lineNumber(new AtomicInteger(0))
                    .build();
        } else {
            output = outputScripts -> {
                if (logsProperties.isEchoToServiceLog()) {
                    log.info(outputScripts);
                }
                scriptOutput.appendln(outputScripts);
            };
        }
        boolean executionSuccess;
        boolean handedOver = false;
        try {
            executionSuccess = scriptEngineService.execute(terraformJob, terraformJob.getCommandList(), terraformWorkingDir, output);
            terraformResult.setOutputBuffer(scriptOutput);
            handedOver = true;
        } finally {
            // Also for an Error: the buffer may already be a file on the scratch disk.
            if (!handedOver) {
                scriptOutput.close();
            }
        }
        terraformResult.setOutputErrorLog(scriptErrorOutput.toString());
        terraformResult.setSuccessfulExecution(executionSuccess);
        return terraformResult;
    }

    private static String getCommitId(File workspaceFolder) {
        String commitId = "";
        try {
            final File commitInformation = new File(String.format("%s/commitHash.info", workspaceFolder.getCanonicalPath()));
            final InputStream commitIdStream = new DataInputStream(new FileInputStream(commitInformation));
            commitId = IOUtils.toString(commitIdStream, Charset.defaultCharset());
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        return commitId;
    }
}
