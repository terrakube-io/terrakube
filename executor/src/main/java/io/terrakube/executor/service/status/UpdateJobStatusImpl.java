package io.terrakube.executor.service.status;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobRequest;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.organization.job.step.StepAttributes;
import io.terrakube.client.model.organization.job.step.StepRequest;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
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

            job.getAttributes().setStatus("running");
            job.getAttributes().setCommitId(commitId);

            JobRequest jobRequest = new JobRequest();
            jobRequest.setData(job);

            terrakubeClient.updateJob(jobRequest, job.getRelationships().getOrganization().getData().getId(), job.getId());

            updateStepLogs(terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId());
        }
    }

    @Override
    public void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob terraformJob, String jobOutput, String jobErrorOutput, String jobPlan, String commitId) {
        if (!executorFlagsProperties.isDisableAcknowledge()) {
            updateStepStatus(successful, terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), jobOutput, jobErrorOutput);
            if(!isJobCancelled(terraformJob))
                updateJobStatus(successful, isPlan, exitCode, terraformJob.getOrganizationId(), terraformJob.getJobId(), terraformJob.getStepId(), jobOutput, jobErrorOutput, jobPlan, commitId);
        }
    }

    private boolean isJobCancelled(TerraformJob terraformJob){
        Job job = terrakubeClient.getJobById(terraformJob.getOrganizationId(), terraformJob.getJobId()).getData();
        if(job.getAttributes().getStatus().equals("cancelled")) {
            log.warn("Job {} was cancelled when running executor", terraformJob.getJobId());
            return true;
        }
        else {
            log.info("Job {} is still active", terraformJob.getJobId());
            return false;
        }
    }

    private void updateJobStatus(boolean successful, boolean isPlan, int exitCode, String organizationId, String jobId, String stepId, String jobOutput, String jobErrorOutput, String jobPlan, String commitId) {
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
                        status = "pending";
                        break;
                }
            }
        } else {
            status = "failed";
        }
        job.getAttributes().setStatus(status);
        job.getAttributes().setPlanChanges(planChanges);
        log.info("JobStatus: {}", status);
        log.info("StepId: {}", stepId);
        log.info("output: {}", jobOutput.length());
        log.info("outputError: {}", jobErrorOutput.length());

        job.getAttributes().setOutput(
                job.getAttributes().getOutput() == null ? "" : job.getAttributes().getOutput() + " Step " + stepId + " completed\n"
        );
        job.getAttributes().setTerraformPlan(jobPlan);
        job.getAttributes().setCommitId(commitId);

        JobRequest jobRequest = new JobRequest();
        jobRequest.setData(job);

        terrakubeClient.updateJob(jobRequest, job.getRelationships().getOrganization().getData().getId(), job.getId());
    }

    private void updateStepStatus(boolean status, String organizationId, String jobId, String stepId, String jobOutput, String jobErrorOutput) {
        StepAttributes stepAttributes = new StepAttributes();
        stepAttributes.setOutput(this.terraformOutput.saveOutput(organizationId, jobId, stepId, jobOutput, jobErrorOutput));
        stepAttributes.setStatus(status ? "completed": "failed");

        Step step = new Step();
        step.setId(stepId);
        step.setType("step");
        step.setAttributes(stepAttributes);
        StepRequest stepRequest = new StepRequest();
        stepRequest.setData(step);

        terrakubeClient.updateStep(stepRequest, organizationId, jobId, stepId);
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
