package io.terrakube.api.plugin.scheduler;

import io.terrakube.api.plugin.scheduler.logs.DeleteJobLogStreamJob;
import io.terrakube.api.plugin.scheduler.reconciliation.JobReconciliationSweep;
import io.terrakube.api.plugin.streaming.StreamingConfiguration;
import io.terrakube.api.repository.JobRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies Redis Cluster slot-enforcement compatibility against a real Redis 7 node running in
 * cluster mode with the production {@link StreamingConfiguration#redisTemplate} configuration.
 */
@Testcontainers
class RedisClusterCompatibilityIntegrationTest {

    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration CLAIM_TTL = Duration.ofMinutes(5);

    @Container
    private static final GenericContainer<?> redisCluster = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand(
                    "redis-server",
                    "--cluster-enabled", "yes",
                    "--cluster-config-file", "/tmp/nodes.conf",
                    "--cluster-node-timeout", "5000",
                    "--appendonly", "no");

    private static JedisConnectionFactory connectionFactory;
    private static RedisTemplate<String, Object> redisTemplate;

    @BeforeAll
    static void connectCluster() throws Exception {
        String host = redisCluster.getHost();
        String announceIp = InetAddress.getByName(host).getHostAddress();
        int mappedPort = redisCluster.getMappedPort(6379);

        assertExecOk(redisCluster.execInContainer("redis-cli", "CONFIG", "SET", "cluster-announce-ip", announceIp));
        assertExecOk(redisCluster.execInContainer("redis-cli", "CONFIG", "SET", "cluster-announce-port", String.valueOf(mappedPort)));
        assertExecOk(redisCluster.execInContainer("redis-cli", "CLUSTER", "ADDSLOTSRANGE", "0", "16383"));

        waitUntilClusterReady();

        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(List.of(announceIp + ":" + mappedPort));
        connectionFactory = new JedisConnectionFactory(clusterConfig);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        redisTemplate = new StreamingConfiguration().redisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectCluster() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private static void assertExecOk(org.testcontainers.containers.Container.ExecResult result) {
        assertThat(result.getExitCode())
                .as("redis-cli exitCode stdout=%s stderr=%s", result.getStdout(), result.getStderr())
                .isZero();
        assertThat(result.getStdout())
                .as("redis-cli stdout=%s stderr=%s", result.getStdout(), result.getStderr())
                .doesNotContain("ERR");
    }

    private static void waitUntilClusterReady() throws Exception {
        String lastInfo = "";
        for (int attempt = 0; attempt < 60; attempt++) {
            var res = redisCluster.execInContainer("redis-cli", "CLUSTER", "INFO");
            lastInfo = res.getStdout();
            if (res.getExitCode() == 0 && lastInfo.contains("cluster_state:ok")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Redis Cluster node did not enter cluster_state:ok in time: " + lastInfo);
    }

    @Test
    void executionLeaseClaimDispatchAndReleaseSucceedOnRedisCluster() {
        // Test multiple job IDs and step IDs so keys would hit different slots without {jobId} hash tags.
        for (int jobId : List.of(129, 4033, 78537)) {
            ExecutionLease lease = ExecutionLease.acquire(redisTemplate, jobId, LOCK_TTL, CLAIM_TTL).orElseThrow();

            assertThat(lease.renew()).isTrue();
            assertThat(lease.claimDispatch("step-alpha-uuid")).isTrue();
            assertThat(lease.claimDispatch("step-alpha-uuid")).isFalse();
            assertThat(lease.claimDispatch("step-beta-uuid")).isTrue();

            lease.releaseDispatchClaim("step-alpha-uuid");
            assertThat(lease.claimDispatch("step-alpha-uuid")).isTrue();

            lease.release();
            assertThat(redisTemplate.hasKey(ExecutionLease.lockKey(jobId))).isFalse();
        }
    }

    @Test
    void deleteJobLogStreamJobDeletesStreamsWithoutCrossSlotOnRedisCluster() {
        // 78537: both streams present; 78538: only -context present; 78539: neither present.
        redisTemplate.opsForStream().add("78537", Map.of("output", "log-line"));
        redisTemplate.opsForStream().add("78537-context", Map.of("output", "{\"phase\":\"plan\"}"));
        redisTemplate.opsForStream().add("78538-context", Map.of("output", "{\"phase\":\"apply\"}"));

        JobRepository jobRepository = mock(JobRepository.class);
        when(jobRepository.findTerminalJobIdsUpdatedBetween(any(), any())).thenReturn(List.of(78537, 78538, 78539));

        DeleteJobLogStreamJob cleanup = new DeleteJobLogStreamJob(jobRepository, redisTemplate, 30, 24);
        cleanup.execute(null);

        assertThat(redisTemplate.hasKey("78537")).isFalse();
        assertThat(redisTemplate.hasKey("78537-context")).isFalse();
        assertThat(redisTemplate.hasKey("78538-context")).isFalse();
    }

    @Test
    void reconciliationSweepParsesClusterPrefixedServerInfo() {
        Properties info = redisTemplate.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info("server"));

        assertThat(info).isNotNull();
        assertThat(info.stringPropertyNames())
                .anyMatch(key -> key.endsWith(".uptime_in_seconds"));
        // Freshly started test container has uptime < 300s, verifying parsing works without NPE/NumberFormatException.
        assertThat(JobReconciliationSweep.isRedisWithinWarmupPeriod(info)).isTrue();
    }
}
