package io.terrakube.api.plugin.scheduler;

import io.terrakube.api.repository.JobRepository;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.SchedulerException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

// Reacts to the executor module's "capacity available" doorbell by immediately waking the
// current front of the FIFO dispatch queue, instead of waiting for its next 30s Quartz retry.
@AllArgsConstructor
@Component
@Slf4j
public class ExecutorAvailabilityListener implements MessageListener {

    // Duplicated as a literal in executor's ExecutorJobImpl - separate Spring Boot apps, no
    // shared module for this constant.
    public static final String CHANNEL = "terrakube:executor-available";

    RedisMessageListenerContainer redisMessageListenerContainer;
    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;

    @PostConstruct
    public void subscribe() {
        redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Integer nextJobId = jobRepository.findNextDispatchableJobId();
            if (nextJobId != null) {
                scheduleJobService.createJobContextNow(jobRepository.getReferenceById(nextJobId));
            }
        } catch (DataAccessException | SchedulerException e) {
            log.warn("Could not react to executor-available signal: {}", e.getMessage());
        }
    }
}
