package io.terrakube.api.plugin.context;

import io.terrakube.api.plugin.streaming.StreamingConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs {@link ContextWriteLock} against a real Redis with the production {@code redisTemplate}
 * (default serializers), so the SET NX PX acquire and the compare-and-delete Lua release are
 * exercised end to end. If the token round-trip through the serializers broke, release would
 * silently return 0 and the lock would stay held until its TTL.
 */
@Testcontainers
class ContextWriteLockRedisIntegrationTest {

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static JedisConnectionFactory connectionFactory;
    private static RedisTemplate<String, Object> redisTemplate;

    @BeforeAll
    static void connect() {
        connectionFactory = new JedisConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                JedisClientConfiguration.builder().usePooling().build());
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StreamingConfiguration().redisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void releaseFreesTheLockForTheNextWriter() {
        ContextWriteLock lock = new ContextWriteLock(redisTemplate, 200);

        String token = lock.acquire(1);
        assertThat(redisTemplate.hasKey(ContextWriteLock.KEY_PREFIX + 1)).isTrue();
        assertThat(redisTemplate.getExpire(ContextWriteLock.KEY_PREFIX + 1)).isPositive();
        assertThatThrownBy(() -> lock.acquire(1)).isInstanceOf(ContextUnavailableException.class);

        lock.release(1, token);

        assertThat(redisTemplate.hasKey(ContextWriteLock.KEY_PREFIX + 1)).isFalse();
        lock.release(1, lock.acquire(1));
    }

    @Test
    void releaseWithAStaleTokenKeepsTheCurrentHoldersLock() {
        ContextWriteLock lock = new ContextWriteLock(redisTemplate, 200);

        String token = lock.acquire(2);
        lock.release(2, "stale-token");

        assertThat(redisTemplate.hasKey(ContextWriteLock.KEY_PREFIX + 2)).isTrue();
        lock.release(2, token);
        assertThat(redisTemplate.hasKey(ContextWriteLock.KEY_PREFIX + 2)).isFalse();
    }
}
