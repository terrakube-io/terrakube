package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.SocketTimeoutException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobContextServiceTest {

    // patchContextFresh mints revisions for job 100 from the JVM-wide counters.
    @AfterEach
    void endJob100() {
        ContextRevision.end("100", 0);
    }

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
        return attemptWithResponse(status, responseBody, requestBody).result();
    }

    private static JobContextService.Attempt attemptWithResponse(int status, String responseBody, ByteArrayOutputStream requestBody) throws Exception {
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
        JobContextService.Attempt attempt = service.attemptPatch("org-1", "100", 1760371234567001L, set);
        // Only the patch endpoint was called: no legacy fallback.
        Mockito.verify(service, Mockito.never()).buildConnection("http://localhost:8080/context/v1/100", "GET");
        return attempt;
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

    private static final JobContextService.Attempt RETRYABLE =
            new JobContextService.Attempt(JobContextService.PatchResult.FAILED, true);
    private static final JobContextService.Attempt SKIPPED =
            new JobContextService.Attempt(JobContextService.PatchResult.SKIPPED, false);
    private static final JobContextService.Attempt APPLIED =
            new JobContextService.Attempt(JobContextService.PatchResult.APPLIED, false);

    private static JobContextService freshService() {
        return Mockito.spy(new JobContextService(
                Mockito.mock(WorkspaceSecurity.class), new ObjectMapper(), "http://localhost:8080", Mockito.mock(TerrakubeClient.class)));
    }

    @Test
    void patchContextFreshUsesTheJobExecutionRevisionAndReturnsTheResult() {
        JobContextService service = freshService();
        Map<String, Object> set = Map.of("policyEvaluation", Map.of());
        Mockito.doReturn(SKIPPED).when(service)
                .attemptPatch(Mockito.eq("org-1"), Mockito.eq("fresh-1"), Mockito.anyLong(), Mockito.eq(set));
        ContextRevision.begin("fresh-1", 1_760_371_234_567L);
        try {
            assertEquals(JobContextService.PatchResult.SKIPPED, service.patchContextFresh("org-1", "fresh-1", set));
        } finally {
            ContextRevision.end("fresh-1", 1_760_371_234_567L);
        }

        Mockito.verify(service).attemptPatch("org-1", "fresh-1", 1_760_371_234_567L * ContextRevision.SCALE + 1, set);
    }

    @Test
    void patchContextFreshRetriesAFailureWithTheSameRevision() {
        JobContextService service = freshService();
        service.retryBackoffMs = 0;
        Map<String, Object> set = Map.of("outputs/step-1", List.of());
        Mockito.doReturn(RETRYABLE, RETRYABLE, APPLIED).when(service)
                .attemptPatch(Mockito.eq("org-1"), Mockito.eq("100"), Mockito.anyLong(), Mockito.eq(set));

        assertEquals(JobContextService.PatchResult.APPLIED, service.patchContextFresh("org-1", "100", set));

        ArgumentCaptor<Long> revision = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(service, Mockito.times(3)).attemptPatch(Mockito.eq("org-1"), Mockito.eq("100"), revision.capture(), Mockito.eq(set));
        assertEquals(1, revision.getAllValues().stream().distinct().count(), "retries minted new revisions: " + revision.getAllValues());
    }

    @Test
    void patchContextFreshGivesUpAfterThreeAttempts() {
        JobContextService service = freshService();
        service.retryBackoffMs = 0;
        Mockito.doReturn(RETRYABLE).when(service)
                .attemptPatch(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any());

        assertEquals(JobContextService.PatchResult.FAILED, service.patchContextFresh("org-1", "100", Map.of("k", "v")));
        Mockito.verify(service, Mockito.times(3)).attemptPatch(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any());
    }

    @Test
    void patchContextFreshStopsRetryingWhenInterrupted() {
        JobContextService service = freshService();
        Mockito.doReturn(RETRYABLE).when(service)
                .attemptPatch(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any());

        Thread.currentThread().interrupt();
        try {
            assertEquals(JobContextService.PatchResult.FAILED, service.patchContextFresh("org-1", "100", Map.of("k", "v")));
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt status was swallowed");
        } finally {
            Thread.interrupted();
        }
        Mockito.verify(service, Mockito.times(1)).attemptPatch(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any());
    }

    @Test
    void patchContextFreshDoesNotRetryAFailureThatCannotSucceed() {
        JobContextService service = freshService();
        service.retryBackoffMs = 0;
        Mockito.doReturn(new JobContextService.Attempt(JobContextService.PatchResult.FAILED, false)).when(service)
                .attemptPatch(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any());

        assertEquals(JobContextService.PatchResult.FAILED, service.patchContextFresh("org-1", "100", Map.of("k", "v")));
        Mockito.verify(service, Mockito.times(1)).attemptPatch(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any());
    }

    private static List<ILoggingEvent> freshPatchWarns(JobContextService.Attempt first, JobContextService.Attempt... rest) {
        JobContextService service = freshService();
        service.retryBackoffMs = 0;
        Mockito.doReturn(first, (Object[]) rest).when(service)
                .attemptPatch(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any());
        Logger logger = (Logger) LoggerFactory.getLogger(JobContextService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(JobContextService.PatchResult.SKIPPED, service.patchContextFresh("org-1", "100", Map.of("k", "v")));
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void patchContextFreshWarnsOnlyWhenTheFirstAttemptIsSkipped() {
        List<ILoggingEvent> warns = freshPatchWarns(SKIPPED);
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).getFormattedMessage().endsWith("an equal or newer revision of this entry already landed"));

        // A retry skipped after a failure most likely found its own earlier attempt.
        assertTrue(freshPatchWarns(RETRYABLE, SKIPPED).isEmpty());
    }

    @Test
    void onlyIoErrorsTimeoutsAnd5xx408And429AreRetryable() throws Exception {
        for (int status : new int[]{500, 502, 503, 408, 429}) {
            assertEquals(RETRYABLE, attemptWithResponse(status, "x", new ByteArrayOutputStream()), "status " + status);
        }
        for (int status : new int[]{400, 401, 403, 409, 413}) {
            assertEquals(new JobContextService.Attempt(JobContextService.PatchResult.FAILED, false),
                    attemptWithResponse(status, "x", new ByteArrayOutputStream()), "status " + status);
        }
        assertFalse(attemptWithResponse(200, "<html>proxy</html>", new ByteArrayOutputStream()).retryable());

        JobContextService service = Mockito.spy(new JobContextService(
                Mockito.mock(WorkspaceSecurity.class), new ObjectMapper(), "http://localhost:8080", clientWithJobStatus("running")));
        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        Mockito.when(connection.getResponseCode()).thenThrow(new SocketTimeoutException("Read timed out"));
        Mockito.doReturn(connection).when(service).buildConnection("http://localhost:8080/context/v1/100/patch", "POST");
        assertEquals(RETRYABLE, service.attemptPatch("org-1", "100", 1L, Map.of("k", "v")));
    }

    @Test
    void aJobThatIsNotRunningIsNotRetryable() {
        JobContextService service = new JobContextService(
                Mockito.mock(WorkspaceSecurity.class), new ObjectMapper(), "http://localhost:8080", clientWithJobStatus("failed"));

        assertFalse(service.attemptPatch("org-1", "100", 1L, Map.of("k", "v")).retryable());
    }

    @Test
    void aMissingJobOnThePatchEndpointIsNotALegacyApi() throws Exception {
        // ContextController answers 404 "{}" for a job it does not know.
        assertEquals(new JobContextService.Attempt(JobContextService.PatchResult.FAILED, false),
                attemptWithResponse(404, "{}", new ByteArrayOutputStream()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> legacyMergeOn(int patchStatus, int saveStatus, JobContextService.PatchResult expected) throws Exception {
        Map<String, Object> set = new HashMap<>();
        set.put("planStructuredOutput/step-1", List.of(2));
        set.put("outputs/step-1", List.of());
        set.put("noChangePlan", null);
        set.put("policyEvaluation", Map.of("status", "pass"));
        return legacyMergeOn(patchStatus, saveStatus, set, expected);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> legacyMergeOn(int patchStatus, int saveStatus, Map<String, Object> set,
                                                     JobContextService.PatchResult expected) throws Exception {
        JobContextService service = Mockito.spy(new JobContextService(
                Mockito.mock(WorkspaceSecurity.class), new ObjectMapper(), "http://localhost:8080", clientWithJobStatus("running")));
        HttpURLConnection patch = Mockito.mock(HttpURLConnection.class);
        Mockito.when(patch.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        Mockito.when(patch.getResponseCode()).thenReturn(patchStatus);
        Mockito.doReturn(patch).when(service).buildConnection("http://localhost:8080/context/v1/100/patch", "POST");
        HttpURLConnection get = Mockito.mock(HttpURLConnection.class);
        Mockito.when(get.getResponseCode()).thenReturn(200);
        Mockito.when(get.getInputStream()).thenReturn(new ByteArrayInputStream((
                "{\"planStructuredOutput\":{\"step-0\":[1]},\"noChangePlan\":true,\"outputs\":\"not-a-map\",\"keep\":\"x\"}")
                .getBytes(StandardCharsets.UTF_8)));
        Mockito.doReturn(get).when(service).buildConnection("http://localhost:8080/context/v1/100", "GET");
        HttpURLConnection save = Mockito.mock(HttpURLConnection.class);
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        Mockito.when(save.getOutputStream()).thenReturn(saved);
        Mockito.when(save.getResponseCode()).thenReturn(saveStatus);
        Mockito.doReturn(save).when(service).buildConnection("http://localhost:8080/context/v1/100", "POST");

        assertEquals(expected, service.patchContext("org-1", "100", 1L, set));
        return saved.size() == 0 ? Map.of() : new ObjectMapper().readValue(saved.toByteArray(), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void patchContextFallsBackToAFullDocumentMergeWhenThePatchEndpointIsMissing() throws Exception {
        for (int status : new int[]{404, 405}) {
            Map<String, Object> saved = legacyMergeOn(status, 200, JobContextService.PatchResult.APPLIED);

            assertEquals(Map.of("step-0", List.of(1), "step-1", List.of(2)), saved.get("planStructuredOutput"));
            assertEquals(Map.of("step-1", List.of()), saved.get("outputs"));
            assertEquals(Map.of("status", "pass"), saved.get("policyEvaluation"));
            assertEquals("x", saved.get("keep"));
            assertFalse(saved.containsKey("noChangePlan"), "a null entry must delete the key");
        }
    }

    @Test
    void patchContextLegacyFallbackReportsFailedWhenTheSaveFails() throws Exception {
        legacyMergeOn(404, 500, JobContextService.PatchResult.FAILED);
    }

    @Test
    void theUpgradeWarningWaitsForAFallbackWriteThatLanded() throws Exception {
        JobContextService.LEGACY_WARNED.set(false);

        legacyMergeOn(404, 500, JobContextService.PatchResult.FAILED);
        assertFalse(JobContextService.LEGACY_WARNED.get());

        legacyMergeOn(404, 200, JobContextService.PatchResult.APPLIED);
        assertTrue(JobContextService.LEGACY_WARNED.get());
    }

    @Test
    void aLegacyDeleteNeverCreatesOrReplacesTheParent() throws Exception {
        Map<String, Object> set = new HashMap<>();
        set.put("missing/step-1", null);
        set.put("keep/step-1", null);
        set.put("planStructuredOutput/step-0", null);

        Map<String, Object> saved = legacyMergeOn(404, 200, set, JobContextService.PatchResult.APPLIED);

        assertFalse(saved.containsKey("missing"));
        assertEquals("x", saved.get("keep"));
        assertEquals(Map.of(), saved.get("planStructuredOutput"));
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
