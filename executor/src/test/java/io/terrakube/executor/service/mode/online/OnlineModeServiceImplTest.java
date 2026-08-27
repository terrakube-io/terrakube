package io.terrakube.executor.service.mode.online;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.Workspace;
import io.terrakube.client.model.organization.workspace.WorkspaceAttributes;
import io.terrakube.client.model.response.Response;
import io.terrakube.executor.service.executor.ExecutorCapacityGate;
import io.terrakube.executor.service.executor.ExecutorJob;
import io.terrakube.executor.service.mode.TerraformJob;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OnlineModeServiceImplTest {

    private final ExecutorJob executorJob = mock(ExecutorJob.class);
    private final List<Object> publishedEvents = new ArrayList<>();
    private final ApplicationEventPublisher eventPublisher = publishedEvents::add;
    private final TerrakubeClient terrakubeClient = mock(TerrakubeClient.class);

    @SuppressWarnings("unchecked")
    private ReadinessState stateAt(int index) {
        return ((AvailabilityChangeEvent<ReadinessState>) publishedEvents.get(index)).getState();
    }

    private TerraformJob job() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org");
        job.setWorkspaceId("workspace");
        return job;
    }

    @Test
    void anIdlePodAcquiresTheGatePublishesRefusingTrafficAndReturns202() {
        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        OnlineModeServiceImpl subject = new OnlineModeServiceImpl(executorJob, gate, eventPublisher, terrakubeClient);
        TerraformJob job = job();

        ResponseEntity<TerraformJob> response = subject.terraformJob(job);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(job, response.getBody());
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
}
