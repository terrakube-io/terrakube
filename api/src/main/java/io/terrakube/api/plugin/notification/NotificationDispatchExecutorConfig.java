package io.terrakube.api.plugin.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class NotificationDispatchExecutorConfig {

    // Bounded, dedicated pool for the immediate-dispatch path (JobNotificationHook's
    // POSTCOMMIT calling NotificationDispatchService.dispatchAsync). Without a named executor
    // here, Spring's @Async falls back to the unbounded SimpleAsyncTaskExecutor (a new thread
    // per call, no queue, no cap) - a burst of job status changes could spawn unbounded
    // concurrent deliveries all competing for the same DB connection pool. A full queue rejects
    // rather than blocks the caller (JobNotificationHook's POSTCOMMIT): the row stays PENDING
    // and the outbox poller picks it up on its next tick, so a rejection is safe, not lossy.
    @Bean("notificationDispatchExecutor")
    public ThreadPoolTaskExecutor notificationDispatchExecutor(
            @Value("${io.terrakube.notification.dispatch.executor.corePoolSize:10}") int corePoolSize,
            @Value("${io.terrakube.notification.dispatch.executor.maxPoolSize:20}") int maxPoolSize,
            @Value("${io.terrakube.notification.dispatch.executor.queueCapacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("notification-dispatch-");
        executor.initialize();
        return executor;
    }
}
