package io.terrakube.executor.service.status;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.generic.Resource;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.OrganizationData;
import io.terrakube.client.model.organization.job.Relationships;
import io.terrakube.client.model.organization.job.StepData;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.TerraformJob;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpdateJobStatusImplTest {

    private Job jobWithSteps(int stepCount) {
        Job job = new Job();
        job.setId("42");

        JobAttributes attributes = new JobAttributes();
        attributes.setStatus("queue");
        job.setAttributes(attributes);

        Resource organizationResource = new Resource();
        organizationResource.setId("org-1");
        OrganizationData organizationData = new OrganizationData();
        organizationData.setData(organizationResource);

        List<Resource> steps = new java.util.ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(new Resource());
        }
        StepData stepData = new StepData();
        stepData.setData(steps);

        Relationships relationships = new Relationships();
        relationships.setOrganization(organizationData);
        relationships.setStep(stepData);
        job.setRelationships(relationships);

        return job;
    }

    private UpdateJobStatusImpl newService(Job job) {
        TerrakubeClient terrakubeClient = mock(TerrakubeClient.class);
        ResponseWithInclude<Job, ?> response = new ResponseWithInclude<>();
        response.setData(job);
        when(terrakubeClient.getJobById(anyString(), anyString())).thenReturn((ResponseWithInclude) response);

        TerraformState terraformState = mock(TerraformState.class);
        when(terraformState.saveOutput(any(), any(), any(), any(), any())).thenReturn("output-path");

        ExecutorFlagsProperties flags = mock(ExecutorFlagsProperties.class);
        when(flags.isDisableAcknowledge()).thenReturn(false);

        TerraformOutputPathService pathService = mock(TerraformOutputPathService.class);

        return new UpdateJobStatusImpl(terrakubeClient, terraformState, flags, pathService);
    }

    private TerraformJob terraformJob() {
        TerraformJob terraformJob = new TerraformJob();
        terraformJob.setOrganizationId("org-1");
        terraformJob.setJobId("42");
        terraformJob.setStepId("step-1");
        return terraformJob;
    }

    @Test
    void planWithChangesAndNoFurtherSteps_marksJobCompleted() {
        // A plan-only template has exactly one step. When that plan finds changes
        // (exitCode 2) there is nothing left in the template to run, so the job
        // should be marked completed rather than left pending forever.
        Job job = jobWithSteps(1);
        UpdateJobStatusImpl service = newService(job);

        service.setCompletedStatus(true, true, 2, terraformJob(), "plan output", "", "plan-file", "commit-1");

        assertEquals("completed", job.getAttributes().getStatus());
        assertEquals(true, job.getAttributes().isPlanChanges());
    }

    @Test
    void planWithChangesAndFurtherSteps_marksJobPending() {
        // A "Plan and Apply" template has two steps. A plan finding changes should
        // still wait "pending" for the apply step that follows it.
        Job job = jobWithSteps(2);
        UpdateJobStatusImpl service = newService(job);

        service.setCompletedStatus(true, true, 2, terraformJob(), "plan output", "", "plan-file", "commit-1");

        assertEquals("pending", job.getAttributes().getStatus());
        assertEquals(true, job.getAttributes().isPlanChanges());
    }
}
