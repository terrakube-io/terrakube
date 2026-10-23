package io.terrakube.executor.service.logs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogsConsumerTest {

    private final Logger consumerLog = (Logger) LoggerFactory.getLogger(LogsConsumer.class);
    private ListAppender<ILoggingEvent> serviceLog;

    @BeforeEach
    void captureTheServiceLog() {
        serviceLog = new ListAppender<>();
        serviceLog.start();
        consumerLog.addAppender(serviceLog);
    }

    @AfterEach
    void stopCapturing() {
        consumerLog.detachAppender(serviceLog);
    }

    private LogsConsumer consumer(ProcessLogs processLogs, StepOutputBuffer saved) {
        return LogsConsumer.builder()
                .jobId(42)
                .stepId("step-1")
                .terraformOutput(saved)
                .processLogs(processLogs)
                .lineNumber(new AtomicInteger(0))
                .build();
    }

    @Test
    void theOutputIsNotCopiedIntoTheServiceLogWhenItIsSwitchedOff() {
        ProcessLogs processLogs = mock(ProcessLogs.class);
        when(processLogs.echoToServiceLog()).thenReturn(false);
        StepOutputBuffer saved = new StepOutputBuffer();

        consumer(processLogs, saved).accept("aws_instance.web: Creating...");

        assertTrue(serviceLog.list.isEmpty());
        assertEquals("aws_instance.web: Creating..." + System.lineSeparator(), saved.toString());
        verify(processLogs).sendLogs(42, "step-1", 1, "aws_instance.web: Creating...");
    }

    @Test
    void theOutputIsCopiedIntoTheServiceLogWhenItIsSwitchedOn() {
        ProcessLogs processLogs = mock(ProcessLogs.class);
        when(processLogs.echoToServiceLog()).thenReturn(true);

        consumer(processLogs, new StepOutputBuffer()).accept("aws_instance.web: Creating...");

        assertEquals(1, serviceLog.list.size());
        assertEquals("aws_instance.web: Creating...", serviceLog.list.get(0).getFormattedMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aVeryLongLineIsCutInTheLiveStreamButKeptWholeInTheSavedOutput() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(stream);
        ExecutorLogsProperties properties = new ExecutorLogsProperties();
        properties.setLiveLineMaxBytes(1_000);
        StepOutputBuffer saved = new StepOutputBuffer();
        String line = "x".repeat(50_000);

        consumer(new LogsServiceRedis(redisTemplate, properties), saved).accept(line);

        ArgumentCaptor<Map<String, String>> sent = ArgumentCaptor.forClass(Map.class);
        verify(stream).add(eq("42"), sent.capture(), any(XAddOptions.class));
        assertTrue(sent.getValue().get("output").length() < 1_200);
        assertTrue(sent.getValue().get("output").contains("49000 bytes left out of the live view"));
        assertEquals(line + System.lineSeparator(), saved.toString());
    }
}
