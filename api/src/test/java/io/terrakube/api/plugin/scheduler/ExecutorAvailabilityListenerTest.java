package io.terrakube.api.plugin.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.plugin.scheduler.dispatchretry.DispatchRetryProperties;
import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationProperties;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;

class ExecutorAvailabilityListenerTest {

    private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);
    private final ReconciliationProperties reconciliationProperties = new ReconciliationProperties();
    // Deferral guard off by default here so the pre-existing guarded/unguarded tests below keep
    // exercising isJobNextInDispatchOrderExecutable/findNextDispatchableJobId unchanged; the
    // dispatch-retry-aware ladder itself is covered by the dedicated test further down.
    private final DispatchRetryProperties dispatchRetryProperties = dispatchRetryPropertiesWithDeferralDisabled();

    private static DispatchRetryProperties dispatchRetryPropertiesWithDeferralDisabled() {
        DispatchRetryProperties properties = new DispatchRetryProperties();
        properties.setAdmissionDeferralEnabled(false);
        return properties;
    }

    private ExecutorAvailabilityListener subject() {
        return new ExecutorAvailabilityListener(container, jobRepository, scheduleJobService,
                new SimpleMeterRegistry(), reconciliationProperties, dispatchRetryProperties);
    }

    @Test
    void wakesTheOldestWaitingJobWhenAnExecutorReportsCapacity() throws Exception {
        Job nextJob = new Job();
        nextJob.setId(42);
        when(jobRepository.findNextDispatchableExecutableJobId()).thenReturn(42);
        when(jobRepository.getReferenceById(42)).thenReturn(nextJob);

        subject().onMessage(null, null);

        verify(scheduleJobService).createJobContextNow(nextJob);
    }

    @Test
    void doesNothingWhenNoJobIsWaiting() throws Exception {
        when(jobRepository.findNextDispatchableExecutableJobId()).thenReturn(null);

        subject().onMessage(null, null);

        verify(scheduleJobService, never()).createJobContextNow(any());
    }

    @Test
    void usesTheUnguardedQueryWhenTheAdmissionGuardIsDisabled() throws Exception {
        reconciliationProperties.setAdmissionGuardEnabled(false);
        when(jobRepository.findNextDispatchableJobId()).thenReturn(null);

        subject().onMessage(null, null);

        verify(scheduleJobService, never()).createJobContextNow(any());
    }

    @Test
    void usesTheDeferralAwareQueryWhenDispatchRetryAdmissionDeferralIsEnabled() throws Exception {
        dispatchRetryProperties.setAdmissionDeferralEnabled(true);
        Job nextJob = new Job();
        nextJob.setId(7);
        when(jobRepository.findNextDispatchableExecutableNotDeferredJobId()).thenReturn(7);
        when(jobRepository.getReferenceById(7)).thenReturn(nextJob);

        subject().onMessage(null, null);

        verify(scheduleJobService).createJobContextNow(nextJob);
        verify(jobRepository, never()).findNextDispatchableExecutableJobId();
    }

    @Test
    void subscribesToTheExecutorAvailableChannelOnStartup() {
        subject().subscribe();

        verify(container).addMessageListener(any(), eq(new ChannelTopic(ExecutorAvailabilityListener.CHANNEL)));
    }
}
