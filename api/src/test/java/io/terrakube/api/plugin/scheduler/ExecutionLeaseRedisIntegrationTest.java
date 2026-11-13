package io.terrakube.api.plugin.scheduler;

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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExecutionLease} against a real Redis with the production {@code redisTemplate}, because the
 * ownership checks are Lua scripts comparing the stored token with the one the serializers send. If
 * that round trip broke, every renew, claim and release would silently answer "not the owner".
 */
@Testcontainers
class ExecutionLeaseRedisIntegrationTest {

    private static final Duration LOCK_TTL = Duration.ofMillis(400);
    private static final Duration CLAIM_TTL = Duration.ofMinutes(5);

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

    private static Optional<ExecutionLease> acquire(int jobId) {
        return ExecutionLease.acquire(redisTemplate, jobId, LOCK_TTL, CLAIM_TTL);
    }

    private static void waitForExpiry() throws InterruptedException {
        Thread.sleep(LOCK_TTL.toMillis() + 250);
    }

    @Test
    void onlyOneWorkerHoldsTheLockAndReleaseFreesIt() {
        ExecutionLease first = acquire(100).orElseThrow();

        assertThat(acquire(100)).isEmpty();
        first.release();

        assertThat(redisTemplate.hasKey("job-execution-lock:100")).isFalse();
        acquire(100).orElseThrow().release();
    }

    @Test
    void aWorkerWhoseLockExpiredCannotReleaseItsSuccessorsLock() throws Exception {
        ExecutionLease slow = acquire(101).orElseThrow();
        waitForExpiry();
        ExecutionLease successor = acquire(101).orElseThrow();

        slow.release();

        assertThat(redisTemplate.hasKey("job-execution-lock:101")).isTrue();
        assertThat(acquire(101)).isEmpty();
        successor.release();
        assertThat(redisTemplate.hasKey("job-execution-lock:101")).isFalse();
    }

    @Test
    void renewingKeepsTheLockPastItsTtl() throws Exception {
        ExecutionLease lease = acquire(102).orElseThrow();

        for (int i = 0; i < 8; i++) {
            Thread.sleep(150);
            assertThat(lease.renew()).isTrue();
        }

        assertThat(lease.isLost()).isFalse();
        assertThat(acquire(102)).isEmpty();
        lease.release();
    }

    @Test
    void renewingAfterTheLockWasTakenOverFailsAndMarksTheLeaseLost() throws Exception {
        ExecutionLease slow = acquire(103).orElseThrow();
        waitForExpiry();
        ExecutionLease successor = acquire(103).orElseThrow();

        assertThat(slow.renew()).isFalse();

        assertThat(slow.isLost()).isTrue();
        assertThat(successor.renew()).isTrue();
        // and it did not extend the successor's lock on the way: the successor still owns it
        assertThat(slow.renew()).isFalse();
        successor.release();
    }

    @Test
    void aStepIsClaimedOnceEvenWhileTheLockIsHeld() {
        ExecutionLease lease = acquire(104).orElseThrow();

        assertThat(lease.claimDispatch("step-a")).isTrue();
        assertThat(lease.claimDispatch("step-a")).isFalse();
        assertThat(lease.claimDispatch("step-b")).isTrue();
        lease.release();
    }

    @Test
    void aClaimStillBlocksASecondWorkerAfterTheFirstWorkersLockExpired() throws Exception {
        ExecutionLease first = acquire(105).orElseThrow();
        assertThat(first.claimDispatch("step-a")).isTrue();
        waitForExpiry();
        ExecutionLease second = acquire(105).orElseThrow();

        assertThat(second.claimDispatch("step-a")).isFalse();
        // the first worker resuming after its lock expired cannot claim anything either
        assertThat(first.claimDispatch("step-b")).isFalse();
        second.release();
    }

    @Test
    void aWorkerThatLostTheLockCannotClaimEvenBeforeItNoticedByRenewing() throws Exception {
        ExecutionLease slow = acquire(106).orElseThrow();
        waitForExpiry();
        acquire(106).orElseThrow();

        assertThat(slow.claimDispatch("step-a")).isFalse();
        assertThat(redisTemplate.hasKey("job-dispatch-claim:106:step-a")).isFalse();
    }

    @Test
    void aReleasedClaimLetsTheStepBeDispatchedAgainButOnlyByItsOwner() {
        ExecutionLease lease = acquire(107).orElseThrow();
        assertThat(lease.claimDispatch("step-a")).isTrue();

        redisTemplate.opsForValue().set("job-dispatch-claim:107:step-b", "someone-else");
        lease.releaseDispatchClaim("step-b");
        assertThat(redisTemplate.hasKey("job-dispatch-claim:107:step-b")).isTrue();

        lease.releaseDispatchClaim("step-a");
        assertThat(lease.claimDispatch("step-a")).isTrue();
        lease.release();
    }

    @Test
    void ofManyWorkersRacingForTheLockExactlyOneWins() throws Exception {
        int workers = 16;
        List<Optional<ExecutionLease>> results = race(workers, () -> acquire(108));

        assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
        results.stream().flatMap(Optional::stream).forEach(ExecutionLease::release);
    }

    @Test
    void ofManyWorkersWhoseLocksExpiredAndOneCurrentWorkerOnlyOneSubmitsTheStep() throws Exception {
        // Workers whose locks expired one after another all resume and reach the submission point
        // together with the current holder; exactly one may submit.
        int workers = 4;
        AtomicInteger submitted = new AtomicInteger();
        List<ExecutionLease> leases = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            ExecutionLease lease = acquire(109).orElseThrow();
            leases.add(lease);
            waitForExpiry();
        }
        ExecutionLease newest = acquire(109).orElseThrow();
        leases.add(newest);

        ExecutorService pool = Executors.newFixedThreadPool(leases.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (ExecutionLease lease : leases) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (lease.claimDispatch("step-a")) {
                        submitted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(submitted.get()).isEqualTo(1);
        newest.release();
    }

    private static <T> List<T> race(int workers, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
