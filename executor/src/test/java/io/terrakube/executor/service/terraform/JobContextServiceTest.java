package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.HttpURLConnection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobContextServiceTest {

    @Test
    void connectAndReadTimeoutsComeFromConfiguredValues() throws Exception {
        JobContextService service = new JobContextService(
                Mockito.mock(WorkspaceSecurity.class), new ObjectMapper(),
                "http://api.example", Mockito.mock(TerrakubeClient.class), 1234, 5678);

        HttpURLConnection connection = service.buildConnection("http://api.example/context/v1/1", "GET");

        assertEquals(1234, connection.getConnectTimeout());
        assertEquals(5678, connection.getReadTimeout());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCurrentContextReturnsEmptyMapWhenJobNotRunning() {
        WorkspaceSecurity workspaceSecurity = Mockito.mock(WorkspaceSecurity.class);
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        ObjectMapper objectMapper = new ObjectMapper();

        Job job = new Job();
        job.setId("100");
        JobAttributes attrs = new JobAttributes();
        attrs.setStatus("completed");
        job.setAttributes(attrs);

        ResponseWithInclude<Job, ?> jobDataResponse = new ResponseWithInclude<>();
        jobDataResponse.setData(job);

        Mockito.when(terrakubeClient.getJobById("org-1", "100")).thenReturn((ResponseWithInclude) jobDataResponse);

        JobContextService service = new JobContextService(workspaceSecurity, objectMapper, "http://localhost:8080", terrakubeClient);

        Map<String, Object> context = service.getCurrentContext("org-1", "100");
        assertNotNull(context);
        assertTrue(context.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveContextDoesNotThrowWhenJobNotRunning() {
        WorkspaceSecurity workspaceSecurity = Mockito.mock(WorkspaceSecurity.class);
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        ObjectMapper objectMapper = new ObjectMapper();

        Job job = new Job();
        job.setId("100");
        JobAttributes attrs = new JobAttributes();
        attrs.setStatus("failed");
        job.setAttributes(attrs);

        ResponseWithInclude<Job, ?> jobDataResponse = new ResponseWithInclude<>();
        jobDataResponse.setData(job);

        Mockito.when(terrakubeClient.getJobById("org-1", "100")).thenReturn((ResponseWithInclude) jobDataResponse);

        JobContextService service = new JobContextService(workspaceSecurity, objectMapper, "http://localhost:8080", terrakubeClient);

        service.saveContext("org-1", "100", Map.of("key", "value"));
        // Should catch exception and not throw
    }
}

