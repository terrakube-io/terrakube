package io.terrakube.api.plugin.logs;

import io.terrakube.api.plugin.state.model.logs.Log;
import io.terrakube.api.plugin.state.model.logs.LogsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LogsControllerTest {

    private final LogsService logsService = mock(LogsService.class);
    private final LogsProperties properties = new LogsProperties();
    private final LogsController controller = new LogsController(logsService, properties);

    private LogsRequest request(String... outputs) {
        LogsRequest request = new LogsRequest();
        request.setData(java.util.Arrays.stream(outputs).map(output -> {
            Log log = new Log();
            log.setJobId(42);
            log.setStepId("1");
            log.setLineNumber(1);
            log.setOutput(output);
            return log;
        }).toList());
        return request;
    }

    // A line counts its text plus a fixed overhead.
    private static long cost(int lines, int textBytes) {
        return (long) lines * LogLine.LINE_OVERHEAD_BYTES + textBytes;
    }

    @Test
    void aRequestExactlyAtTheLimitIsAppended() {
        properties.setAppendMaxBytes(cost(2, 100));

        controller.appendLogs(request("a".repeat(50), "b".repeat(50)));

        verify(logsService).appendLogs(any());
    }

    @Test
    void aRequestOneByteOverTheLimitIsRefusedWith413AndNothingIsWritten() {
        properties.setAppendMaxBytes(cost(2, 100));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.appendLogs(request("a".repeat(50), "b".repeat(51))));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, thrown.getStatusCode());
        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void theLimitCountsBytesNotCharacters() {
        properties.setAppendMaxBytes(cost(1, 100));

        // 60 characters of two bytes each are 120 bytes.
        assertThrows(ResponseStatusException.class, () -> controller.appendLogs(request("é".repeat(60))));
    }

    @Test
    void manyEmptyLinesAreStillRefused() {
        properties.setAppendMaxBytes(cost(10, 0));

        String[] empty = new String[11];
        java.util.Arrays.fill(empty, "");

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.appendLogs(request(empty)));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, thrown.getStatusCode());
        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void aLimitOfZeroTurnsTheCheckOff() {
        properties.setAppendMaxBytes(0);

        controller.appendLogs(request("a".repeat(10_000_000)));

        verify(logsService).appendLogs(any());
    }

    @Test
    void anEmptyRequestIsAcceptedAndWritesNothing() {
        assertEquals(HttpStatus.OK, controller.appendLogs(request()).getStatusCode());

        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void aRequestWithoutDataIsAcceptedInsteadOfFailingWithAnError() {
        // The body {} has no data list.
        assertEquals(HttpStatus.OK, controller.appendLogs(new LogsRequest()).getStatusCode());
        assertEquals(HttpStatus.OK, controller.appendLogs(null).getStatusCode());

        verify(logsService, never()).appendLogs(any());
    }
}
