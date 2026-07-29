package io.terrakube.executor.service.status;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.step.StepAttributes;
import io.terrakube.client.model.organization.job.step.StepRequest;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.plugin.tfstate.StateUploadFailedException;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.TerraformJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateJobStatusImplTest {

    private TerrakubeClient terrakubeClient;
    private TerraformState terraformState;
    private ExecutorFlagsProperties executorFlagsProperties;
    private TerraformOutputPathService terraformOutputPathService;
    private UpdateJobStatusImpl subject;

    @BeforeEach
    void setUp() {
        terrakubeClient = mock(TerrakubeClient.class, Answers.RETURNS_DEEP_STUBS);
        terraformState = mock(TerraformState.class);
        executorFlagsProperties = mock(ExecutorFlagsProperties.class);
        terraformOutputPathService = mock(TerraformOutputPathService.class);

        when(executorFlagsProperties.isDisableAcknowledge()).thenReturn(false);
        when(terrakubeClient.getJobById(anyString(), anyString()).getData().getAttributes().getStatus())
                .thenReturn("running");
        when(terrakubeClient.getJobById(anyString(), anyString()).getData().getRelationships().getOrganization().getData().getId())
                .thenReturn("org-1");
        when(terrakubeClient.getJobById(anyString(), anyString()).getData().getAttributes().getOutput())
                .thenReturn("");

        subject = new UpdateJobStatusImpl(terrakubeClient, terraformState, executorFlagsProperties, terraformOutputPathService);
    }

    private TerraformJob job() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org-1");
        job.setJobId("job-1");
        job.setStepId("step-1");
        return job;
    }

    @Test
    void stepIsMarkedFailedWhenOutputUploadThrowsAndOutputUrlIsEmpty() {
        when(terraformState.saveOutput(eq("org-1"), eq("job-1"), eq("step-1"), anyString(), anyString()))
                .thenThrow(new StateUploadFailedException("upload kaboom"));

        subject.setCompletedStatus(true, false, 0, job(), "stdout", "stderr", "plan", "commit");

        ArgumentCaptor<StepRequest> stepCaptor = ArgumentCaptor.forClass(StepRequest.class);
        verify(terrakubeClient).updateStep(stepCaptor.capture(), eq("org-1"), eq("job-1"), eq("step-1"));

        StepAttributes attrs = stepCaptor.getValue().getData().getAttributes();
        // Output URL is cleared so the executor doesn't link to a half-uploaded artifact.
        assertEquals("", attrs.getOutput());
        // Despite the caller passing successful=true, the upload failure flips this to failed.
        assertEquals("failed", attrs.getStatus());
    }

    @Test
    void stepRecordsUrlOnSuccessfulOutputUpload() {
        when(terraformState.saveOutput(eq("org-1"), eq("job-1"), eq("step-1"), anyString(), anyString()))
                .thenReturn("https://example/output-url");

        subject.setCompletedStatus(true, false, 0, job(), "stdout", "stderr", "plan", "commit");

        ArgumentCaptor<StepRequest> stepCaptor = ArgumentCaptor.forClass(StepRequest.class);
        verify(terrakubeClient).updateStep(stepCaptor.capture(), eq("org-1"), eq("job-1"), eq("step-1"));

        StepAttributes attrs = stepCaptor.getValue().getData().getAttributes();
        assertEquals("https://example/output-url", attrs.getOutput());
        assertEquals("completed", attrs.getStatus());
    }
}
