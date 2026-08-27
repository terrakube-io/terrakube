package io.terrakube.api.plugin.vcs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class RepoWebhookDispatchExecutorConfig {

    // Bounded, dedicated pool for the immediate-dispatch path (RepoWebhookService.acceptV2Webhook
    // calling RepoWebhookDispatchService.dispatchAsync right after the HTTP response to GitHub
    // would otherwise be blocked on). Without a named executor here, Spring's @Async falls back to
    // the unbounded SimpleAsyncTaskExecutor (a new thread per call, no queue, no cap) - a burst of
    // webhook deliveries (e.g. a force-push touching many shared-webhook repos at once) could spawn
    // unbounded concurrent fan-outs all competing for the same DB connection pool and the same
    // GitHub API rate limit. A full queue rejects rather than blocks the caller: the row stays
    // PENDING and RepoWebhookDeliveryPollerJob picks it up on its next tick, so a rejection is safe,
    // not lossy.
    @Bean("repoWebhookDispatchExecutor")
    public ThreadPoolTaskExecutor repoWebhookDispatchExecutor(
            @Value("${io.terrakube.webhook.dispatch.executor.corePoolSize:10}") int corePoolSize,
            @Value("${io.terrakube.webhook.dispatch.executor.maxPoolSize:20}") int maxPoolSize,
            @Value("${io.terrakube.webhook.dispatch.executor.queueCapacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("webhook-dispatch-");
        executor.initialize();
        return executor;
    }
}
