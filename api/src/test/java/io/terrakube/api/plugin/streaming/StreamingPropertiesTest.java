package io.terrakube.api.plugin.streaming;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfig.class)
                    .withPropertyValues(
                            "io.terrakube.api.redis.hostname=localhost",
                            "io.terrakube.api.redis.port=6379",
                            "io.terrakube.api.redis.username=redisUser",
                            "io.terrakube.api.redis.password=redisPass",
                            "io.terrakube.api.redis.ssl=true",
                            "io.terrakube.api.redis.sentinel.master=mymaster",
                            "io.terrakube.api.redis.sentinel.nodes=127.0.0.1:26379,127.0.0.2:26379",
                            "io.terrakube.api.redis.sentinel.username=sentinelUser",
                            "io.terrakube.api.redis.sentinel.password=sentinelPass",
                            "io.terrakube.api.redis.cluster.nodes=127.0.0.1:7000,127.0.0.2:7001",
                            "io.terrakube.api.redis.cluster.max-redirects=5"
                    );

    @EnableConfigurationProperties(StreamingProperties.class)
    static class TestConfig {}

    @Test
    void propertiesAreBoundCorrectly() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(StreamingProperties.class);
            StreamingProperties props = context.getBean(StreamingProperties.class);

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