package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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

    @Test
    void rejectsInvalidJsonPayloads() throws IOException {
        StorageTypeService storageTypeService = Mockito.mock(StorageTypeService.class);
        JobRepository jobRepository = Mockito.mock(JobRepository.class);
        ContextController controller = new ContextController(storageTypeService, jobRepository, new ObjectMapper());

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
        ContextController controller = new ContextController(storageTypeService, jobRepository, new ObjectMapper());

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
        ContextController controller = new ContextController(storageTypeService, jobRepository, new ObjectMapper());

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
        ContextController controller = new ContextController(storageTypeService, jobRepository, new ObjectMapper());

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
        ContextController controller = new ContextController(storageTypeService, jobRepository, new ObjectMapper());

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
        ContextController controller = new ContextController(storageTypeService, jobRepository, new ObjectMapper());

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
}
