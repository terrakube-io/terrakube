package io.terrakube.executor.service.status;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.generic.Resource;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.JobRequest;
import io.terrakube.client.model.organization.job.OrganizationData;
import io.terrakube.client.model.organization.job.Relationships;
import io.terrakube.client.model.organization.job.StepData;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.organization.job.step.StepRequest;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.TerraformJob;

@ExtendWith(MockitoExtension.class)
public class UpdateJobStatusImplTest {

    TerrakubeClient terrakubeClient;
    TerraformState terraformState;
    ExecutorFlagsProperties executorFlagsProperties;
    TerraformOutputPathService terraformOutputPathService;

    @BeforeEach
    public void setup() {
        terrakubeClient = mock(TerrakubeClient.class);
        terraformState = mock(TerraformState.class);
        executorFlagsProperties = mock(ExecutorFlagsProperties.class);
        terraformOutputPathService = mock(TerraformOutputPathService.class);
        doReturn(false).when(executorFlagsProperties).isDisableAcknowledge();
    }

    private UpdateJobStatusImpl subject() {
        return new UpdateJobStatusImpl(terrakubeClient, terraformState, executorFlagsProperties, terraformOutputPathService);
    }

    private TerraformJob terraformJob() {
        TerraformJob terraformJob = new TerraformJob();
        terraformJob.setOrganizationId("ze-org");
        terraformJob.setJobId("4711");
        terraformJob.setStepId("ze-step");
        return terraformJob;
    }

    private Job stubJobWithStatus(String status) {
        return stubJobWithStatus(status, 1);
    }

    private Job stubJobWithStatus(String status, int stepCount) {
        Resource organization = new Resource();
        organization.setId("ze-org");
        OrganizationData organizationData = new OrganizationData();
        organizationData.setData(organization);

        List<Resource> steps = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            Resource stepResource = new Resource();
            stepResource.setId("ze-step-" + i);
            steps.add(stepResource);
        }
        StepData stepData = new StepData();
        stepData.setData(steps);

        Relationships relationships = new Relationships();
        relationships.setOrganization(organizationData);
        relationships.setStep(stepData);

        JobAttributes attributes = new JobAttributes();
        attributes.setStatus(status);

        Job job = new Job();
        job.setId("4711");
        job.setAttributes(attributes);
        job.setRelationships(relationships);

        ResponseWithInclude<Job, Step> response = new ResponseWithInclude<>();
        response.setData(job);
        doReturn(response).when(terrakubeClient).getJobById("ze-org", "4711");
        return job;
    }

    @Test
    public void setCompletedStatusOnRejectedJobMarksStepFailedAndSkipsJob() {
        stubJobWithStatus("rejected");
        doReturn("output-url").when(terraformState).saveOutput(anyString(), anyString(), anyString(), anyString(), anyString());

        subject().setCompletedStatus(true, false, 0, terraformJob(), "output", "", "", "0000000");

        ArgumentCaptor<StepRequest> stepCaptor = ArgumentCaptor.forClass(StepRequest.class);
        verify(terrakubeClient, times(1)).updateStep(stepCaptor.capture(), anyString(), anyString(), anyString());
        Assertions.assertEquals("failed", stepCaptor.getValue().getData().getAttributes().getStatus());
        verify(terrakubeClient, never()).updateJob(any(JobRequest.class), anyString(), anyString());
    }

    @Test
    public void setCompletedStatusOnCancelledJobKeepsStepResultAndSkipsJob() {
        stubJobWithStatus("cancelled");
        doReturn("output-url").when(terraformState).saveOutput(anyString(), anyString(), anyString(), anyString(), anyString());

        subject().setCompletedStatus(true, false, 0, terraformJob(), "output", "", "", "0000000");

        ArgumentCaptor<StepRequest> stepCaptor = ArgumentCaptor.forClass(StepRequest.class);
        verify(terrakubeClient, times(1)).updateStep(stepCaptor.capture(), anyString(), anyString(), anyString());
        Assertions.assertEquals("completed", stepCaptor.getValue().getData().getAttributes().getStatus());
        verify(terrakubeClient, never()).updateJob(any(JobRequest.class), anyString(), anyString());
    }

    @Test
    public void setCompletedStatusOnActiveJobUpdatesJob() {
        stubJobWithStatus("running");
        doReturn("output-url").when(terraformState).saveOutput(anyString(), anyString(), anyString(), anyString(), anyString());

        subject().setCompletedStatus(true, false, 0, terraformJob(), "output", "", "", "0000000");

        verify(terrakubeClient, times(1)).updateStep(any(), anyString(), anyString(), anyString());
        verify(terrakubeClient, times(1)).updateJob(any(JobRequest.class), anyString(), anyString());
    }

    @Test
    public void setRunningStatusOnRejectedJobUpdatesStepLogsButNotJob() {
        stubJobWithStatus("rejected");
        doReturn("output-path").when(terraformOutputPathService).getOutputPath(anyString(), anyString(), anyString());

        subject().setRunningStatus(terraformJob(), "0000000");

        verify(terrakubeClient, times(1)).updateStep(any(), anyString(), anyString(), anyString());
        verify(terrakubeClient, never()).updateJob(any(JobRequest.class), anyString(), anyString());
    }

    @Test
    public void setRunningStatusOnActiveJobUpdatesJob() {
        stubJobWithStatus("pending");
        doReturn("output-path").when(terraformOutputPathService).getOutputPath(anyString(), anyString(), anyString());

        subject().setRunningStatus(terraformJob(), "0000000");

        verify(terrakubeClient, times(1)).updateStep(any(), anyString(), anyString(), anyString());
        verify(terrakubeClient, times(1)).updateJob(any(JobRequest.class), anyString(), anyString());
    }

    @Test
    public void planWithChangesAndNoFurtherSteps_marksJobCompleted() {
        // A plan-only template has exactly one step. When that plan finds changes
        // (exitCode 2) there is nothing left in the template to run, so the job
        // should be marked completed rather than left pending forever.
        Job job = stubJobWithStatus("queue", 1);
        doReturn("output-url").when(terraformState).saveOutput(anyString(), anyString(), anyString(), anyString(), anyString());

        subject().setCompletedStatus(true, true, 2, terraformJob(), "plan output", "", "plan-file", "commit-1");

        Assertions.assertEquals("completed", job.getAttributes().getStatus());
        Assertions.assertEquals(true, job.getAttributes().isPlanChanges());
    }

    @Test
    public void planWithChangesAndFurtherSteps_marksJobPending() {
        // A "Plan and Apply" template has two steps. A plan finding changes should
        // still wait "pending" for the apply step that follows it.
        Job job = stubJobWithStatus("queue", 2);
        doReturn("output-url").when(terraformState).saveOutput(anyString(), anyString(), anyString(), anyString(), anyString());

        subject().setCompletedStatus(true, true, 2, terraformJob(), "plan output", "", "plan-file", "commit-1");

        Assertions.assertEquals("pending", job.getAttributes().getStatus());
        Assertions.assertEquals(true, job.getAttributes().isPlanChanges());
    }
}
