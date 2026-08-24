package io.terrakube.api.plugin.vcs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class WorkspaceFanoutExecutorConfig {

    // Bounds how many workspaces on one shared webhook are processed concurrently. Without this,
    // a repo with 35+ workspaces either processes them fully serially (slow, and each workspace
    // still opens its own DB connections and outbound VCS/executor calls one at a time) or, if
    // naively parallelized without a cap, could open dozens of concurrent connections at once. A
    // full queue rejects rather than blocks the caller (RepoWebhookService.processClaimedDelivery):
    // a rejected workspace task is caught and logged like any other per-workspace failure - see
    // the comment there.
    @Bean("workspaceFanoutExecutor")
    public ThreadPoolTaskExecutor workspaceFanoutExecutor(
            @Value("${io.terrakube.webhook.workspace-fanout.concurrency:4}") int concurrency,
            @Value("${io.terrakube.webhook.workspace-fanout.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("workspace-fanout-");
        executor.initialize();
        return executor;
    }
}
