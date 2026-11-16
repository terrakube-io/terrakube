package io.terrakube.api.plugin.scheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionLeaseTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ListAppender<ILoggingEvent> logs;
    private Logger leaseLogger;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        logs = new ListAppender<>();
        logs.start();
        leaseLogger = (Logger) LoggerFactory.getLogger(ExecutionLease.class);
        leaseLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        leaseLogger.detachAppender(logs);
    }

    /**
     * The 30 second renewal can already be inside renew() when the work finishes: it has passed
     * the lost check, then the worker releases and deletes the key, then the renew script runs and
     * finds nothing. That is a normal end of the run and must not be reported as a lost lock.
     */
    @Test
    void aRenewalRacingTheOwnReleaseDoesNotMarkTheLeaseLostOrLogAnError() {
        ExecutionLease lease = ExecutionLease.acquire(redisTemplate, 7, Duration.ofSeconds(90), Duration.ZERO).orElseThrow();
        AtomicInteger scripts = new AtomicInteger();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(call -> {
            if (scripts.getAndIncrement() == 0) {
                // the renew script is about to run; the worker finishes and releases first
                lease.release();
            }
            return 0L;
        });

        assertThat(lease.renew()).isFalse();

        assertThat(lease.isLost()).isFalse();
        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
    }

    @Test
    void aRenewalThatFindsAnotherOwnerStillMarksTheLeaseLostAndLogsAnError() {
        ExecutionLease lease = ExecutionLease.acquire(redisTemplate, 8, Duration.ofSeconds(90), Duration.ZERO).orElseThrow();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        assertThat(lease.renew()).isFalse();

        assertThat(lease.isLost()).isTrue();
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.ERROR);
    }
}
