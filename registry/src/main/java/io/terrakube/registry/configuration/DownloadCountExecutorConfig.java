package io.terrakube.registry.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class DownloadCountExecutorConfig {

    // Bounded, dedicated pool for the download-count bookkeeping call
    // (ModuleServiceImpl.updateModuleDownloadCount), so it never blocks the Terraform-facing
    // metadata response (ModuleWebServiceImpl.getModuleVersionPath). Deliberately small: this is
    // one lightweight, best-effort API call per module download, not a fan-out. A full queue
    // rejects rather than blocks the caller - a dropped update only affects a non-critical
    // download counter, never the client-visible response.
    @Bean("downloadCountExecutor")
    public ThreadPoolTaskExecutor downloadCountExecutor(
            @Value("${io.terrakube.registry.download-count.executor.corePoolSize:2}") int corePoolSize,
            @Value("${io.terrakube.registry.download-count.executor.maxPoolSize:5}") int maxPoolSize,
            @Value("${io.terrakube.registry.download-count.executor.queueCapacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("download-count-");
        executor.initialize();
        return executor;
    }
}
