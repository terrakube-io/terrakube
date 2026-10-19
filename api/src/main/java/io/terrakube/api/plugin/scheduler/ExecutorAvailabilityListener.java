package io.terrakube.api.plugin.scheduler;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

// Reacts to the executor module's "capacity available" doorbell by immediately waking the
// current front of the FIFO dispatch queue, instead of waiting for its next 30s Quartz retry.
@Component
@Slf4j
public class ExecutorAvailabilityListener implements MessageListener {

    // Duplicated as a literal in executor's ExecutorJobImpl - separate Spring Boot apps, no
    // shared module for this constant.
    public static final String CHANNEL = "terrakube:executor-available";

    private final RedisMessageListenerContainer redisMessageListenerContainer;
    private final ScheduleJobService scheduleJobService;
    // Initialized to "now" at startup, not zero, so the gauge below doesn't report a huge bogus
    // age before the very first real signal arrives after a fresh deploy.
    private final AtomicLong lastSignalEpochMillis = new AtomicLong(System.currentTimeMillis());

    public ExecutorAvailabilityListener(RedisMessageListenerContainer redisMessageListenerContainer,
            ScheduleJobService scheduleJobService, MeterRegistry meterRegistry) {
        this.redisMessageListenerContainer = redisMessageListenerContainer;
        this.scheduleJobService = scheduleJobService;
        Gauge.builder("executor.availability.age.seconds", lastSignalEpochMillis,
                        signal -> (System.currentTimeMillis() - signal.get()) / 1000.0)
                .description("Seconds since the executor module last signalled it has capacity available")
                .register(meterRegistry);
    }

    @PostConstruct
    public void subscribe() {
        redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        lastSignalEpochMillis.set(System.currentTimeMillis());
        scheduleJobService.wakeQueueHead();
    }
}
