package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.streaming.StreamingService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextControllerTest {

    private ContextController controller(StorageTypeService storageTypeService, JobRepository jobRepository) {
        return controller(storageTypeService, jobRepository, new SimpleMeterRegistry());
    }

    private ContextController controller(StorageTypeService storageTypeService, JobRepository jobRepository, MeterRegistry meterRegistry) {
        return new ContextController(storageTypeService, jobRepository, new ContextSanitizer(new ObjectMapper()),
                Mockito.mock(StreamingService.class), new ContextStorageMetrics(meterRegistry));
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

        ResponseEntity<String> response = controller.getContext(22);

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

        ResponseEntity<String> response = controller.getContext(9);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("{}", response.getBody());
        assertEquals(1.0, registry.get("terrakube.api.context.storage.failures")
                .tag("operation", "read").counter().count());
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
        ContextController controller = new ContextController(storageTypeService, jobRepository, new ContextSanitizer(new ObjectMapper()), streamingService, new ContextStorageMetrics(new SimpleMeterRegistry()));

        controller.streamContext("42", null);

        verify(streamingService).streamJobContextAsync(Mockito.eq("42"), Mockito.any(), Mockito.eq(RecordId.of("0-0")), Mockito.any());
    }
}
