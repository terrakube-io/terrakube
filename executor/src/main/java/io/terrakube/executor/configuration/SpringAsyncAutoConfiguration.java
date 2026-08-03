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
        // single-threaded: a busy pod signals REFUSING_TRAFFIC to leave the k8s Service's endpoint
        // pool, but that only takes effect once kube-proxy propagates it, so a second request can
        // still land here in the meantime. Capping the pool at one thread queues that request
        // behind the running job instead of running both concurrently - that's what actually
        // enforces "one job per pod", not the readiness signal by itself. Do not raise this.
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("terrakube-executor-job-");
        return executor;
    }
}
