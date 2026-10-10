package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.plugin.security.job.JobLogAccessService;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.streaming.StreamingService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContextSecurityTest {

    @Mock StorageTypeService storageTypeService;
    @Mock JobRepository jobRepository;
    @Mock StreamingService streamingService;
    @Mock JobLogAccessService jobLogAccessService;
    @Mock Authentication authentication;

    private ContextController controller;

    @BeforeEach
    void setUp() {
        ContextProperties properties = new ContextProperties();
        ContextStorageMetrics storageMetrics = new ContextStorageMetrics(new SimpleMeterRegistry());
        ContextReadService readService = new ContextReadService(storageTypeService, storageMetrics, properties, new SimpleMeterRegistry());
        controller = new ContextController(
                storageTypeService,
                jobRepository,
                new ContextSanitizer(new ObjectMapper()),
                streamingService,
                storageMetrics,
                readService,
                properties,
                null,
                jobLogAccessService);
    }

    @Test
    void getContextReturns403WhenForbidden() {
        when(jobLogAccessService.checkJobAccess(authentication, 1))
                .thenReturn(JobLogAccessService.LogAccessResult.FORBIDDEN);

        ResponseEntity<String> response = controller.getContext(1, authentication);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(storageTypeService, never()).getContext(anyInt());
    }

    @Test
    void getContextReturns404WhenNotFound() {
        when(jobLogAccessService.checkJobAccess(authentication, 99))
                .thenReturn(JobLogAccessService.LogAccessResult.NOT_FOUND);

        ResponseEntity<String> response = controller.getContext(99, authentication);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(storageTypeService, never()).getContext(anyInt());
    }

    @Test
    void getContextReturns200WhenAllowed() {
        when(jobLogAccessService.checkJobAccess(authentication, 1))
                .thenReturn(JobLogAccessService.LogAccessResult.ALLOWED);
        when(storageTypeService.getContext(1)).thenReturn("{\"plan\":{}}");

        ResponseEntity<String> response = controller.getContext(1, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"plan\":{}}", response.getBody());
    }

    @Test
    void streamContextThrows403WhenForbidden() {
        when(jobLogAccessService.checkJobAccess(authentication, 1))
                .thenReturn(JobLogAccessService.LogAccessResult.FORBIDDEN);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.streamContext("1", null, authentication));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void streamContextThrows404WhenNotFound() {
        when(jobLogAccessService.checkJobAccess(authentication, 99))
                .thenReturn(JobLogAccessService.LogAccessResult.NOT_FOUND);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.streamContext("99", null, authentication));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void streamContextReturnsEmitterWhenAllowed() {
        when(jobLogAccessService.checkJobAccess(authentication, 1))
                .thenReturn(JobLogAccessService.LogAccessResult.ALLOWED);

        SseEmitter emitter = controller.streamContext("1", null, authentication);

        assertNotNull(emitter);
        verify(streamingService).streamJobContextAsync(eq("1"), any(), any(), any());
    }

    @Test
    void saveContextReturns403WhenUserCannotWrite() throws IOException {
        when(jobLogAccessService.canWriteContext(authentication, 1)).thenReturn(false);

        ResponseEntity<String> response = controller.saveContext(1, "{\"plan\":{}}", authentication);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(storageTypeService, never()).saveContext(anyInt(), anyString());
    }

    @Test
    void saveContextAllowsInternalTokenOrInstanceOwner() throws IOException {
        when(jobLogAccessService.canWriteContext(authentication, 1)).thenReturn(true);
        Job job = mock(Job.class);
        when(job.getStatus()).thenReturn(JobStatus.running);
        when(jobRepository.findById(1)).thenReturn(Optional.of(job));
        when(storageTypeService.saveContext(1, "{\"plan\":{}}")).thenReturn("{\"plan\":{}}");

        ResponseEntity<String> response = controller.saveContext(1, "{\"plan\":{}}", authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"plan\":{}}", response.getBody());
        verify(storageTypeService).saveContext(1, "{\"plan\":{}}");
    }
}
