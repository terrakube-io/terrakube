package io.terrakube.executor.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAsyncAutoConfigurationTest {

    @Test
    void threadPoolTaskExecutorIsSingleThreaded() {
        ThreadPoolTaskExecutor executor =
                (ThreadPoolTaskExecutor) new SpringAsyncAutoConfiguration().threadPoolTaskExecutor();

        // createJob() relies on this pool never running two jobs on the same pod at once - see
        // the comment on SpringAsyncAutoConfiguration.threadPoolTaskExecutor(). If this ever
        // changes, that guarantee silently breaks.
        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
    }

}
