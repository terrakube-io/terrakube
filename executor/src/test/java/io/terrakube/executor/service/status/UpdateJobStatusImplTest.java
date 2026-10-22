package io.terrakube.executor.service.status;

import io.terrakube.executor.service.logs.StepOutputBuffer;
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
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        complete(subject(), true, false, 0, terraformJob(), "output", "", "", "0000000");

        ArgumentCaptor<StepRequest> stepCaptor = ArgumentCaptor.forClass(StepRequest.class);
        verify(terrakubeClient, times(1)).updateStep(stepCaptor.capture(), anyString(), anyString(), anyString());
        Assertions.assertEquals("failed", stepCaptor.getValue().getData().getAttributes().getStatus());
        verify(terrakubeClient, never()).updateJob(any(JobRequest.class), anyString(), anyString());
    }

    @Test
    public void setCompletedStatusOnCancelledJobKeepsStepResultAndSkipsJob() {
        stubJobWithStatus("cancelled");
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        complete(subject(), true, false, 0, terraformJob(), "output", "", "", "0000000");

        ArgumentCaptor<StepRequest> stepCaptor = ArgumentCaptor.forClass(StepRequest.class);
        verify(terrakubeClient, times(1)).updateStep(stepCaptor.capture(), anyString(), anyString(), anyString());
        Assertions.assertEquals("completed", stepCaptor.getValue().getData().getAttributes().getStatus());
        verify(terrakubeClient, never()).updateJob(any(JobRequest.class), anyString(), anyString());
    }

    @Test
    public void setCompletedStatusOnActiveJobUpdatesJob() {
        stubJobWithStatus("running");
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        complete(subject(), true, false, 0, terraformJob(), "output", "", "", "0000000");

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
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        complete(subject(), true, true, 2, terraformJob(), "plan output", "", "plan-file", "commit-1");

        Assertions.assertEquals("completed", job.getAttributes().getStatus());
        Assertions.assertEquals(true, job.getAttributes().isPlanChanges());
    }

    @Test
    public void planWithChangesAndFurtherSteps_marksJobPending() {
        // A "Plan and Apply" template has two steps. A plan finding changes should
        // still wait "pending" for the apply step that follows it.
        Job job = stubJobWithStatus("queue", 2);
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        complete(subject(), true, true, 2, terraformJob(), "plan output", "", "plan-file", "commit-1");

        Assertions.assertEquals("pending", job.getAttributes().getStatus());
        Assertions.assertEquals(true, job.getAttributes().isPlanChanges());
    }

    @Test
    public void planWithSoftMandatoryViolations_marksJobWaitingApprovalAndSetsApprovalTeam() {
        Job job = stubJobWithStatus("queue", 2);
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        complete(subject(), true, true, 2, terraformJob(), "plan output", "", "plan-file", "commit-1", true, "SecOps");

        Assertions.assertEquals("waitingApproval", job.getAttributes().getStatus());
        Assertions.assertEquals("SecOps", job.getAttributes().getApprovalTeam());
        Assertions.assertTrue(job.getAttributes().isPlanChanges());
    }

    @Test
    public void planWithSoftMandatoryViolationsWithoutApprovalTeam_marksJobWaitingApproval() {
        Job job = stubJobWithStatus("queue", 2);
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        complete(subject(), true, true, 2, terraformJob(), "plan output", "", "plan-file", "commit-1", true, null);

        Assertions.assertEquals("waitingApproval", job.getAttributes().getStatus());
        Assertions.assertNull(job.getAttributes().getApprovalTeam());
    }

    @Test
    public void setCompletedStatusWithDeliveryStateSetsAttributeOnStepRequest() {
        stubJobWithStatus("queue", 1);
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        ArgumentCaptor<StepRequest> captor = ArgumentCaptor.forClass(StepRequest.class);

        complete(subject(), true, false, 0, terraformJob(), "output", "", "", "commit-1", false, null, "ACKED");

        verify(terrakubeClient).updateStep(captor.capture(), anyString(), anyString(), anyString());
        StepRequest request = captor.getValue();
        Assertions.assertNotNull(request.getData());
        Assertions.assertTrue(request.getData().getAttributes() instanceof StepDeliveryAttributes);
        StepDeliveryAttributes attrs = (StepDeliveryAttributes) request.getData().getAttributes();
        Assertions.assertEquals("ACKED", attrs.getDeliveryState());
        Assertions.assertNotNull(attrs.getDeliveryTimestamp());
    }

    @Test
    public void setCompletedStatusWithDeliveryStateAndRevisionSetsAttributesOnStepRequest() {
        stubJobWithStatus("queue", 1);
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        ArgumentCaptor<StepRequest> captor = ArgumentCaptor.forClass(StepRequest.class);

        complete(subject(), true, false, 0, terraformJob(), "output", "", "", "commit-1", false, null, "ACKED", 42L);

        verify(terrakubeClient).updateStep(captor.capture(), anyString(), anyString(), anyString());
        StepRequest request = captor.getValue();
        Assertions.assertNotNull(request.getData());
        Assertions.assertTrue(request.getData().getAttributes() instanceof StepDeliveryAttributes);
        StepDeliveryAttributes attrs = (StepDeliveryAttributes) request.getData().getAttributes();
        Assertions.assertEquals("ACKED", attrs.getDeliveryState());
        Assertions.assertEquals(42L, attrs.getDeliveryRevision());
        Assertions.assertNotNull(attrs.getDeliveryTimestamp());
    }

    @Test
    public void setCompletedStatus_WhenApiRejectsDeliveryAttributesWith400_RetriesWithPlainStepAttributes() {
        stubJobWithStatus("queue", 1);
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        feign.Request feignRequest = feign.Request.create(
                feign.Request.HttpMethod.PATCH, "/step", java.util.Collections.emptyMap(), null, java.nio.charset.StandardCharsets.UTF_8, null);
        feign.FeignException badRequest = new feign.FeignException.BadRequest("Unknown attribute: deliveryState", feignRequest, null, null);

        ArgumentCaptor<StepRequest> captor = ArgumentCaptor.forClass(StepRequest.class);
        org.mockito.Mockito.doThrow(badRequest)
                .doNothing()
                .when(terrakubeClient).updateStep(captor.capture(), anyString(), anyString(), anyString());

        complete(subject(), true, false, 0, terraformJob(), "output", "", "", "commit-1", false, null, "ACKED", 42L);

        verify(terrakubeClient, times(2)).updateStep(any(), anyString(), anyString(), anyString());
        List<StepRequest> requests = captor.getAllValues();
        Assertions.assertEquals(2, requests.size());

        // First attempt sent StepDeliveryAttributes
        Assertions.assertTrue(requests.get(0).getData().getAttributes() instanceof StepDeliveryAttributes);

        // Second fallback attempt sent plain StepAttributes without delivery fields
        Assertions.assertFalse(requests.get(1).getData().getAttributes() instanceof StepDeliveryAttributes);
        Assertions.assertEquals("completed", requests.get(1).getData().getAttributes().getStatus());
        Assertions.assertEquals("output-url", requests.get(1).getData().getAttributes().getOutput());
    }

    private JobAttributes updatedJobAttributes() {
        ArgumentCaptor<JobRequest> captor = ArgumentCaptor.forClass(JobRequest.class);
        verify(terrakubeClient).updateJob(captor.capture(), anyString(), anyString());
        return captor.getValue().getData().getAttributes();
    }

    @Test
    public void aPlanThatSavedArtifactsRecordsWhereAndTheirChecksumWithThePlan() {
        stubJobWithStatus("queue", 2);
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());
        TerraformJob terraformJob = terraformJob();
        terraformJob.setArtifactPatterns(List.of("build/**"));
        terraformJob.setArtifactsUrl("https://bucket/plan-artifacts/o/w/4711/ze-step/plan-artifacts.tar.gz");
        terraformJob.setArtifactsChecksum("deadbeef");

        complete(subject(), true, true, 2, terraformJob, "plan output", "", "plan-file", "commit-1");

        JobAttributes sent = updatedJobAttributes();
        Assertions.assertEquals("https://bucket/plan-artifacts/o/w/4711/ze-step/plan-artifacts.tar.gz", sent.getTerraformPlanArtifacts());
        Assertions.assertEquals("deadbeef", sent.getTerraformPlanArtifactsChecksum());
        Assertions.assertEquals("plan-file", sent.getTerraformPlan());
    }

    @Test
    public void aPlanWithArtifactsConfiguredButNoneSavedClearsWhatAnEarlierPlanRecorded() {
        // A later plan in the same job must not leave the first plan's bundle for its apply to unpack.
        stubJobWithStatus("queue", 2);
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());
        TerraformJob terraformJob = terraformJob();
        terraformJob.setArtifactPatterns(List.of("build/**"));

        complete(subject(), true, true, 2, terraformJob, "plan output", "", "plan-file", "commit-1");

        JobAttributes sent = updatedJobAttributes();
        Assertions.assertEquals("", sent.getTerraformPlanArtifacts());
        Assertions.assertEquals("", sent.getTerraformPlanArtifactsChecksum());
    }

    @Test
    public void aPlanWithoutArtifactsNeverMentionsThemSoAnOlderApiAcceptsTheUpdate() {
        // An API from before the plan-artifacts fields refuses an unknown attribute.
        stubJobWithStatus("queue", 2);
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());

        complete(subject(), true, true, 2, terraformJob(), "plan output", "", "plan-file", "commit-1");

        JobAttributes sent = updatedJobAttributes();
        Assertions.assertNull(sent.getTerraformPlanArtifacts());
        Assertions.assertNull(sent.getTerraformPlanArtifactsChecksum());
    }

    @Test
    public void anApplyLeavesTheRecordedArtifactsAlone() {
        stubJobWithStatus("running", 2);
        doReturn("output-url").when(terraformState).saveBufferedOutput(anyString(), anyString(), anyString(), any(StepOutputBuffer.class), anyString());
        TerraformJob terraformJob = terraformJob();
        terraformJob.setArtifactPatterns(List.of("build/**"));

        complete(subject(), true, false, 0, terraformJob, "apply output", "", "", "commit-1");

        Assertions.assertNull(updatedJobAttributes().getTerraformPlanArtifacts());
    }

    private static void complete(UpdateJobStatusImpl subject, boolean successful, boolean isPlan, int exitCode, TerraformJob job,
                                 String output, String errorOutput, String planFile, String commitId, Object... extra) {
        boolean softViolations = extra.length > 0 && (Boolean) extra[0];
        String approvalTeam = extra.length > 1 ? (String) extra[1] : null;
        String deliveryState = extra.length > 2 ? (String) extra[2] : null;
        Long deliveryRevision = extra.length > 3 ? (Long) extra[3] : null;
        try (StepOutputBuffer buffer = StepOutputBuffer.ofMessage(output)) {
            subject.setCompletedStatus(successful, isPlan, exitCode, job, buffer, errorOutput, planFile, commitId,
                    softViolations, approvalTeam, deliveryState, deliveryRevision);
        }
    }
}
