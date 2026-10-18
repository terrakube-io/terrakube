package io.terrakube.api.plugin.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import javax.net.ssl.SSLContext;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

class StreamingConfigurationTest {

    @Test
    void jedisConnectionFactoryUsesPoolingWithSsl() throws Exception {
        StreamingProperties properties = new StreamingProperties();
        properties.setHostname("localhost");
        properties.setPort(6379);
        properties.setSsl(true);

        JedisConnectionFactory connectionFactory = new StreamingConfiguration()
                .jedisConnectionFactory(properties, SSLContext.getDefault().getSocketFactory());

        assertThat(connectionFactory.getClientConfiguration().isUsePooling()).isTrue();
        assertThat(connectionFactory.getClientConfiguration().isUseSsl()).isTrue();
    }

    @Test
    void jedisPoolIsSizedAndBoundedByDefault() {
        StreamingProperties properties = new StreamingProperties();
        properties.setHostname("localhost");
        properties.setPort(6379);

        JedisConnectionFactory connectionFactory = new StreamingConfiguration()
                .jedisConnectionFactory(properties, null);

        GenericObjectPoolConfig<?> pool = connectionFactory.getClientConfiguration().getPoolConfig().orElseThrow();
        assertThat(pool.getMaxTotal()).isEqualTo(64);
        assertThat(pool.getMaxIdle()).isEqualTo(64);
        assertThat(pool.getMaxWaitDuration()).isEqualTo(Duration.ofSeconds(5));
    }
}
