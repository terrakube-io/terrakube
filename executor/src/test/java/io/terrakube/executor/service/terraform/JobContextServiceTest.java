package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void getCurrentContextThrowsWhenTheContextCannotBeRead() throws Exception {
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        Job job = new Job();
        job.setId("100");
        JobAttributes attrs = new JobAttributes();
        attrs.setStatus("running");
        job.setAttributes(attrs);
        ResponseWithInclude<Job, ?> jobDataResponse = new ResponseWithInclude<>();
        jobDataResponse.setData(job);
        Mockito.when(terrakubeClient.getJobById("org-1", "100")).thenReturn((ResponseWithInclude) jobDataResponse);

        JobContextService service = Mockito.spy(new JobContextService(
                Mockito.mock(WorkspaceSecurity.class), new ObjectMapper(), "http://localhost:8080", terrakubeClient));
        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getResponseCode()).thenReturn(503);
        Mockito.doReturn(connection).when(service).buildConnection("http://localhost:8080/context/v1/100", "GET");

        // An empty map here would be merged and saved over the stored context by every caller.
        assertThrows(IllegalStateException.class, () -> service.getCurrentContext("org-1", "100"));
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

    @SuppressWarnings("unchecked")
    private static TerrakubeClient clientWithJobStatus(String status) {
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        Job job = new Job();
        job.setId("100");
        JobAttributes attrs = new JobAttributes();
        attrs.setStatus(status);
        job.setAttributes(attrs);
        ResponseWithInclude<Job, ?> jobDataResponse = new ResponseWithInclude<>();
        jobDataResponse.setData(job);
        Mockito.when(terrakubeClient.getJobById("org-1", "100")).thenReturn((ResponseWithInclude) jobDataResponse);
        return terrakubeClient;
    }

    private static JobContextService.PatchResult patchWithResponse(int status, String responseBody, ByteArrayOutputStream requestBody) throws Exception {
        JobContextService service = Mockito.spy(new JobContextService(
                Mockito.mock(WorkspaceSecurity.class), new ObjectMapper(), "http://localhost:8080", clientWithJobStatus("running")));
        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getOutputStream()).thenReturn(requestBody);
        Mockito.when(connection.getResponseCode()).thenReturn(status);
        Mockito.when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
        Mockito.doReturn(connection).when(service).buildConnection("http://localhost:8080/context/v1/100/patch", "POST");

        Map<String, Object> set = new HashMap<>();
        set.put("planStructuredOutput/step-1", List.of());
        set.put("noChangePlan", null);
        return service.patchContext("org-1", "100", 1760371234567001L, set);
    }

    @Test
    void patchContextPostsRevisionAndSetIncludingNullsToThePatchEndpoint() throws Exception {
        ByteArrayOutputStream requestBody = new ByteArrayOutputStream();

        assertEquals(JobContextService.PatchResult.APPLIED, patchWithResponse(200, "{\"applied\":2,\"skipped\":0}", requestBody));

        JsonNode body = new ObjectMapper().readTree(requestBody.toByteArray());
        assertEquals(1760371234567001L, body.get("revision").asLong());
        assertTrue(body.get("set").get("planStructuredOutput/step-1").isArray());
        assertTrue(body.get("set").has("noChangePlan"));
        assertTrue(body.get("set").get("noChangePlan").isNull());
    }

    @Test
    void patchContextReportsSkippedWhenNothingApplied() throws Exception {
        assertEquals(JobContextService.PatchResult.SKIPPED,
                patchWithResponse(200, "{\"applied\":0,\"skipped\":2}", new ByteArrayOutputStream()));
    }

    @Test
    void patchContextReportsFailedWhenA2xxBodyHasNoNumericApplied() throws Exception {
        assertEquals(JobContextService.PatchResult.FAILED, patchWithResponse(200, "", new ByteArrayOutputStream()));
        assertEquals(JobContextService.PatchResult.FAILED, patchWithResponse(200, "{}", new ByteArrayOutputStream()));
        assertEquals(JobContextService.PatchResult.FAILED,
                patchWithResponse(200, "{\"applied\":\"many\"}", new ByteArrayOutputStream()));
    }

    @Test
    void patchContextFreshUsesANewRevisionAndReturnsTheResult() {
        JobContextService service = Mockito.spy(new JobContextService(
                Mockito.mock(WorkspaceSecurity.class), new ObjectMapper(), "http://localhost:8080", Mockito.mock(TerrakubeClient.class)));
        Map<String, Object> set = Map.of("policyEvaluation", Map.of());
        Mockito.doReturn(JobContextService.PatchResult.SKIPPED).when(service)
                .patchContext(Mockito.eq("org-1"), Mockito.eq("100"), Mockito.anyLong(), Mockito.eq(set));
        long floor = System.currentTimeMillis() * 1000;

        assertEquals(JobContextService.PatchResult.SKIPPED, service.patchContextFresh("org-1", "100", set));

        ArgumentCaptor<Long> revision = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(service).patchContext(Mockito.eq("org-1"), Mockito.eq("100"), revision.capture(), Mockito.eq(set));
        assertTrue(revision.getValue() >= floor);
    }

    @Test
    void patchContextReportsFailedOnAnErrorStatus() throws Exception {
        assertEquals(JobContextService.PatchResult.FAILED,
                patchWithResponse(503, "unavailable", new ByteArrayOutputStream()));
    }

    @Test
    void patchContextReportsFailedWithoutCallingTheApiWhenJobNotRunning() throws Exception {
        JobContextService service = Mockito.spy(new JobContextService(
                Mockito.mock(WorkspaceSecurity.class), new ObjectMapper(), "http://localhost:8080", clientWithJobStatus("failed")));

        assertEquals(JobContextService.PatchResult.FAILED, service.patchContext("org-1", "100", 1L, Map.of("key", "value")));
        Mockito.verify(service, Mockito.never()).buildConnection(Mockito.any(), Mockito.any());
    }
}
