package io.terrakube.executor.service.executor;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.status.UpdateJobStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class JobExecutionWatchdogTest {

    private final List<Object> publishedEvents = new ArrayList<>();
    private final ApplicationEventPublisher eventPublisher = publishedEvents::add;
    private final RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
    private final ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
    private final UpdateJobStatus updateJobStatus = Mockito.mock(UpdateJobStatus.class);

    private JobExecutionWatchdog subject() {
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        return new JobExecutionWatchdog(eventPublisher, 360, redisTemplate, updateJobStatus);
    }

    private TerraformJob job(String jobId) {
        TerraformJob job = new TerraformJob();
        job.setJobId(jobId);
        return job;
    }

    @Test
    void doesNothingWhenNeverMarkedBusy() {
        JobExecutionWatchdog watchdog = subject();

        watchdog.checkForWedgedJob();

        assertTrue(publishedEvents.isEmpty());
    }

    @Test
    void doesNothingWhileWithinTheAllowedDuration() {
        JobExecutionWatchdog watchdog = subject();

        watchdog.markBusy(job("1"));
        watchdog.checkForWedgedJob();

        assertTrue(publishedEvents.isEmpty());
    }

    @Test
    void marksThePodUnhealthyOnceBusyLongerThanTheConfiguredCeiling() throws InterruptedException {
        JobExecutionWatchdog watchdog = new JobExecutionWatchdog(eventPublisher, 0, redisTemplate, updateJobStatus);

        watchdog.markBusy(job("1"));
        Thread.sleep(5);
        watchdog.checkForWedgedJob();

        assertEquals(1, publishedEvents.size());
        assertEquals(LivenessState.BROKEN, ((AvailabilityChangeEvent<?>) publishedEvents.get(0)).getState());
    }

    @Test
    void markFreeResetsTheWatchdogSoAFinishedJobIsNeverFlaggedAsWedged() throws InterruptedException {
        JobExecutionWatchdog watchdog = subject();

        watchdog.markBusy(job("1"));
        Thread.sleep(5);
        watchdog.markFree();
        watchdog.checkForWedgedJob();

        assertTrue(publishedEvents.isEmpty());
    }

    @Test
    void refreshesTheHeartbeatKeyWhileBusy() {
        JobExecutionWatchdog watchdog = subject();

        watchdog.markBusy(job("42"));
        watchdog.refreshHeartbeat();

        verify(valueOperations, times(1)).set("executor-job-heartbeat:42", "1", Duration.ofSeconds(45));
    }

    @Test
    void doesNotRefreshTheHeartbeatWhileFree() {
        JobExecutionWatchdog watchdog = subject();

        watchdog.refreshHeartbeat();

        verify(valueOperations, times(0)).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void aRedisFailureDuringHeartbeatRefreshIsLoggedAndSwallowed() {
        JobExecutionWatchdog watchdog = subject();
        watchdog.markBusy(job("42"));
        Mockito.doThrow(new DataAccessException("boom") {}).when(valueOperations)
                .set(anyString(), any(), any(Duration.class));

        watchdog.refreshHeartbeat();
    }

    @Test
    void deletesTheHeartbeatKeyImmediatelyOnMarkFree() {
        JobExecutionWatchdog watchdog = subject();

        watchdog.markBusy(job("42"));
        watchdog.markFree();

        verify(redisTemplate, times(1)).delete("executor-job-heartbeat:42");
    }

    @Test
    void failsTheInFlightJobOnGracefulShutdown() {
        JobExecutionWatchdog watchdog = subject();
        TerraformJob job = job("42");

        watchdog.markBusy(job);
        watchdog.onShutdown();

        verify(updateJobStatus, times(1)).setCompletedStatus(
                Mockito.eq(false), Mockito.eq(false), Mockito.eq(-1), Mockito.eq(job),
                anyString(), anyString(), Mockito.isNull(), anyString());
    }

    @Test
    void doesNothingOnShutdownWhenNoJobIsInFlight() {
        JobExecutionWatchdog watchdog = subject();

        watchdog.onShutdown();

        verify(updateJobStatus, times(0)).setCompletedStatus(
                Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyInt(), any(),
                anyString(), anyString(), any(), anyString());
    }
}
