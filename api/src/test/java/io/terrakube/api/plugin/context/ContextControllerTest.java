package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.streaming.StreamingService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextControllerTest {

    private final Principal executorPrincipal = Mockito.mock(Principal.class);

    private final Principal uiPrincipal = Mockito.mock(Principal.class);

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);

    {
        InMemoryContextWriteLock.stub(redisTemplate, valueOperations);
    }

    private final io.terrakube.api.plugin.policy.PolicyEvaluationService policyEvaluationService =
            Mockito.mock(io.terrakube.api.plugin.policy.PolicyEvaluationService.class);

    private AuthenticatedUser authenticatedUser() {
        AuthenticatedUser authenticatedUser = Mockito.mock(AuthenticatedUser.class);
        when(authenticatedUser.isServiceAccountInternal(Mockito.any()))
                .thenAnswer(invocation -> invocation.<com.yahoo.elide.core.security.User>getArgument(0).getPrincipal() == executorPrincipal);
        return authenticatedUser;
    }

    private ContextController controller(StorageTypeService storageTypeService, JobRepository jobRepository) {
        return controller(storageTypeService, jobRepository, new SimpleMeterRegistry());
    }

    private ContextController controller(StorageTypeService storageTypeService, JobRepository jobRepository, MeterRegistry meterRegistry) {
        return controller(storageTypeService, jobRepository, meterRegistry, Mockito.mock(StreamingService.class));
    }

    private ContextController controller(StorageTypeService storageTypeService, JobRepository jobRepository, MeterRegistry meterRegistry,
                                         StreamingService streamingService) {
        ContextProperties properties = new ContextProperties();
        ContextStorageMetrics storageMetrics = new ContextStorageMetrics(meterRegistry);
        ContextReadService readService = new ContextReadService(storageTypeService, storageMetrics, properties, meterRegistry);
        ContextSanitizer sanitizer = new ContextSanitizer(new ObjectMapper());
        ContextWriteService writeService = new ContextWriteService(jobRepository, new ContextWriteLock(redisTemplate), readService,
                sanitizer, storageTypeService, storageMetrics, new ObjectMapper());
        return new ContextController(sanitizer, streamingService, readService, writeService, properties,
                policyEvaluationService, null, authenticatedUser(), new ObjectMapper());
    }

    /** A storage mock whose reads return the last saved context, so successive writes build on each other. */
    private StorageTypeService inMemoryStorage(String initialContext) {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        AtomicReference<String> stored = new AtomicReference<>(initialContext);
        when(storageTypeService.getContext(1)).thenAnswer(invocation -> stored.get());
        when(storageTypeService.saveContext(Mockito.eq(1), Mockito.anyString())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return stored.get();
        });
        return storageTypeService;
    }

    private JobRepository runningJob() {
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.running);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        return jobRepository;
    }

    @Test
    void patchMergesOnlyTouchedEntriesUnderTheWriteLock() throws IOException {
        StorageTypeService storageTypeService = inMemoryStorage("{\"planRenderedText\":{\"s1\":\"text\"}}");
        JobRepository jobRepository = runningJob();
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.patchContext(1,
                "{\"revision\":10,\"set\":{\"planStructuredOutput/s1\":[],\"structuredOutputStatus\":{\"state\":\"COMPLETE\"}}}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"applied\":2,\"skipped\":0}", response.getBody());
        verify(valueOperations).setIfAbsent(Mockito.eq("job-context-write-lock:1"), Mockito.anyString(), Mockito.any(Duration.class));
        verify(redisTemplate).execute(Mockito.any(RedisScript.class), Mockito.eq(List.of("job-context-write-lock:1")), Mockito.anyString());
        verify(storageTypeService).saveContext(1, "{\"planRenderedText\":{\"s1\":\"text\"},"
                + "\"contextRevisions\":{\"planStructuredOutput/s1\":10,\"structuredOutputStatus\":10},"
                + "\"planStructuredOutput\":{\"s1\":[]},\"structuredOutputStatus\":{\"state\":\"COMPLETE\"}}");
    }

    @Test
    void allStalePatchWritesNothing() throws IOException {
        StorageTypeService storageTypeService = inMemoryStorage("{\"contextRevisions\":{\"terrakubeUI/s1\":10},\"terrakubeUI\":{\"s1\":\"final\"}}");
        ContextController controller = controller(storageTypeService, runningJob());

        ResponseEntity<String> response = controller.patchContext(1, "{\"revision\":10,\"set\":{\"terrakubeUI/s1\":\"progress\"}}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"applied\":0,\"skipped\":1}", response.getBody());
        verify(storageTypeService, never()).saveContext(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void patchRejectsInvalidPayloadsBeforeTakingTheLock() {
        StorageTypeService storageTypeService = inMemoryStorage(null);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        ContextController controller = controller(storageTypeService, jobRepository);
        long future = (System.currentTimeMillis() + 20_000) * ContextPatchApplier.REVISION_EPOCH_SCALE;
        StringBuilder oversized = new StringBuilder("{\"revision\":1,\"set\":{");
        for (int i = 0; i <= ContextPatchApplier.MAX_ENTRIES; i++) {
            oversized.append(i == 0 ? "" : ",").append("\"terraformOutputs/s").append(i).append("\":[]");
        }
        oversized.append("}}");

        for (String patch : new String[]{
                "{",
                "[]",
                "{\"set\":{\"a\":1}}",
                "{\"revision\":0,\"set\":{\"a\":1}}",
                "{\"revision\":\"5\",\"set\":{\"a\":1}}",
                "{\"revision\":1.5,\"set\":{\"a\":1}}",
                "{\"revision\":" + future + ",\"set\":{\"a\":1}}",
                "{\"revision\":1}",
                "{\"revision\":1,\"set\":{}}",
                "{\"revision\":1,\"set\":[]}",
                oversized.toString(),
                "{\"revision\":1,\"set\":{\"a/b/c\":1}}",
                "{\"revision\":1,\"set\":{\"a/\":1}}",
                "{\"revision\":1,\"set\":{\"/b\":1}}",
                "{\"revision\":1,\"set\":{\"contextRevisions\":{}}}",
                "{\"revision\":1,\"set\":{\"contextRevisions/a\":1}}"}) {
            assertEquals(HttpStatus.BAD_REQUEST, controller.patchContext(1, patch).getStatusCode(), patch);
        }
        Mockito.verifyNoInteractions(jobRepository, storageTypeService, redisTemplate);
    }

    @Test
    void patchForMissingJobReturnsNotFound() throws IOException {
        StorageTypeService storageTypeService = inMemoryStorage(null);
        ContextController controller = controller(storageTypeService, Mockito.mock(JobRepository.class));

        ResponseEntity<String> response = controller.patchContext(1, "{\"revision\":1,\"set\":{\"a\":1}}");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(storageTypeService, never()).saveContext(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void patchForTerminalJobReturnsConflict() throws IOException {
        StorageTypeService storageTypeService = inMemoryStorage(null);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.failed);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.patchContext(1, "{\"revision\":1,\"set\":{\"a\":1}}");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(storageTypeService, never()).saveContext(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void writesFailWithoutWritingWhenTheStoredContextCannotBeRead() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        when(storageTypeService.getContext(1)).thenThrow(new RuntimeException("apiCallTimeout"));
        ContextController controller = controller(storageTypeService, runningJob());

        ResponseEntity<String> patched = controller.patchContext(1, "{\"revision\":1,\"set\":{\"a\":1}}");
        ResponseEntity<String> replaced = controller.saveContext(1, "{\"a\":1}");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, patched.getStatusCode());
        assertEquals("5", patched.getHeaders().getFirst("Retry-After"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, replaced.getStatusCode());
        verify(storageTypeService, never()).saveContext(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void patchFailsWithoutWritingWhenTheStoredContextIsMalformed() throws IOException {
        for (String malformed : new String[]{"{\"truncated\":", "[]"}) {
            StorageTypeService storageTypeService = inMemoryStorage(malformed);
            ContextController controller = controller(storageTypeService, runningJob());

            ResponseEntity<String> response = controller.patchContext(1, "{\"revision\":1,\"set\":{\"a\":1}}");

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(), malformed);
            verify(storageTypeService, never()).saveContext(Mockito.anyInt(), Mockito.anyString());
        }
    }

    @Test
    void legacySaveRepairsAMalformedStoredContext() throws IOException {
        StorageTypeService storageTypeService = inMemoryStorage("{\"truncated\":");
        ContextController controller = controller(storageTypeService, runningJob());

        ResponseEntity<String> response = controller.saveContext(1, "{\"a\":1}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(storageTypeService).saveContext(1, "{\"a\":1}");
    }

    @Test
    void legacySaveRejectsANonObjectBody() throws IOException {
        StorageTypeService storageTypeService = inMemoryStorage(null);
        JobRepository jobRepository = runningJob();
        ContextController controller = controller(storageTypeService, jobRepository);

        for (String body : new String[]{"[]", "\"text\"", "1", "null"}) {
            assertEquals(HttpStatus.BAD_REQUEST, controller.saveContext(1, body).getStatusCode(), body);
        }
        verify(jobRepository, never()).findById(Mockito.anyInt());
        verify(storageTypeService, never()).saveContext(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void legacySaveRunsThePolicyHookOnlyWhenPolicyEvaluationChanges() {
        StorageTypeService storageTypeService = inMemoryStorage(null);
        ContextController controller = controller(storageTypeService, runningJob());

        controller.saveContext(1, "{\"policyEvaluation\":{\"status\":\"PASSED\"}}");
        // A script writing back what it read, plus its own key.
        controller.saveContext(1, "{\"policyEvaluation\":{\"status\":\"PASSED\"},\"custom\":1}");
        verify(policyEvaluationService, times(1)).processPolicyEvaluationContext(Mockito.eq(1), Mockito.anyString());

        controller.saveContext(1, "{\"policyEvaluation\":{\"status\":\"FAILED\"}}");
        verify(policyEvaluationService, times(2)).processPolicyEvaluationContext(Mockito.eq(1), Mockito.anyString());

        // A patched (revisioned) policyEvaluation cannot be changed by a full replace, so the hook does not re-run.
        controller.patchContext(1, "{\"revision\":1,\"set\":{\"policyEvaluation\":{\"status\":\"PASSED\"}}}");
        controller.saveContext(1, "{\"policyEvaluation\":{\"status\":\"FORGED\"}}");
        verify(policyEvaluationService, times(3)).processPolicyEvaluationContext(Mockito.eq(1), Mockito.anyString());
    }

    @Test
    void writeLockFailureReturnsServiceUnavailableWithRetryAfter() throws IOException {
        StorageTypeService storageTypeService = inMemoryStorage(null);
        Mockito.doThrow(new RedisConnectionFailureException("Redis down"))
                .when(valueOperations).setIfAbsent(Mockito.anyString(), Mockito.any(), Mockito.any(Duration.class));
        ContextController controller = controller(storageTypeService, runningJob());

        ResponseEntity<String> response = controller.patchContext(1, "{\"revision\":1,\"set\":{\"a\":1}}");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.saveContext(1, "{\"a\":1}").getStatusCode());
        verify(storageTypeService, never()).saveContext(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void policyHookRunsOnlyWhenAPatchAppliesPolicyEvaluation() {
        StorageTypeService storageTypeService = inMemoryStorage(null);
        ContextController controller = controller(storageTypeService, runningJob());

        controller.patchContext(1, "{\"revision\":1,\"set\":{\"terrakubeUI/s1\":\"ui\"}}");
        verify(policyEvaluationService, never()).processPolicyEvaluationContext(Mockito.anyInt(), Mockito.anyString());

        controller.patchContext(1, "{\"revision\":2,\"set\":{\"policyEvaluation\":{\"status\":\"PASSED\"}}}");
        verify(policyEvaluationService).processPolicyEvaluationContext(Mockito.eq(1),
                Mockito.argThat(context -> context.contains("\"policyEvaluation\"") && context.contains("\"terrakubeUI\"")));

        // A retried (stale) policy patch must not re-run the hook.
        controller.patchContext(1, "{\"revision\":2,\"set\":{\"policyEvaluation\":{\"status\":\"PASSED\"}}}");
        verify(policyEvaluationService, times(1)).processPolicyEvaluationContext(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void legacySaveKeepsNewerPatchedEntries() throws IOException {
        StorageTypeService storageTypeService = inMemoryStorage(null);
        ContextController controller = controller(storageTypeService, runningJob());
        controller.patchContext(1, "{\"revision\":7,\"set\":{\"planRenderedText/s1\":\"text\"}}");

        // A script that read the document before the patch writes it back with its own key.
        ResponseEntity<String> response = controller.saveContext(1, "{\"custom\":\"value\"}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"custom\":\"value\",\"planRenderedText\":{\"s1\":\"text\"},"
                + "\"contextRevisions\":{\"planRenderedText/s1\":7}}", response.getBody());
    }

    @Test
    void rejectsInvalidJsonPayloads() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.saveContext(1, "{");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storageTypeService, never()).saveContext(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void rejectsContextWritesForTerminalFailureJobs() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.cancelled);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.saveContext(1, "{}");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(storageTypeService, never()).saveContext(Mockito.anyInt(), Mockito.anyString());
    }

    @Test
    void savesContextForQueuedJobs() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.queue);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        when(storageTypeService.saveContext(1, "{\"planStructuredOutput\":{}}"))
                .thenReturn("{\"planStructuredOutput\":{}}");
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.saveContext(1, "{\"planStructuredOutput\":{}}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"planStructuredOutput\":{}}", response.getBody());
    }

    @Test
    void savesContextForCompletedJobs() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.completed);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        when(storageTypeService.saveContext(1, "{\"planStructuredOutput\":{}}"))
                .thenReturn("{\"planStructuredOutput\":{}}");
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.saveContext(1, "{\"planStructuredOutput\":{}}");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"planStructuredOutput\":{}}", response.getBody());
    }

    @Test
    void redactsSensitiveValuesWhenReadingContext() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        when(storageTypeService.getContext(22)).thenReturn("""
                {
                  "planStructuredOutput": {
                    "step-1": [
                      {
                        "before": {
                          "variables": [
                            {
                              "name": "CONSUMER_COUNT",
                              "value": "0"
                            }
                          ]
                        },
                        "beforeSensitive": {
                          "variables": [
                            {
                              "value": true
                            }
                          ]
                        },
                        "after": {
                          "variables": [
                            {
                              "name": "CONSUMER_COUNT",
                              "value": "2"
                            }
                          ]
                        },
                        "afterSensitive": {
                          "variables": [
                            {
                              "value": true
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
                """);
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.getContext(22, uiPrincipal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().contains("\"value\":\"0\""));
        assertFalse(response.getBody().contains("\"value\":\"2\""));
        assertTrue(response.getBody().contains("\"name\":\"CONSUMER_COUNT\""));
        assertTrue(response.getBody().contains("\"value\":null"));
    }

    @Test
    void redactsSensitiveValuesBeforeSavingContext() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.queue);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        when(storageTypeService.saveContext(Mockito.eq(1), Mockito.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.saveContext(1, """
                {
                  "planStructuredOutput": {
                    "step-1": [
                      {
                        "before": {
                          "variables": [
                            {
                              "name": "CONSUMER_COUNT",
                              "value": "0"
                            }
                          ]
                        },
                        "beforeSensitive": {
                          "variables": [
                            {
                              "value": true
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().contains("\"value\":\"0\""));
        verify(storageTypeService).saveContext(Mockito.eq(1), Mockito.argThat(savedContext -> !savedContext.contains("\"value\":\"0\"")));
    }

    @Test
    void redactsSensitiveValuesFromApplyStructuredOutput() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.running);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        when(storageTypeService.saveContext(Mockito.eq(1), Mockito.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.saveContext(1, """
                {
                  "applyStructuredOutput": {
                    "step-1": [
                      {
                        "address": "aws_instance.example",
                        "before": {"password": "old-secret"},
                        "beforeSensitive": {"password": true},
                        "after": {"password": "new-secret"},
                        "afterSensitive": {"password": true},
                        "status": "applied"
                      }
                    ]
                  }
                }
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().contains("old-secret"));
        assertFalse(response.getBody().contains("new-secret"));
        assertTrue(response.getBody().contains("\"status\":\"applied\""));
    }

    @Test
    void redactsSensitiveValuesFromTerraformOutputs() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.running);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        when(storageTypeService.saveContext(Mockito.eq(1), Mockito.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.saveContext(1, """
                {
                  "terraformOutputs": {
                    "step-1": [
                      {"name": "random_value", "value": "sad-otter", "sensitive": false},
                      {"name": "random_password_result", "value": "top-secret", "sensitive": true}
                    ]
                  }
                }
                """);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().contains("top-secret"));
        assertTrue(response.getBody().contains("\"value\":\"sad-otter\""));
        assertTrue(response.getBody().contains("\"name\":\"random_password_result\""));
    }

    @Test
    void storageReadFailureReturnsServiceUnavailableNotFiveHundred() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        when(storageTypeService.getContext(9)).thenThrow(new RuntimeException("apiCallTimeout"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ContextController controller = controller(storageTypeService, jobRepository, registry);

        ResponseEntity<String> response = controller.getContext(9, uiPrincipal);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(response.getBody().contains("\"state\":\"UNAVAILABLE\""));
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
        assertEquals(1.0, registry.get("terrakube.api.context.storage.failures")
                .tag("operation", "read").counter().count());
    }

    @Test
    void missingContextReturnsPendingNotEmptyPlan() {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        when(storageTypeService.getContext(4)).thenReturn(null);
        ContextController controller = controller(storageTypeService, jobRepository);

        ResponseEntity<String> response = controller.getContext(4, uiPrincipal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"state\":\"PENDING\""));
    }

    @Test
    void successfulWriteRefreshesTheReadCache() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.running);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        when(storageTypeService.saveContext(Mockito.eq(1), Mockito.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        ContextController controller = controller(storageTypeService, jobRepository);

        controller.saveContext(1, "{\"planStructuredOutput\":{\"step-1\":[]}}");
        ResponseEntity<String> read = controller.getContext(1, uiPrincipal);

        assertEquals(HttpStatus.OK, read.getStatusCode());
        assertTrue(read.getBody().contains("\"planStructuredOutput\""));
        // Only the write's own locked fresh read touched storage; the UI read is served from the cache.
        verify(storageTypeService, times(1)).getContext(1);
    }

    @Test
    void executorReadBypassesStaleCacheAndReturnsStorageValue() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.running);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        when(storageTypeService.saveContext(Mockito.eq(1), Mockito.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        ContextController controller = controller(storageTypeService, jobRepository);
        // This pod caches the "stale" write; a newer snapshot then lands in storage via another replica.
        controller.saveContext(1, "{\"planStructuredOutput\":{\"stale\":[]}}");
        when(storageTypeService.getContext(1)).thenReturn("{\"planStructuredOutput\":{\"fresh\":[]}}");

        ResponseEntity<String> read = controller.getContext(1, executorPrincipal);

        assertEquals(HttpStatus.OK, read.getStatusCode());
        assertTrue(read.getBody().contains("\"fresh\""));
        // One read by the write itself, one by the executor.
        verify(storageTypeService, times(2)).getContext(1);
        // The stale cache entry is dropped, so the next UI read goes back to storage.
        assertTrue(controller.getContext(1, uiPrincipal).getBody().contains("\"fresh\""));
        verify(storageTypeService, times(3)).getContext(1);
    }

    @Test
    void uiReadIsServedFromCache() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        when(storageTypeService.getContext(1)).thenReturn("{\"planStructuredOutput\":{}}");
        ContextController controller = controller(storageTypeService, jobRepository);

        controller.getContext(1, uiPrincipal);
        ResponseEntity<String> read = controller.getContext(1, uiPrincipal);

        assertEquals(HttpStatus.OK, read.getStatusCode());
        verify(storageTypeService, times(1)).getContext(1);
    }

    @Test
    void storageWriteFailureReturnsServiceUnavailableNotFiveHundred() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        Job job = Mockito.mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.running);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        when(storageTypeService.saveContext(Mockito.eq(1), Mockito.anyString()))
                .thenThrow(new RuntimeException("S3 unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ContextController controller = controller(storageTypeService, jobRepository, registry);

        ResponseEntity<String> response = controller.saveContext(1, "{\"planStructuredOutput\":{}}");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(1.0, registry.get("terrakube.api.context.storage.failures")
                .tag("operation", "write").counter().count());
    }

    @Test
    void streamEndpointDelegatesToStreamingServiceWithJobId() {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        StreamingService streamingService = Mockito.mock(StreamingService.class);
        ContextController controller = controller(storageTypeService, jobRepository, new SimpleMeterRegistry(), streamingService);

        controller.streamContext("42", null);

        verify(streamingService).streamJobContextAsync(Mockito.eq("42"), Mockito.any(), Mockito.eq(RecordId.of("0-0")), Mockito.any());
    }
}
