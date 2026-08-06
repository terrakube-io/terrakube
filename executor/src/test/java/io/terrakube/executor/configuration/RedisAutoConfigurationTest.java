package io.terrakube.executor.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

class RedisAutoConfigurationTest {

    @Test
    void jedisConnectionFactoryUsesPoolingWithSsl() throws Exception {
        RedisProperties properties = new RedisProperties();
        properties.setHostname("localhost");
        properties.setPort(6379);
        properties.setSsl(true);

        JedisConnectionFactory connectionFactory = new RedisAutoConfiguration()
                .jedisConnectionFactory(properties, SSLContext.getDefault().getSocketFactory());

        assertThat(connectionFactory.getClientConfiguration().isUsePooling()).isTrue();
        assertThat(connectionFactory.getClientConfiguration().isUseSsl()).isTrue();
    }
}
