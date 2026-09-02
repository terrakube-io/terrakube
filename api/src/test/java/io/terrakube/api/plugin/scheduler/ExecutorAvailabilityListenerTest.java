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
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;

class ExecutorAvailabilityListenerTest {

    private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);

    private ExecutorAvailabilityListener subject() {
        return new ExecutorAvailabilityListener(container, jobRepository, scheduleJobService,
                new SimpleMeterRegistry());
    }

    @Test
    void wakesTheOldestWaitingJobWhenAnExecutorReportsCapacity() throws Exception {
        Job nextJob = new Job();
        nextJob.setId(42);
        when(jobRepository.findNextDispatchableJobId()).thenReturn(42);
        when(jobRepository.getReferenceById(42)).thenReturn(nextJob);

        subject().onMessage(null, null);

        verify(scheduleJobService).createJobContextNow(nextJob);
    }

    @Test
    void doesNothingWhenNoJobIsWaiting() throws Exception {
        when(jobRepository.findNextDispatchableJobId()).thenReturn(null);

        subject().onMessage(null, null);

        verify(scheduleJobService, never()).createJobContextNow(any());
    }

    @Test
    void subscribesToTheExecutorAvailableChannelOnStartup() {
        subject().subscribe();

        verify(container).addMessageListener(any(), eq(new ChannelTopic(ExecutorAvailabilityListener.CHANNEL)));
    }
}
