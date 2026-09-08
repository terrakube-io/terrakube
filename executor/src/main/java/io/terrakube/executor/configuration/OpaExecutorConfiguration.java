package io.terrakube.executor.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class OpaExecutorConfiguration {

    @Bean(name = "opaEvaluationExecutor")
    public ThreadPoolTaskExecutor opaEvaluationExecutor(
            @Value("${io.terrakube.executor.opa.concurrency.core:8}") int corePoolSize,
            @Value("${io.terrakube.executor.opa.concurrency.max:16}") int maxPoolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setThreadNamePrefix("opa-eval-");
        executor.initialize();
        return executor;
    }
}
