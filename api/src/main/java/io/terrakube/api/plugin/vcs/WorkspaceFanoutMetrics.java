package io.terrakube.api.plugin.vcs;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

// Explicit constructor (not @AllArgsConstructor): there are two ThreadPoolTaskExecutor beans in
// this context (this one and repoWebhookDispatchExecutor), so the injection point needs a
// @Qualifier on the constructor parameter itself - relying on Lombok to copy that annotation onto
// a generated constructor isn't something to depend on.
@Component
public class WorkspaceFanoutMetrics {

    private final ThreadPoolTaskExecutor workspaceFanoutExecutor;
    private final MeterRegistry meterRegistry;

    public WorkspaceFanoutMetrics(@Qualifier("workspaceFanoutExecutor") ThreadPoolTaskExecutor workspaceFanoutExecutor,
            MeterRegistry meterRegistry) {
        this.workspaceFanoutExecutor = workspaceFanoutExecutor;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerGauge() {
        Gauge.builder("webhook.workspace.fanout.queue.depth", workspaceFanoutExecutor,
                        executor -> executor.getThreadPoolExecutor().getQueue().size())
                .description("Number of workspace-fanout tasks queued waiting for a free thread")
                .register(meterRegistry);
    }
}
