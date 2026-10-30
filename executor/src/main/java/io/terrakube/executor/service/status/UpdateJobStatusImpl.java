package io.terrakube.executor.service.status;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import feign.FeignException;
import org.springframework.stereotype.Service;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.JobRequest;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.organization.job.step.StepAttributes;
import io.terrakube.client.model.organization.job.step.StepRequest;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.artifact.ArtifactPackagingService;
import io.terrakube.executor.service.logs.StepOutputBuffer;
import io.terrakube.executor.service.mode.TerraformJob;

@Slf4j
@Service
@AllArgsConstructor
public class UpdateJobStatusImpl implements UpdateJobStatus {


    private TerrakubeClient terrakubeClient;

    private TerraformState terraformOutput;

    private ExecutorFlagsProperties executorFlagsProperties;

    private TerraformOutputPathService terraformOutputPathService;

    @Override
    public void setRunningStatus(TerraformJob terraformJob, String commitId) {
        if (!executorFlagsProperties.isDisableAcknowledge()) {
            Job job = null;
            for(int retry=0; retry<5; retry++) {
                job = terrakubeClient.getJobById(terraformJob.getOrganizationId(), terraformJob.getJobId()).getData();

                if(!job.getRelationships().getStep().getData().isEmpty()){
                    log.info("Step list is not empty...");
                    break;
                } else {
                    log.error("Step list is empty for some reason...");
                }
            }

            if (job.getAttributes().getStatus().equals("rejected")) {
                log.warn("Job {} is rejected, keeping job status unchanged", terraformJob.getJobId());
            } else {
                job.getAttributes().setStatus("running");
                job.getAttributes().setCommitId(commitId);

                Job updateJob = new Job();
                updateJob.setId(job.getId());
                updateJob.setType("job");

                JobAttributes updateAttributes = new JobAttributes();
                updateAttributes.setStatus("running");
                updateAttributes.setCommitId(commitId);
                updateJob.setAttributes(updateAttributes);

                JobRequest jobRequest = new JobRequest();
                jobRequest.setData(updateJob);

                terrakubeClient.updateJob(jobRequest, job.getRelationships().getOrganization().getData().getId(), job.getId());
            }

            updateStepLogs(terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId());
        }
    }

    @Override
    public void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob terraformJob, StepOutputBuffer jobOutput, String jobErrorOutput, String jobPlan, String commitId, boolean hasSoftMandatoryViolations, String approvalTeam, String deliveryState, Long deliveryRevision) {
        if (jobOutput == null) {
            // A step that produced no buffer still has to be completed, otherwise the job stays running.
            try (StepOutputBuffer empty = new StepOutputBuffer()) {
                setCompletedStatus(successful, isPlan, exitCode, terraformJob, empty, jobErrorOutput, jobPlan, commitId, hasSoftMandatoryViolations, approvalTeam, deliveryState, deliveryRevision);
            }
            return;
        }
        if (!executorFlagsProperties.isDisableAcknowledge()) {
            String currentJobStatus = getCurrentJobStatus(terraformJob);
            // A rejected run keeps showing its approval step as failed: onReject command
            // output is still saved, but its success must not repaint the step as completed.
            boolean rejected = currentJobStatus.equals("rejected");
            boolean outputSaved = updateStepStatus(successful && !rejected, terraformJob, jobOutput, jobErrorOutput, deliveryState, deliveryRevision);
            if (rejected || currentJobStatus.equals("cancelled"))
                log.warn("Job {} was {} when running executor, skipping job status update", terraformJob.getJobId(), currentJobStatus);
            else
                updateJobStatus(successful && outputSaved, isPlan, exitCode, terraformJob, jobOutput.sizeInBytes(), jobErrorOutput, jobPlan, commitId, hasSoftMandatoryViolations, approvalTeam);
        }
    }

    private String getCurrentJobStatus(TerraformJob terraformJob) {
        return terrakubeClient.getJobById(terraformJob.getOrganizationId(), terraformJob.getJobId())
                .getData().getAttributes().getStatus();
    }

    private void updateJobStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob terraformJob, long jobOutputLength, String jobErrorOutput, String jobPlan, String commitId, boolean hasSoftMandatoryViolations, String approvalTeam) {
        String organizationId = terraformJob.getOrganizationId();
        String jobId = terraformJob.getJobId();
        String stepId = terraformJob.getStepId();
        Job job = terrakubeClient.getJobById(organizationId, jobId).getData();
        String status = "";
        boolean planChanges = true;
        if (successful) {
            status = "pending";

            if (isPlan) {
                switch (exitCode){
                    case 0:
                        status = "completed";
                        planChanges = false;
                        break;
                    case 1:
                        status = "failed";
                        planChanges = false;
                        break;
                    case 2:
                        // A plan that finds changes normally waits "pending" for an approval/apply
                        // step. But if this plan is the job's only step (a plan-only template),
                        // there's nothing left to run it against - leaving it "pending" forever
                        // misreports a finished job as still in progress.
                        status = job.getRelationships().getStep().getData().size() > 1 ? "pending" : "completed";
                        break;
                }
                if (hasSoftMandatoryViolations && exitCode != 1) {
                    status = "waitingApproval";
                    if (approvalTeam != null && !approvalTeam.isBlank()) {
                        job.getAttributes().setApprovalTeam(approvalTeam);
                    }
                }
            }
        } else {
            status = "failed";
        }
        job.getAttributes().setStatus(status);
        job.getAttributes().setPlanChanges(planChanges);
        log.info("JobStatus: {}", status);
        log.info("StepId: {}", stepId);
        log.info("output: {}", jobOutputLength);
        log.info("outputError: {}", jobErrorOutput.length());

        // Append-only per-step marker. The null check guards the first step (no prior output),
        // it must not also swallow the marker itself - the parenthesised ternary is the base
        // string, the marker is always appended.
        String existingOutput = job.getAttributes().getOutput() == null ? "" : job.getAttributes().getOutput();
        String stepOutput = existingOutput + " Step " + stepId + " completed\n";
        job.getAttributes().setOutput(stepOutput);
        job.getAttributes().setTerraformPlan(jobPlan);
        job.getAttributes().setCommitId(commitId);

        Job updateJob = new Job();
        updateJob.setId(job.getId());
        updateJob.setType("job");

        JobAttributes updateAttributes = new JobAttributes();
        updateAttributes.setStatus(status);
        updateAttributes.setPlanChanges(planChanges);
        updateAttributes.setOutput(stepOutput);
        updateAttributes.setTerraformPlan(jobPlan);
        // Plan artifacts are recorded together with the plan file, in the one update that completes
        // the plan step. Only sent when the feature is in use, so every other job is unaffected (an
        // API from before this field existed would refuse an unknown attribute). When a plan in such
        // a job has no artifacts, an empty value replaces the earlier plan's.
        if (isPlan && (terraformJob.getArtifactsUrl() != null || ArtifactPackagingService.isConfigured(terraformJob))) {
            updateAttributes.setTerraformPlanArtifacts(terraformJob.getArtifactsUrl() == null ? "" : terraformJob.getArtifactsUrl());
            updateAttributes.setTerraformPlanArtifactsChecksum(terraformJob.getArtifactsChecksum() == null ? "" : terraformJob.getArtifactsChecksum());
        }
        updateAttributes.setCommitId(commitId);
        if (hasSoftMandatoryViolations && exitCode != 1 && approvalTeam != null && !approvalTeam.isBlank()) {
            updateAttributes.setApprovalTeam(approvalTeam);
        }
        updateJob.setAttributes(updateAttributes);

        JobRequest jobRequest = new JobRequest();
        jobRequest.setData(updateJob);

        terrakubeClient.updateJob(jobRequest, job.getRelationships().getOrganization().getData().getId(), job.getId());
    }

    /**
     * Saves the output of the step and completes the step. Returns false when the final output could not be
     * saved but parts of it are in storage: the step is then completed as failed without a saved output, so
     * the API serves the parts with the incomplete notice. Saving a short message in its place would hide them.
     */
    private boolean updateStepStatus(boolean status, TerraformJob terraformJob, StepOutputBuffer jobOutput, String jobErrorOutput, String deliveryState, Long deliveryRevision) {
        String organizationId = terraformJob.getOrganizationId();
        String jobId = terraformJob.getJobId();
        String stepId = terraformJob.getStepId();
        // The parts written while the step ran are only for a step that never gets here. Stop them before
        // the final upload, which has the whole output, and remove them once it has succeeded.
        int archivedParts = jobOutput.finishArchiving();
        String output;
        boolean outputSaved = true;
        try {
            output = this.terraformOutput.saveBufferedOutput(organizationId, jobId, stepId, jobOutput, jobErrorOutput);
        } catch (RuntimeException e) {
            if (archivedParts == 0) {
                throw e;
            }
            log.error("Could not save the output of step {}, its {} parts stay in storage: {}", stepId, archivedParts, e.getMessage());
            output = null;
            outputSaved = false;
        }
        if (outputSaved && archivedParts > 0) {
            try {
                this.terraformOutput.deleteOutputParts(organizationId, jobId, stepId);
            } catch (RuntimeException e) {
                log.warn("Could not delete the output parts of step {}: {}", stepId, e.getMessage());
            }
        }
        // The step keeps the output path it was given when it started, so with no output saved here the API
        // finds no final output and serves the parts.
        String stepStatus = status && outputSaved ? "completed" : "failed";

        StepDeliveryAttributes stepAttributes = new StepDeliveryAttributes();
        stepAttributes.setOutput(output);
        stepAttributes.setStatus(stepStatus);
        stepAttributes.setExecutionDurationMs(terraformJob.getExecutionDurationMs());
        stepAttributes.setExecutionPhase(terraformJob.getExecutionPhase());
        if (deliveryState != null) {
            stepAttributes.setDeliveryState(deliveryState);
            stepAttributes.setDeliveryTimestamp(System.currentTimeMillis());
            stepAttributes.setDeliveryRevision(deliveryRevision);
        }

        Step step = new Step();
        step.setId(stepId);
        step.setType("step");
        step.setAttributes(stepAttributes);
        StepRequest stepRequest = new StepRequest();
        stepRequest.setData(step);

        try {
            terrakubeClient.updateStep(stepRequest, organizationId, jobId, stepId);
        } catch (FeignException e) {
            if ((deliveryState != null || terraformJob.getExecutionDurationMs() != null) && (e.status() == 400 || e.status() == 404)) {
                log.warn("Step PATCH with delivery/timing attributes rejected (status {}) by API for step {}, retrying without these attributes (older API compatibility)",
                        e.status(), stepId);
                StepAttributes fallbackAttributes = new StepAttributes();
                fallbackAttributes.setOutput(output);
                fallbackAttributes.setStatus(stepStatus);

                Step fallbackStep = new Step();
                fallbackStep.setId(stepId);
                fallbackStep.setType("step");
                fallbackStep.setAttributes(fallbackAttributes);

                StepRequest fallbackRequest = new StepRequest();
                fallbackRequest.setData(fallbackStep);

                terrakubeClient.updateStep(fallbackRequest, organizationId, jobId, stepId);
            } else {
                throw e;
            }
        }
        return outputSaved;
    }

    private void updateStepLogs(String organizationId, String jobId, String stepId) {
        StepAttributes stepAttributes = new StepAttributes();
        stepAttributes.setStatus("running");
        stepAttributes.setOutput(terraformOutputPathService.getOutputPath(organizationId, jobId, stepId));

        Step step = new Step();
        step.setId(stepId);
        step.setType("step");
        step.setAttributes(stepAttributes);
        StepRequest stepRequest = new StepRequest();
        stepRequest.setData(step);

        terrakubeClient.updateStep(stepRequest, organizationId, jobId, stepId);
    }
}
