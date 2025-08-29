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

        if (isCluster(redisProperties))
            return new JedisConnectionFactory(getClusterConfig(redisProperties), clientConfig);

        if (isSentinel(redisProperties))
            return new JedisConnectionFactory(getSentinelConf(redisProperties), clientConfig);

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

    private RedisStandaloneConfiguration getStandaloneConfiguration(RedisProperties properties) {
        String hostname = properties.getHostname();
        int port = properties.getPort();
        String username = properties.getUsername();
        String password = properties.getPassword();
        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration(hostname, port);

        if (StringUtils.hasText(properties.getPassword()))
            redisStandaloneConfiguration.setPassword(password);

        if (StringUtils.hasText(properties.getUsername()))
            redisStandaloneConfiguration.setUsername(username);

        log.info("Redis User {}, Hostname {}, Port {}, SSL {}",
                (username != null && !username.isEmpty()) ? username: "NULL username",
                hostname,
                port,
                properties.isSsl()
        );
        return redisStandaloneConfiguration;
    }

    public RedisSentinelConfiguration getSentinelConf(RedisProperties sentinelProperties) {
        RedisProperties.Sentinel newSentinel = sentinelProperties.getSentinel();
        RedisSentinelConfiguration newConfiguration = new RedisSentinelConfiguration();
        newConfiguration.master(newSentinel.getMaster());
        if (StringUtils.hasText(newSentinel.getUsername()))
            newConfiguration.setUsername(newSentinel.getUsername());

        if (StringUtils.hasText(newSentinel.getPassword()))
            newConfiguration.setPassword(newSentinel.getPassword());

        if (newSentinel.getNodes() != null) {
            newSentinel.getNodes().forEach(node -> {
                String[] parts = node.split(":");
                if (parts.length == 2) newConfiguration.sentinel(parts[0], Integer.parseInt(parts[1]));
            });
        }

        log.info("Redis Config Sentinel -> Master-> {}, Nodes-> {}, User-> {}",
                newSentinel.getMaster(),
                newSentinel.getNodes(),
                newSentinel.getUsername() != null
                        ? newSentinel.getUsername()
                        : "NULL USERNAME..."
        );

        return newConfiguration;
    }

    public RedisClusterConfiguration getClusterConfig(RedisProperties redisProperties) {
        RedisProperties.Cluster newCluster = redisProperties.getCluster();
        RedisClusterConfiguration newConfig = new RedisClusterConfiguration(newCluster.getNodes());

        if (newCluster.getMaxRedirects() != null)
            newConfig.setMaxRedirects(newCluster.getMaxRedirects());

        if (StringUtils.hasText(redisProperties.getUsername()))
            newConfig.setUsername(redisProperties.getUsername());

        if (StringUtils.hasText(redisProperties.getPassword()))
            newConfig.setPassword(redisProperties.getPassword());

        log.info("Redis Config Cluster -> Node: {}, MaxRedirect: {}, User: {}", newCluster.getNodes(), newCluster.getMaxRedirects(), redisProperties.getUsername() != null ? redisProperties.getUsername() : "NULL USERNAME");

        return newConfig;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(JedisConnectionFactory jedisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(jedisConnectionFactory);
        return template;
    }
}
