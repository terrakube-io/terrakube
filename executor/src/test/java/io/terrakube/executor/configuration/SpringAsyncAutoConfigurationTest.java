package io.terrakube.executor.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAsyncAutoConfigurationTest {

    @Test
    void threadPoolTaskExecutorIsSingleThreadedWithNoLocalQueue() {
        ThreadPoolTaskExecutor executor =
                (ThreadPoolTaskExecutor) new SpringAsyncAutoConfiguration().threadPoolTaskExecutor();
        executor.initialize();

        // createJob() relies on this pool never running two jobs on the same pod at once, and on
        // never silently queuing a second one behind the first - see the comment on
        // SpringAsyncAutoConfiguration.threadPoolTaskExecutor(). If either of these ever changes,
        // that guarantee silently breaks.
        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(0);
    }

}
