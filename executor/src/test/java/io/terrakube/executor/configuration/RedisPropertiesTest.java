package io.terrakube.executor.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RedisPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfig.class)
                    .withPropertyValues(
                            "io.terrakube.executor.redis.hostname=localhost",
                            "io.terrakube.executor.redis.port=6379",
                            "io.terrakube.executor.redis.username=redisUser",
                            "io.terrakube.executor.redis.password=redisPass",
                            "io.terrakube.executor.redis.ssl=true",
                            "io.terrakube.executor.redis.sentinel.master=mymaster",
                            "io.terrakube.executor.redis.sentinel.nodes=127.0.0.1:26379,127.0.0.2:26379",
                            "io.terrakube.executor.redis.sentinel.username=sentinelUser",
                            "io.terrakube.executor.redis.sentinel.password=sentinelPass",
                            "io.terrakube.executor.redis.cluster.nodes=127.0.0.1:7000,127.0.0.2:7001",
                            "io.terrakube.executor.redis.cluster.max-redirects=5"
                    );

    @EnableConfigurationProperties(RedisProperties.class)
    static class TestConfig {}

    @Test
    void propertiesAreBoundCorrectly() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RedisProperties.class);
            RedisProperties props = context.getBean(RedisProperties.class);

            assertThat(props.getHostname()).isEqualTo("localhost");
            assertThat(props.getPort()).isEqualTo(6379);
            assertThat(props.getUsername()).isEqualTo("redisUser");
            assertThat(props.getPassword()).isEqualTo("redisPass");
            assertThat(props.isSsl()).isTrue();

            assertThat(props.getSentinel()).isNotNull();
            assertThat(props.getSentinel().getMaster()).isEqualTo("mymaster");
            assertThat(props.getSentinel().getNodes()).containsExactly("127.0.0.1:26379", "127.0.0.2:26379");
            assertThat(props.getSentinel().getUsername()).isEqualTo("sentinelUser");
            assertThat(props.getSentinel().getPassword()).isEqualTo("sentinelPass");

            assertThat(props.getCluster()).isNotNull();
            assertThat(props.getCluster().getNodes()).containsExactly("127.0.0.1:7000", "127.0.0.2:7001");
            assertThat(props.getCluster().getMaxRedirects()).isEqualTo(5);
        });
    }

}