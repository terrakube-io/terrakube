package io.terrakube.executor.service.mode.online;

import feign.FeignException;
import feign.Request;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.generic.Resource;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.OrganizationData;
import io.terrakube.client.model.organization.job.Relationships;
import io.terrakube.client.model.organization.job.WorkspaceData;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.organization.job.step.StepAttributes;
import io.terrakube.client.model.organization.workspace.Workspace;
import io.terrakube.client.model.organization.workspace.WorkspaceAttributes;
import io.terrakube.client.model.response.Response;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.executor.service.executor.ExecutorCapacityGate;
import io.terrakube.executor.service.executor.ExecutorJob;
import io.terrakube.executor.service.mode.TerraformJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OnlineModeServiceImplTest {

    private final ExecutorJob executorJob = mock(ExecutorJob.class);
    private final List<Object> publishedEvents = new ArrayList<>();
    private final ApplicationEventPublisher eventPublisher = publishedEvents::add;
    private final TerrakubeClient terrakubeClient = mock(TerrakubeClient.class);

    @BeforeEach
    void setUp() {
        when(terrakubeClient.getJobById("org", "job-1"))
                .thenReturn(jobResponse("org", "workspace", "job-1", "pending"));
    }

    private static ResponseWithInclude<Job, Step> jobResponse(
            String organizationId, String workspaceId, String jobId, String status) {
        JobAttributes attributes = new JobAttributes();
        attributes.setStatus(status);

        Resource wsResource = new Resource();
        wsResource.setId(workspaceId);
        wsResource.setType("workspace");
        WorkspaceData workspaceData = new WorkspaceData();
        workspaceData.setData(wsResource);

        Resource orgResource = new Resource();
        orgResource.setId(organizationId);
        orgResource.setType("organization");
        OrganizationData organizationData = new OrganizationData();
        organizationData.setData(orgResource);

        Relationships relationships = new Relationships();
        relationships.setWorkspace(workspaceData);
        relationships.setOrganization(organizationData);

        Job job = new Job();
        job.setId(jobId);
        job.setType("job");
        job.setAttributes(attributes);
        job.setRelationships(relationships);

        ResponseWithInclude<Job, Step> response = new ResponseWithInclude<>();
        response.setData(job);
        return response;
    }

    @SuppressWarnings("unchecked")
    private ReadinessState stateAt(int index) {
        return ((AvailabilityChangeEvent<ReadinessState>) publishedEvents.get(index)).getState();
    }

    private TerraformJob job() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org");
        job.setWorkspaceId("workspace");
        job.setJobId("job-1");
        return job;
    }

    @Test
    void anIdlePodAcquiresTheGatePublishesRefusingTrafficAndReturns202() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        TerraformJob job = job();

        ResponseEntity<TerraformJob> response = subject.terraformJob(job);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNull(response.getBody());
        assertEquals(1, publishedEvents.size());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, stateAt(0));
        verify(executorJob, times(1)).createJob(job);
    }

    @Test
    void aBusyPodReturns503AndNeverCallsCreateJob() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        gate.tryAcquire();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);

        ResponseEntity<TerraformJob> response = subject.terraformJob(job());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNull(response.getBody());
        verifyNoInteractions(executorJob);
        assertEquals(0, publishedEvents.size());
    }

    @Test
    void anAsyncPoolRejectionReleasesTheGateRestoresReadinessAndReturns503() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        doThrow(new TaskRejectedException("pool exhausted")).when(executorJob).createJob(Mockito.any());
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);

        ResponseEntity<TerraformJob> response = subject.terraformJob(job());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(2, publishedEvents.size());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, stateAt(0));
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, stateAt(1));
        // Gate must be free again so the next request can be admitted.
        assertEquals(true, gate.tryAcquire());
    }

    @Test
    void overridesJobFolderFromTerrakubeClientWhenRequestIsReceived() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        TerraformJob job = job();
        job.setFolder("initial-folder");

        WorkspaceAttributes attributes = new WorkspaceAttributes();
        attributes.setFolder("trusted/override/folder");
        Workspace workspace = new Workspace();
        workspace.setAttributes(attributes);
        Response<Workspace> response = new Response<>();
        response.setData(workspace);

        when(terrakubeClient.getWorkspaceById("org", "workspace")).thenReturn(response);

        ResponseEntity<TerraformJob> result = subject.terraformJob(job);

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertEquals("trusted/override/folder", job.getFolder());
    }

    @Test
    void rejectsWith403AndReleasesGateWhenJobNotFoundInApi() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        when(terrakubeClient.getJobById("org", "job-1")).thenReturn(null);

        ResponseEntity<TerraformJob> response = subject.terraformJob(job());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(executorJob);
        assertEquals(2, publishedEvents.size());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, stateAt(0));
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, stateAt(1));
        assertTrue(gate.tryAcquire());
    }

    @Test
    void rejectsWith503AndReleasesGateWhenApiLookupThrows() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        when(terrakubeClient.getJobById("org", "job-1")).thenThrow(new RuntimeException("API timeout"));

        ResponseEntity<TerraformJob> response = subject.terraformJob(job());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verifyNoInteractions(executorJob);
        assertEquals(2, publishedEvents.size());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, stateAt(0));
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, stateAt(1));
        assertTrue(gate.tryAcquire());
    }

    @Test
    void rejectsWith403AndReleasesGateWhenApiReturns404() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        Request feignRequest = Request.create(
                Request.HttpMethod.GET, "/api/v1/organization/org/job/job-1", Map.of(), null, StandardCharsets.UTF_8, null);
        when(terrakubeClient.getJobById("org", "job-1"))
                .thenThrow(new FeignException.NotFound("not found", feignRequest, null, Map.of()));

        ResponseEntity<TerraformJob> response = subject.terraformJob(job());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(executorJob);
        assertEquals(2, publishedEvents.size());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, stateAt(0));
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, stateAt(1));
        assertTrue(gate.tryAcquire());
    }

    @Test
    void rejectsWith403WhenJobBelongsToDifferentWorkspace() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        when(terrakubeClient.getJobById("org", "job-1"))
                .thenReturn(jobResponse("org", "other-workspace", "job-1", "pending"));

        ResponseEntity<TerraformJob> response = subject.terraformJob(job());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(executorJob);
        assertEquals(2, publishedEvents.size());
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, stateAt(1));
        assertTrue(gate.tryAcquire());
    }

    @Test
    void rejectsWith403WhenJobBelongsToDifferentOrganization() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        when(terrakubeClient.getJobById("org", "job-1"))
                .thenReturn(jobResponse("other-org", "workspace", "job-1", "pending"));

        ResponseEntity<TerraformJob> response = subject.terraformJob(job());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(executorJob);
        assertTrue(gate.tryAcquire());
    }

    @Test
    void rejectsWith403WhenJobIsInTerminalStatus() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        when(terrakubeClient.getJobById("org", "job-1"))
                .thenReturn(jobResponse("org", "workspace", "job-1", "completed"));

        ResponseEntity<TerraformJob> response = subject.terraformJob(job());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(executorJob);
        assertTrue(gate.tryAcquire());
    }

    @Test
    void rejectsWith403WhenJobIdIsMissing() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        TerraformJob job = job();
        job.setJobId(null);

        ResponseEntity<TerraformJob> response = subject.terraformJob(job);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(executorJob);
        assertTrue(gate.tryAcquire());
    }

    @Test
    void acceptsJobInRejectedStatusForOnRejectCommands() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        when(terrakubeClient.getJobById("org", "job-1"))
                .thenReturn(jobResponse("org", "workspace", "job-1", "rejected"));

        TerraformJob job = job();
        ResponseEntity<TerraformJob> response = subject.terraformJob(job);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(executorJob, times(1)).createJob(job);
    }

    private static Step stepResource(String stepId, String status) {
        Step step = new Step();
        step.setId(stepId);
        step.setType("step");
        StepAttributes stepAttributes = new StepAttributes();
        stepAttributes.setStatus(status);
        step.setAttributes(stepAttributes);
        return step;
    }

    @Test
    void httpPostReturnsEmpty202Body() throws Exception {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(subject).build();

        String payload = """
                {
                  "organizationId": "org",
                  "workspaceId": "workspace",
                  "jobId": "job-1",
                  "stepId": "step-1",
                  "accessToken": "glpat-super-secret-vcs-token",
                  "environmentVariables": {
                    "TERRAKUBE_GCP_CREDENTIALS_FILE": "secret-jwt"
                  }
                }
                """;

        MvcResult result = mockMvc.perform(
                        post("/api/v1/terraform-rs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                .andExpect(status().isAccepted())
                .andReturn();

        assertEquals("", result.getResponse().getContentAsString());
        verify(executorJob, times(1)).createJob(Mockito.any(TerraformJob.class));
    }

    @Test
    void acceptsDispatchWhenIncludedStepsAreMissingForCompatibility() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);

        TerraformJob job = job();
        job.setStepId("step-1");

        ResponseEntity<TerraformJob> response = subject.terraformJob(job);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNull(response.getBody());
        verify(executorJob, times(1)).createJob(job);
    }

    @Test
    void acceptsPendingStepEvenWhenJobStatusIsQueueOrRunning() {
        for (String jobStatus : List.of("queue", "running")) {
            ExecutorCapacityGate gate = new ExecutorCapacityGate();
            ExecutorJob localExecutorJob = mock(ExecutorJob.class);
            OnlineModeServiceImpl subject = new OnlineModeServiceImpl(localExecutorJob, gate, eventPublisher, terrakubeClient);

            ResponseWithInclude<Job, Step> responseWithPendingStep = jobResponse("org", "workspace", "job-1", jobStatus);
            responseWithPendingStep.setIncluded(List.of(stepResource("step-1", "pending")));
            when(terrakubeClient.getJobById("org", "job-1")).thenReturn(responseWithPendingStep);

            TerraformJob job = job();
            job.setStepId("step-1");

            ResponseEntity<TerraformJob> response = subject.terraformJob(job);
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
            assertNull(response.getBody());
            verify(localExecutorJob, times(1)).createJob(job);
        }
    }

    @Test
    void rejectsDuplicateDispatchForEveryNonPendingStepStatusAndReleasesGate() {
        for (String nonPendingStepStatus : List.of("queue", "running", "completed", "failed", "cancelled")) {
            ExecutorCapacityGate gate = new ExecutorCapacityGate();
            ExecutorJob localExecutorJob = mock(ExecutorJob.class);
            OnlineModeServiceImpl subject = new OnlineModeServiceImpl(localExecutorJob, gate, eventPublisher, terrakubeClient);

            ResponseWithInclude<Job, Step> responseWithStep = jobResponse("org", "workspace", "job-1", "running");
            responseWithStep.setIncluded(List.of(stepResource("step-1", nonPendingStepStatus)));
            when(terrakubeClient.getJobById("org", "job-1")).thenReturn(responseWithStep);

            TerraformJob duplicate = job();
            duplicate.setStepId("step-1");

            assertEquals(HttpStatus.FORBIDDEN, subject.terraformJob(duplicate).getStatusCode(),
                    "Expected 403 for step status " + nonPendingStepStatus);
            verifyNoInteractions(localExecutorJob);
            assertTrue(gate.tryAcquire());
        }
    }

    @Test
    void acceptsOnRejectHookEvenWhenStepIsNonPendingOnRejectedJob() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);

        ResponseWithInclude<Job, Step> rejectedWithFailedStep = jobResponse("org", "workspace", "job-1", "rejected");
        rejectedWithFailedStep.setIncluded(List.of(stepResource("step-approval", "failed")));
        when(terrakubeClient.getJobById("org", "job-1")).thenReturn(rejectedWithFailedStep);

        TerraformJob job = job();
        job.setStepId("step-approval");

        ResponseEntity<TerraformJob> response = subject.terraformJob(job);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNull(response.getBody());
        verify(executorJob, times(1)).createJob(job);
    }
}
