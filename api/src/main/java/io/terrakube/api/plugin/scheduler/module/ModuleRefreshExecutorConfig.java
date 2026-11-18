package io.terrakube.api.plugin.scheduler.module;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ModuleRefreshExecutorConfig {

    // Bounded, dedicated pool for module refreshes, so the ls-remote calls run off the scheduler thread and
    // a replica never takes more modules than it can work on. ModuleRefreshPoller claims at most the
    // free slots (threads plus queue) each poll. A full queue rejects rather than blocks: the poller hands
    // the rejected module's lease back and it is claimed again on the next tick, so a rejection is safe.
    @Bean("moduleRefreshExecutor")
    public ThreadPoolTaskExecutor moduleRefreshExecutor(ModuleRefreshProperties moduleRefreshProperties) {
        ModuleRefreshProperties.Executor settings = moduleRefreshProperties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.getPoolSize());
        executor.setMaxPoolSize(settings.getPoolSize());
        executor.setQueueCapacity(settings.getQueueCapacity());
        executor.setThreadNamePrefix("module-refresh-");
        executor.initialize();
        return executor;
    }
}
