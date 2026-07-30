package io.terrakube.api.plugin.scheduler.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.impl.matchers.KeyMatcher;
import org.quartz.listeners.JobListenerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the actual coalescing claim behind the Quartz-job redesign against
 * a real, JDBC-backed (cluster-safe) Quartz scheduler and a real Postgres
 * instance — the exact conditions production runs under. This is the direct
 * replacement for the deleted RepoWebhookConcurrencyTests (which exercised
 * the now-abandoned advisory-lock fix directly against
 * RepoWebhookService#getOrCreateRepoWebhook).
 *
 * <p>{@link RepoWebhookSyncSchedulerTest} already verifies the coalescing
 * contract against a mocked Scheduler; what that can't prove is that Quartz
 * itself actually serializes concurrent scheduleJob() calls under the same
 * key against a real JDBC job store rather than racing. N concurrent
 * scheduleSync() calls for the exact same repository URL must: (1) never
 * propagate an exception to the caller, and (2) result in exactly one
 * RepoWebhookSyncJob execution.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RepoWebhookSyncCoalescingIntegrationTest {

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("terrakube")
            .withUsername("terrakube")
            .withPassword("terrakube");

    @DynamicPropertySource
    static void registerPostgreSQLProperties(DynamicPropertyRegistry registry) {
        registry.add("io.terrakube.api.plugin.datasource.type", () -> "POSTGRESQL");
        registry.add("io.terrakube.api.plugin.datasource.hostname", postgreSQLContainer::getHost);
        registry.add("io.terrakube.api.plugin.datasource.databasePort", () -> postgreSQLContainer.getMappedPort(5432).toString());
        registry.add("io.terrakube.api.plugin.datasource.databaseName", postgreSQLContainer::getDatabaseName);
        registry.add("io.terrakube.api.plugin.datasource.databaseUser", postgreSQLContainer::getUsername);
        registry.add("io.terrakube.api.plugin.datasource.databasePassword", postgreSQLContainer::getPassword);
        // Quartz's SchedulerFactoryBean registers its DataSource in a JVM-wide static
        // registry (org.quartz.utils.DBConnectionManager) keyed by scheduler instance
        // name, which otherwise defaults to the "schedulerFactoryBean" bean name shared
        // with the default (H2) test ApplicationContext. Without a distinct name here,
        // this context's registration clobbers that shared entry; when this context's
        // Postgres container is torn down afterwards, the default context's still-running
        // Quartz background threads are left pointing at a dead connection pool for the
        // rest of the test suite.
        registry.add("io.terrakube.api.plugin.scheduler.instanceName", () -> "repoWebhookSyncCoalescingIT");
    }

    @Autowired
    private RepoWebhookSyncScheduler repoWebhookSyncScheduler;

    @Autowired
    private Scheduler scheduler;

    @Test
    void concurrentSchedulesForSameUrlCoalesceIntoOneExecution() throws Exception {
        String repositoryUrl = "https://github.com/owner/coalesce-test-" + System.nanoTime();
        JobKey jobKey = RepoWebhookSyncScheduler.jobKeyFor(repositoryUrl);
        int concurrency = 8;

        AtomicInteger executionCount = new AtomicInteger();
        CountDownLatch executed = new CountDownLatch(1);
        String listenerName = "coalescing-test-counter-" + jobKey.getName();
        scheduler.getListenerManager().addJobListener(new JobListenerSupport() {
            @Override
            public String getName() {
                return listenerName;
            }

            @Override
            public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
                executionCount.incrementAndGet();
                executed.countDown();
            }
        }, KeyMatcher.keyEquals(jobKey));

        try {
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            CountDownLatch ready = new CountDownLatch(concurrency);
            CountDownLatch start = new CountDownLatch(1);
            List<AtomicReference<Throwable>> failures = IntStream.range(0, concurrency)
                    .mapToObj(i -> new AtomicReference<Throwable>())
                    .collect(Collectors.toList());

            for (int i = 0; i < concurrency; i++) {
                AtomicReference<Throwable> failure = failures.get(i);
                int workspaceIndex = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        repoWebhookSyncScheduler.scheduleSync(repositoryUrl, "ws-" + workspaceIndex);
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(failures)
                    .as("no concurrent scheduleSync call should propagate an exception — ObjectAlreadyExistsException must be coalesced internally")
                    .allSatisfy(ref -> assertThat(ref.get()).isNull());

            assertThat(executed.await(10, TimeUnit.SECONDS)).isTrue();
            // Give any incorrect duplicate execution a moment to show up before asserting.
            Thread.sleep(500);
            assertThat(executionCount.get())
                    .as("exactly one RepoWebhookSyncJob execution should occur for %s despite %d concurrent scheduleSync calls",
                            repositoryUrl, concurrency)
                    .isEqualTo(1);
        } finally {
            scheduler.getListenerManager().removeJobListener(listenerName);
        }
    }
}
