package io.terrakube.api.plugin.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import javax.net.ssl.SSLContext;

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
}
