package io.terrakube.executor.service.executor;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.TextStringBuilder;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.workspace.SetupWorkspace;
import io.terrakube.executor.service.workspace.WorkspaceException;
import io.terrakube.executor.service.shutdown.ShutdownServiceImpl;
import io.terrakube.executor.service.status.UpdateJobStatus;
import io.terrakube.executor.service.terraform.TerraformExecutor;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.Charset;
import java.util.function.Consumer;

@AllArgsConstructor
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

    @Async
    @Override
    public void createJob(TerraformJob terraformJob) {
        log.info("Create Job for Organization {} Workspace {} ", terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
        // Pulls this pod out of the executor Service's endpoints while a job is running,
        // since the pool underneath this @Async method only runs one job at a time.
        publishReadiness(ReadinessState.REFUSING_TRAFFIC);
        jobExecutionWatchdog.markBusy();
        File terraformWorkingDir = null;
        try {
            try {
                terraformWorkingDir = setupWorkspace.prepareWorkspace(terraformJob);
            } catch (WorkspaceException e) {
                log.error("Failed to prepare work dir: ", e);
                updateJobStatus.setCompletedStatus(false, false, -1, terraformJob, "Failed to prepare work dir\n", e.getMessage(), null, "");
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
                updateJobStatus.setCompletedStatus(false, false, -1, terraformJob, "Unexpected error executing job\n", e.getMessage(), null, "");
            }
        } finally {
            try {
                if (terraformWorkingDir != null) {
                    FileUtils.cleanDirectory(terraformWorkingDir);
                }
            } catch (IOException e) {
                log.error(e.getMessage());
            }

            jobExecutionWatchdog.markFree();
            if (executorFlagsProperties.isEphemeral()) {
                shutdownService.shutdownApplication();
            } else {
                publishReadiness(ReadinessState.ACCEPTING_TRAFFIC);
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
            case "customScripts":
            case "approval":
                log.info("Execute Groovy Script for Organization {} Workspace {} ", terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
                TextStringBuilder scriptOutput = new TextStringBuilder();
                TextStringBuilder scriptErrorOutput = new TextStringBuilder();
                Consumer<String> output = outputScripts -> scriptOutput.appendln(outputScripts);
                boolean executionSuccess = scriptEngineService.execute(terraformJob, terraformJob.getCommandList(), terraformWorkingDir, output);
                terraformResult.setOutputLog(scriptOutput.toString());
                terraformResult.setOutputErrorLog(scriptErrorOutput.toString());
                terraformResult.setSuccessfulExecution(executionSuccess);
                break;
            default:
                terraformResult = new ExecutorJobResult();
                terraformResult.setOutputLog("Command Completed");
                terraformResult.setOutputErrorLog("Command type not defined");
                terraformResult.setSuccessfulExecution(false);
                break;
        }

        boolean executionSuccess = terraformResult.isSuccessfulExecution();
        updateJobStatus.setCompletedStatus(executionSuccess, terraformResult.isPlan, terraformResult.getExitCode(), terraformJob, terraformResult.getOutputLog(), terraformResult.getOutputErrorLog(), terraformResult.getPlanFile(), commitId);
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
