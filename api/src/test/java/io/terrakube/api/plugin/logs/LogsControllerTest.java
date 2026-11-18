package io.terrakube.api.plugin.logs;

import io.terrakube.api.plugin.security.job.JobLogAccessService;
import io.terrakube.api.plugin.state.model.logs.Log;
import io.terrakube.api.plugin.state.model.logs.LogsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LogsControllerTest {

    private final LogsService logsService = mock(LogsService.class);
    private final LogsProperties properties = new LogsProperties();
    private final JobLogAccessService jobLogAccessService = new JobLogAccessService(null, null, null, null, null, "");
    private final LogsController controller = new LogsController(logsService, properties, jobLogAccessService);

    private static JwtAuthenticationToken executorServiceToken() {
        Jwt jwt = Jwt.withTokenValue("mock-executor-token")
                .header("alg", "none")
                .claim("iss", "TerrakubeInternal")
                .claim("sub", "TerrakubeInternal (EXECUTOR)")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private static JwtAuthenticationToken workspaceExecutorToken(int jobId) {
        Jwt jwt = Jwt.withTokenValue("mock-runner-token")
                .header("alg", "none")
                .claim("iss", "TerrakubeInternal")
                .claim("workspaceId", UUID.randomUUID().toString())
                .claim("jobId", String.valueOf(jobId))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private static JwtAuthenticationToken userPatToken() {
        Jwt jwt = Jwt.withTokenValue("mock-pat-token")
                .header("alg", "none")
                .claim("iss", "https://auth.terrakube.io")
                .claim("email", "attacker@corp.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

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

        controller.appendLogs(request("a".repeat(50), "b".repeat(50)), executorServiceToken());

        verify(logsService).appendLogs(any());
    }

    @Test
    void aRequestOneByteOverTheLimitIsRefusedWith413AndNothingIsWritten() {
        properties.setAppendMaxBytes(cost(2, 100));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.appendLogs(request("a".repeat(50), "b".repeat(51)), executorServiceToken()));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, thrown.getStatusCode());
        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void theLimitCountsBytesNotCharacters() {
        properties.setAppendMaxBytes(cost(1, 100));

        // 60 characters of two bytes each are 120 bytes.
        assertThrows(ResponseStatusException.class, () -> controller.appendLogs(request("é".repeat(60)), executorServiceToken()));
    }

    @Test
    void manyEmptyLinesAreStillRefused() {
        properties.setAppendMaxBytes(cost(10, 0));

        String[] empty = new String[11];
        java.util.Arrays.fill(empty, "");

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.appendLogs(request(empty), executorServiceToken()));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, thrown.getStatusCode());
        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void aLimitOfZeroTurnsTheCheckOff() {
        properties.setAppendMaxBytes(0);

        controller.appendLogs(request("a".repeat(10_000_000)), executorServiceToken());

        verify(logsService).appendLogs(any());
    }

    @Test
    void anEmptyRequestIsAcceptedAndWritesNothing() {
        assertEquals(HttpStatus.OK, controller.appendLogs(request(), executorServiceToken()).getStatusCode());

        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void aRequestWithoutDataIsAcceptedInsteadOfFailingWithAnError() {
        // The body {} has no data list.
        assertEquals(HttpStatus.OK, controller.appendLogs(new LogsRequest(), executorServiceToken()).getStatusCode());
        assertEquals(HttpStatus.OK, controller.appendLogs(null, executorServiceToken()).getStatusCode());

        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void appendLogsWithUserPatTokenIsForbidden() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.appendLogs(request("injected log line"), userPatToken()));

        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void appendLogsWithNullPrincipalIsForbidden() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.appendLogs(request("injected log line"), null));

        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void appendLogsWithWorkspaceExecutorTokenIsForbidden() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.appendLogs(request("executor line"), workspaceExecutorToken(42)));

        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        verify(logsService, never()).appendLogs(any());
    }

    @Test
    void setupConsumerGroupsWithExecutorTokenSucceeds() {
        assertEquals(HttpStatus.OK, controller.setupConsumerGroups("42", executorServiceToken()).getStatusCode());
        verify(logsService).setupConsumerGroups("42");
    }

    @Test
    void setupConsumerGroupsWithUserPatTokenIsForbidden() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.setupConsumerGroups("42", userPatToken()));

        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        verify(logsService, never()).setupConsumerGroups("42");
    }

    @Test
    void setupConsumerGroupsWithWorkspaceExecutorTokenIsForbidden() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.setupConsumerGroups("42", workspaceExecutorToken(42)));

        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        verify(logsService, never()).setupConsumerGroups("42");
    }

    @Test
    void setupConsumerGroupsWithNullPrincipalIsForbidden() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.setupConsumerGroups("42", null));

        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        verify(logsService, never()).setupConsumerGroups("42");
    }
}
