package io.terrakube.api.plugin.storage.controller;

import io.terrakube.api.plugin.logs.StepLogService;
import io.terrakube.api.plugin.storage.model.StepOutputStream;
import io.terrakube.api.plugin.streaming.JobLogBroadcasterRegistry;
import io.terrakube.api.plugin.streaming.SseCapacityExceededException;
import io.terrakube.api.plugin.streaming.StreamingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TerraformOutputControllerTest {

    @Mock StepLogService stepLogService;
    @Mock StreamingService streamingService;
    @Mock JobLogBroadcasterRegistry broadcasterRegistry;

    @InjectMocks TerraformOutputController controller;

    @Test
    void returns404WhenTerminalStepHasNoObject() {
        when(streamingService.getCurrentLogs("s", "")).thenReturn("");
        when(stepLogService.resolve("o", "j", "s")).thenReturn(StepLogService.StepLog.missing());

        ResponseEntity<?> response = controller.getFile("o", "j", "s", null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void returnsCachedBytesWithImmutableCacheHeaderForTerminalStep() {
        when(streamingService.getCurrentLogs("s", "")).thenReturn("");
        byte[] body = "done".getBytes();
        when(stepLogService.resolve("o", "j", "s")).thenReturn(StepLogService.StepLog.cached(body));

        ResponseEntity<?> response = controller.getFile("o", "j", "s", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("public, max-age=31536000, immutable", response.getHeaders().getCacheControl());
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
    }

    @Test
    void servesLiveRedisLogsWithNoStoreWhenStreamHasData() {
        when(streamingService.getCurrentLogs("s", "")).thenReturn("live line\n");

        ResponseEntity<?> response = controller.getFile("o", "j", "s", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void slicesCachedBytesForARangeRequestAndReturns206() {
        when(streamingService.getCurrentLogs("s", "")).thenReturn("");
        byte[] body = "0123456789".getBytes();
        when(stepLogService.resolve("o", "j", "s")).thenReturn(StepLogService.StepLog.cached(body));

        ResponseEntity<?> response = controller.getFile("o", "j", "s", "bytes=-3");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 7-9/10", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals("789", new String((byte[]) response.getBody()));
    }

    @Test
    void invalidRangeOnCachedBytesFallsBackToFull200() {
        when(streamingService.getCurrentLogs("s", "")).thenReturn("");
        byte[] body = "0123456789".getBytes();
        when(stepLogService.resolve("o", "j", "s")).thenReturn(StepLogService.StepLog.cached(body));

        ResponseEntity<?> response = controller.getFile("o", "j", "s", "bytes=8-2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void rangeAgainstLargeStreamableObjectDelegatesToOpenStreamWithParsedRange() {
        when(streamingService.getCurrentLogs("s", "")).thenReturn("");
        when(stepLogService.resolve("o", "j", "s")).thenReturn(StepLogService.StepLog.streamable(50_000_000L));
        when(stepLogService.openStream(eq("o"), eq("j"), eq("s"), any()))
                .thenReturn(StepOutputStream.partial(
                        new ByteArrayInputStream("tail".getBytes()), 4,
                        "bytes 49999996-49999999/50000000", 50_000_000L));

        ResponseEntity<?> response = controller.getFile("o", "j", "s", "bytes=-4");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 49999996-49999999/50000000",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void streamOutputDelegatesToTheBroadcasterRegistry() {
        SseEmitter emitter = new SseEmitter(0L);
        when(broadcasterRegistry.subscribe(eq("j"), eq("s"), any())).thenReturn(emitter);

        SseEmitter result = controller.streamOutput("o", "j", "s", null);

        assertSame(emitter, result);
    }

    @Test
    void sseCapacityExceededMapsTo503WithRetryAfter() {
        ResponseEntity<Void> response = controller.onSseCapacityExceeded();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }
}
