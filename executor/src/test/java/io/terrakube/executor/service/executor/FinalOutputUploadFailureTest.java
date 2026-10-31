package io.terrakube.executor.service.executor;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.generic.Resource;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.JobRequest;
import io.terrakube.client.model.organization.job.OrganizationData;
import io.terrakube.client.model.organization.job.Relationships;
import io.terrakube.client.model.organization.job.StepData;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.logs.StepOutputBuffer;
import io.terrakube.executor.service.logs.StepOutputPartWriter;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.status.UpdateJobStatusImpl;
import io.terrakube.executor.service.terraform.TerraformExecutor;
import io.terrakube.executor.service.workspace.SetupWorkspace;
import io.terrakube.executor.service.shutdown.ShutdownServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The final upload of a step fails once (a storage hiccup) and the executor's catch-all then completes the
 * job with a short message. The API prefers a saved output over the parts, so what the executor saves
 * second decides what the Console shows.
 */
class FinalOutputUploadFailureTest {

    @TempDir
    Path tempDir;

    private final SetupWorkspace setupWorkspace = mock(SetupWorkspace.class);
    private final TerraformExecutor terraformExecutor = mock(TerraformExecutor.class);
    private final TerrakubeClient terrakubeClient = mock(TerrakubeClient.class);
    private final TerraformState terraformState = mock(TerraformState.class);
    private final ExecutorFlagsProperties flags = new ExecutorFlagsProperties();
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);

    @Test
    void theOutputPrintedBeforeAFailedFinalUploadIsStillReachableAfterwards() throws Exception {
        TerraformJob job = new TerraformJob();
        job.setJobId("4711");
        job.setStepId("ze-step");
        job.setOrganizationId("ze-org");
        job.setWorkspaceId("workspace");
        job.setType("terraformPlan");
        job.setBranch("remote-content");
        job.setEnvironmentVariables(new HashMap<>());
        job.setVariables(new HashMap<>());
        stubRunningJob();
        File workDir = tempDir.toFile();
        when(setupWorkspace.prepareWorkspace(job)).thenReturn(workDir);

        // The step printed output and two parts of it reached storage while it ran.
        List<String> uploadedParts = java.util.Collections.synchronizedList(new ArrayList<>());
        StepOutputBuffer printed = new StepOutputBuffer();
        printed.append("the real output of the plan\n");
        StepOutputPartWriter writer = new StepOutputPartWriter(printed, (number, data) -> uploadedParts.add("part " + number),
                5, Duration.ofMillis(20), 10, "test");
        printed.attachArchiver(writer);
        writer.start();
        for (int i = 0; i < 250 && uploadedParts.isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertTrue(!uploadedParts.isEmpty(), "the test needs at least one uploaded part");

        ExecutorJobResult result = new ExecutorJobResult();
        result.setOutputBuffer(printed);
        result.setSuccessfulExecution(true);
        result.setOutputErrorLog("");
        when(terraformExecutor.plan(any(), any(), anyBoolean())).thenReturn(result);

        // The first final upload fails, the second one (the catch-all's) works. Record what was saved.
        List<String> savedFinalOutputs = new ArrayList<>();
        when(terraformState.saveBufferedOutput(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("storage hiccup"))
                .thenAnswer(invocation -> {
                    StepOutputBuffer saved = invocation.getArgument(3);
                    savedFinalOutputs.add(saved.toString());
                    return "output-url";
                });

        UpdateJobStatusImpl updateJobStatus = new UpdateJobStatusImpl(terrakubeClient, terraformState, flags,
                mock(TerraformOutputPathService.class));
        new ExecutorJobImpl(setupWorkspace, terraformExecutor, updateJobStatus, flags, mock(ShutdownServiceImpl.class),
                mock(ScriptEngineService.class), event -> { }, mock(JobExecutionWatchdog.class),
                mock(ExecutorCapacityGate.class), redisTemplate).createJob(job);

        // Either nothing final was saved (the API then serves the parts with the incomplete notice), or the
        // final output holds what the step printed. A short error line alone would hide the parts.
        verify(terraformState, never()).deleteOutputParts(anyString(), anyString(), anyString());
        assertTrue(savedFinalOutputs.isEmpty() || savedFinalOutputs.stream().anyMatch(o -> o.contains("the real output of the plan")),
                "the final output saved after the failure was " + savedFinalOutputs);
        // The job ends failed, which is what makes the API serve the parts with the incomplete notice.
        ArgumentCaptor<JobRequest> jobUpdate = ArgumentCaptor.forClass(JobRequest.class);
        verify(terrakubeClient, org.mockito.Mockito.atLeastOnce()).updateJob(jobUpdate.capture(), anyString(), anyString());
        assertEquals("failed", jobUpdate.getValue().getData().getAttributes().getStatus());
    }

    private void stubRunningJob() {
        Resource organization = new Resource();
        organization.setId("ze-org");
        OrganizationData organizationData = new OrganizationData();
        organizationData.setData(organization);
        Resource stepResource = new Resource();
        stepResource.setId("ze-step");
        StepData stepData = new StepData();
        stepData.setData(List.of(stepResource));
        Relationships relationships = new Relationships();
        relationships.setOrganization(organizationData);
        relationships.setStep(stepData);
        JobAttributes attributes = new JobAttributes();
        attributes.setStatus("running");
        Job apiJob = new Job();
        apiJob.setId("4711");
        apiJob.setAttributes(attributes);
        apiJob.setRelationships(relationships);
        ResponseWithInclude<Job, Step> response = new ResponseWithInclude<>();
        response.setData(apiJob);
        doReturn(response).when(terrakubeClient).getJobById("ze-org", "4711");
    }
}
