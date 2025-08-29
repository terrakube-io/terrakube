package io.terrakube.executor.configuration;

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
public class RedisAutoConfiguration {

    @Bean
    SSLSocketFactory sslSocketFactory(RedisProperties properties) throws Exception {
        if (!properties.isSsl())
            return SSLContext.getDefault().getSocketFactory();

        KeyStore jksTrustStore = KeyStore.getInstance("jks");
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(properties.getTruststorePath());
            char[] truststorePassword = properties.getTruststorePassword().toCharArray();
            jksTrustStore.load(inputStream, truststorePassword);
        } catch (Exception e) {
            log.error(e.getMessage());
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }

        TrustManagerFactory managerFactory = TrustManagerFactory.getInstance("PKIX");
        managerFactory.init(jksTrustStore);
        TrustManager[] trustManagers = managerFactory.getTrustManagers();

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, new SecureRandom());
        return sslContext.getSocketFactory();
    }

    @Bean
    JedisConnectionFactory jedisConnectionFactory(RedisProperties redisProperties, SSLSocketFactory sslSocketFactory) {
        JedisClientConfiguration.JedisClientConfigurationBuilder clientConfigBuilder = JedisClientConfiguration.builder();

        if (redisProperties.isSsl()) {
            log.info("Redis connection with SSL configuration");
            clientConfigBuilder.useSsl().sslSocketFactory(sslSocketFactory);
        } else {
            log.info("Redis connection with default configuration");
        }

        JedisClientConfiguration clientConfig = clientConfigBuilder.build();

        if (isCluster(redisProperties)) {
            RedisClusterConfiguration clusterConfig = getClusterConfig(redisProperties);
            return new JedisConnectionFactory(clusterConfig, clientConfig);
        }

        if (isSentinel(redisProperties)) {
            RedisSentinelConfiguration sentinelConfig = getSentinelConf(redisProperties);
            return new JedisConnectionFactory(sentinelConfig, clientConfig);
        }

        RedisStandaloneConfiguration standaloneConfig = getStandaloneConfiguration(redisProperties);
        return new JedisConnectionFactory(standaloneConfig, clientConfig);
    }

    private boolean isSentinel(RedisProperties redisProperties) {
        return redisProperties.getSentinel() != null &&
                !CollectionUtils.isEmpty(redisProperties.getSentinel().getNodes()) &&
                StringUtils.hasText(redisProperties.getSentinel().getMaster());
    }

    private boolean isCluster(RedisProperties redisProperties) {
        return redisProperties.getCluster() != null &&
                !CollectionUtils.isEmpty(redisProperties.getCluster().getNodes());
    }

    private RedisStandaloneConfiguration getStandaloneConfiguration(RedisProperties redisProperties) {
        String hostname = redisProperties.getHostname();
        int port = redisProperties.getPort();
        String username = redisProperties.getUsername();
        String password = redisProperties.getPassword();
        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration(
                hostname, port);
        if (StringUtils.hasText(redisProperties.getPassword())) {
            redisStandaloneConfiguration.setPassword(password);
        }

        if (StringUtils.hasText(redisProperties.getUsername())) {
            redisStandaloneConfiguration.setUsername(username);
        }

        log.info("Redis User {}, Hostname {}, Port {}, SSL {}",
                (username != null && !username.isEmpty()) ? username: "NULL username",
                hostname,
                port,
                redisProperties.isSsl()
        );
        return redisStandaloneConfiguration;
    }

    public RedisSentinelConfiguration getSentinelConf(RedisProperties sentinelProperties) {
        RedisProperties.Sentinel sentinel = sentinelProperties.getSentinel();
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

        log.info("Redis Config Sentinel -> Master=> {}, Nodes=> {}, User=> {}",
                sentinel.getMaster(),
                sentinel.getNodes(),
                sentinel.getUsername() != null
                        ? sentinel.getUsername()
                        : "NULL USERNAME..."
        );

        return config;
    }

    public RedisClusterConfiguration getClusterConfig(RedisProperties redisProperties) {
        RedisProperties.Cluster cluster = redisProperties.getCluster();
        RedisClusterConfiguration config = new RedisClusterConfiguration(cluster.getNodes());

        if (cluster.getMaxRedirects() != null) {
            config.setMaxRedirects(cluster.getMaxRedirects());
        }

        if (StringUtils.hasText(redisProperties.getUsername())) {
            config.setUsername(redisProperties.getUsername());
        }

        if (StringUtils.hasText(redisProperties.getPassword())) {
            config.setPassword(redisProperties.getPassword());
        }

        log.info("Redis Config Cluster -> Node: {}, MaxRedirect: {}, User: {}",
                cluster.getNodes(),
                cluster.getMaxRedirects(),
                redisProperties.getUsername() != null
                        ? redisProperties.getUsername()
                        : "NULL USERNAME"
        );

        return config;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(JedisConnectionFactory jedisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(jedisConnectionFactory);
        return template;
    }
}
