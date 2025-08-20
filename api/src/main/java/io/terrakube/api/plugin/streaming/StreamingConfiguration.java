package io.terrakube.api.plugin.streaming;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Slf4j
@Configuration
public class StreamingConfiguration {

    @Bean
    SSLSocketFactory sslSocketFactory(StreamingProperties props) throws Exception {
        if (!props.isSsl()) {
            return SSLContext.getDefault().getSocketFactory();
        }
        KeyStore trustStore = KeyStore.getInstance("jks");
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(props.getTruststorePath());
            trustStore.load(inputStream, props.getTruststorePassword().toCharArray());
        } catch (Exception e) {
            log.error(e.getMessage());
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("PKIX");
        trustManagerFactory.init(trustStore);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, new SecureRandom());
        return sslContext.getSocketFactory();
    }

    @Bean
    JedisConnectionFactory jedisConnectionFactory(StreamingProperties props, SSLSocketFactory sslSocketFactory) {
        JedisClientConfiguration.JedisClientConfigurationBuilder clientConfigBuilder = JedisClientConfiguration.builder();

        if (props.isSsl()) {
            log.info("Setup Redis connection using SSL");
            clientConfigBuilder.useSsl().sslSocketFactory(sslSocketFactory);
        } else {
            log.info("Using default Redis connection");
        }

        JedisClientConfiguration clientConfig = clientConfigBuilder.build();
        if (isCluster(props)) {
            RedisClusterConfiguration clusterConfig = getClusterConfiguration(props);
            return new JedisConnectionFactory(clusterConfig, clientConfig);
        }

        if (isSentinel(props)) {
            RedisSentinelConfiguration sentinelConfig = getSentinelConfiguration(props);
            return new JedisConnectionFactory(sentinelConfig, clientConfig);
        }

        RedisStandaloneConfiguration standaloneConfig = getStandaloneConfiguration(props);
        return new JedisConnectionFactory(standaloneConfig, clientConfig);
    }

    private boolean isSentinel(StreamingProperties properties) {
        return properties.getSentinel() != null &&
                !CollectionUtils.isEmpty(properties.getSentinel().getNodes()) &&
                StringUtils.hasText(properties.getSentinel().getMaster());
    }

    private boolean isCluster(StreamingProperties properties) {
        return properties.getCluster() != null &&
                !CollectionUtils.isEmpty(properties.getCluster().getNodes());
    }

    private RedisStandaloneConfiguration getStandaloneConfiguration(StreamingProperties properties) {
        if (StringUtils.hasText(properties.getUsername())) {
            log.info("Setting redis connection username");
        } else {
            log.info("Redis connection is not using username parameter");
        }

        String hostname = properties.getHostname();
        int port = properties.getPort();
        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration(
                hostname, port);

        if (StringUtils.hasText(properties.getUsername())) {
            redisStandaloneConfiguration.setUsername(properties.getUsername());
        }

        if (StringUtils.hasText(properties.getPassword())) {
            redisStandaloneConfiguration.setPassword(properties.getPassword());
        }

        log.info("Redis User: {}, Hostname: {}, Port: {}, Ssl: {}",
                StringUtils.hasText(properties.getUsername()) ? properties.getUsername(): "NULL username",
                hostname,
                port,
                properties.isSsl());

        return redisStandaloneConfiguration;
    }

    public RedisSentinelConfiguration getSentinelConfiguration(StreamingProperties properties) {
        StreamingProperties.Sentinel sentinel = properties.getSentinel();
        RedisSentinelConfiguration config = new RedisSentinelConfiguration();
        config.master(sentinel.getMaster());
        if (StringUtils.hasText(sentinel.getUsername())) {
            config.setUsername(sentinel.getUsername());
        }

        if (StringUtils.hasText(sentinel.getPassword())) {
            config.setPassword(sentinel.getPassword());
        }

        if (sentinel.getNodes() != null) {
            sentinel.getNodes().forEach(node -> {
                String[] parts = node.split(":");
                if (parts.length == 2) {
                    config.sentinel(parts[0], Integer.parseInt(parts[1]));
                }
            });
        }

        log.info("Redis Sentinel -> Master: {}, Nodes: {}, User: {}",
                sentinel.getMaster(),
                sentinel.getNodes(),
                sentinel.getUsername() != null
                        ? sentinel.getUsername()
                        : "NULL username");

        return config;
    }

    public RedisClusterConfiguration getClusterConfiguration(StreamingProperties properties) {
        StreamingProperties.Cluster cluster = properties.getCluster();
        RedisClusterConfiguration config = new RedisClusterConfiguration(cluster.getNodes());
        if (StringUtils.hasText(properties.getUsername())) {
            config.setUsername(properties.getUsername());
        }

        if (StringUtils.hasText(properties.getPassword())) {
            config.setPassword(properties.getPassword());
        }

        if (cluster.getMaxRedirects() != null) {
            config.setMaxRedirects(cluster.getMaxRedirects());
        }

        log.info("Redis Cluster -> Nodes: {}, MaxRedirects: {}, User: {}",
                cluster.getNodes(),
                cluster.getMaxRedirects(),
                properties.getUsername() != null
                        ? properties.getUsername()
                        : "NULL username");

        return config;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(JedisConnectionFactory jedisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(jedisConnectionFactory);
        return template;
    }
}
