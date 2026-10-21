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

    @Test
    void aRequestWithinTheLimitIsAppended() {
        properties.setAppendMaxBytes(100);

        controller.appendLogs(request("a".repeat(50), "b".repeat(50)));

        verify(logsService).appendLogs(any());
    }

    @Test
    void aRequestOneByteOverTheLimitIsRefusedWith413AndNothingIsWritten() {
        properties.setAppendMaxBytes(100);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.appendLogs(request("a".repeat(50), "b".repeat(51))));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, thrown.getStatusCode());
        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void theLimitCountsBytesNotCharacters() {
        properties.setAppendMaxBytes(100);

        // 60 characters of two bytes each are 120 bytes.
        assertThrows(ResponseStatusException.class, () -> controller.appendLogs(request("é".repeat(60))));
    }

    @Test
    void aLimitOfZeroTurnsTheCheckOff() {
        properties.setAppendMaxBytes(0);

        controller.appendLogs(request("a".repeat(10_000_000)));

        verify(logsService).appendLogs(any());
    }

    @Test
    void anEmptyRequestIsStillAccepted() {
        controller.appendLogs(request());

        verify(logsService).appendLogs(List.of());
    }
}
