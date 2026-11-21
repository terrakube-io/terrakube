package io.terrakube.executor.service.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.client.TerrakubeClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Only one of the two log services exists at runtime, chosen by {@code io.executor.log-via-api}. Both carry
 * the "processLogs" qualifier, so the same injection point resolves in either mode.
 */
class ProcessLogsQualifierTest {

    @Configuration
    static class Dependencies {
        @Bean
        ExecutorLogsProperties executorLogsProperties() {
            return new ExecutorLogsProperties();
        }

        @Bean
        @SuppressWarnings("rawtypes")
        RedisTemplate redisTemplate() {
            return mock(RedisTemplate.class);
        }

        @Bean
        TerrakubeClient terrakubeClient() {
            return mock(TerrakubeClient.class);
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    static class Consumer {
        final ProcessLogs processLogs;

        Consumer(@Qualifier("processLogs") ProcessLogs processLogs) {
            this.processLogs = processLogs;
        }
    }

    @Configuration
    @Import({Dependencies.class, LogsServiceRedis.class, LogsServiceApi.class})
    static class Logs {
        @Bean
        Consumer consumer(@Qualifier("processLogs") ProcessLogs processLogs) {
            return new Consumer(processLogs);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Logs.class);

    @Test
    void theRedisServiceIsInjectedByDefault() {
        runner.run(context -> assertThat(context.getBean(Consumer.class).processLogs)
                .isInstanceOf(LogsServiceRedis.class));
    }

    @Test
    void theApiServiceIsInjectedWhenLogsGoThroughTheApi() {
        runner.withPropertyValues("io.executor.log-via-api=true")
                .run(context -> assertThat(context.getBean(Consumer.class).processLogs)
                        .isInstanceOf(LogsServiceApi.class));
    }
}
