package io.terrakube.executor.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class SpringAsyncAutoConfiguration {

    @Bean(name = "threadPoolTaskExecutor")
    @Primary
    public Executor threadPoolTaskExecutor() {
        // ExecutorJobImpl.createJob() is the only @Async consumer of this pool, and it must stay
        // single-threaded with no local queue: OnlineModeServiceImpl's ExecutorCapacityGate is
        // what actually enforces "one job per pod" (a busy gate returns 503 before a job is ever
        // submitted here). This pool's zero queue capacity is defense-in-depth for the narrow
        // race between the previous job's ExecutorCapacityGate.release() and this pool's thread
        // actually becoming free again - a submission landing in that window is rejected
        // (TaskRejectedException) rather than silently queued, and OnlineModeServiceImpl maps
        // that rejection to a 503. Do not raise corePoolSize/maxPoolSize/queueCapacity.
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("terrakube-executor-job-");
        return executor;
    }
}
