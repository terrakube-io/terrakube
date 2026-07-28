package io.terrakube.api.plugin.vcs;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
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

import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.workspace.Workspace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the real race in {@link RepoWebhookService#getOrCreateRepoWebhook}
 * against an actual Postgres instance: multiple concurrent requests for
 * workspaces on the same never-before-seen repository URL must all resolve
 * to a single shared {@link RepoWebhook} row, never a unique constraint
 * violation. This exercises the pg_advisory_xact_lock fix end to end —
 * RepoWebhookServiceTest's mocked tests only verify the lock call happens,
 * not that it actually serializes concurrent transactions.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RepoWebhookConcurrencyTests {

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
    }

    @Autowired
    private RepoWebhookService repoWebhookService;

    @Autowired
    private RepoWebhookRepository repoWebhookRepository;

    @Test
    void concurrentRequestsForSameRepositoryResolveToOneSharedRow() throws InterruptedException {
        String repositoryUrl = "https://github.com/owner/concurrency-test-" + System.nanoTime();
        int concurrency = 8;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<AtomicReference<Throwable>> failures = IntStream.range(0, concurrency)
                .mapToObj(i -> new AtomicReference<Throwable>())
                .collect(Collectors.toList());

        for (int i = 0; i < concurrency; i++) {
            AtomicReference<Throwable> failure = failures.get(i);
            pool.submit(() -> {
                Workspace workspace = new Workspace();
                workspace.setSource(repositoryUrl);
                ready.countDown();
                try {
                    start.await();
                    repoWebhookService.getOrCreateRepoWebhook(workspace);
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        String normalizedUrl = RepoUrlNormalizer.normalize(repositoryUrl);
        assertThat(failures)
                .as("no concurrent getOrCreateRepoWebhook call should throw")
                .allSatisfy(ref -> assertThat(ref.get()).isNull());
        assertThat(repoWebhookRepository.findByRepositoryUrl(normalizedUrl)).isPresent();
        long rowCount = repoWebhookRepository.findAll().stream()
                .filter(rw -> rw.getRepositoryUrl().equals(normalizedUrl))
                .count();
        assertThat(rowCount)
                .as("exactly one RepoWebhook row should exist for %s despite %d concurrent creators",
                        normalizedUrl, concurrency)
                .isEqualTo(1);
    }
}
