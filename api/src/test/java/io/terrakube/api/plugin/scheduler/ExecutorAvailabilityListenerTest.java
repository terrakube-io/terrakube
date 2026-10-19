package io.terrakube.api.plugin.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ExecutorAvailabilityListenerTest {

    private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
    private final ScheduleJobService scheduleJobService = mock(ScheduleJobService.class);

    private ExecutorAvailabilityListener subject() {
        return new ExecutorAvailabilityListener(container, scheduleJobService, new SimpleMeterRegistry());
    }

    @Test
    void wakesTheQueueHeadWhenAnExecutorReportsCapacity() {
        subject().onMessage(null, null);

        verify(scheduleJobService).wakeQueueHead();
    }

    @Test
    void subscribesToTheExecutorAvailableChannelOnStartup() {
        subject().subscribe();

        verify(container).addMessageListener(any(), eq(new ChannelTopic(ExecutorAvailabilityListener.CHANNEL)));
    }
}
