package io.terrakube.api;

import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code OrganizationRepository.lockForUpdate} really blocks a second transaction until
 * the first ends, against a real database rather than a mock. Exercises the repository method
 * directly rather than two concurrent edge creations - forcing that exact race is itself flaky,
 * and locking is a property of the row, not of what validateAcyclic does with it afterwards.
 */
class WorkspaceGraphLockConcurrencyTest extends ServerApplicationTests {

    private static final String WORKSPACE_SOURCE = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void lockForUpdateBlocksASecondTransactionUntilTheFirstEnds() throws Exception {
        Workspace source = workspaceRepository.findById(UUID.fromString(WORKSPACE_SOURCE)).orElseThrow();
        UUID organizationId = source.getOrganization().getId();

        CountDownLatch firstHasTheLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong firstReleasedAtNanos = new AtomicLong();
        AtomicLong secondAcquiredAtNanos = new AtomicLong();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
                organizationRepository.lockForUpdate(organizationId);
                firstHasTheLock.countDown();
                awaitUninterruptibly(releaseFirst);
                firstReleasedAtNanos.set(System.nanoTime());
                // Transaction ends (and the row lock releases) when this callback returns.
            }));

            assertThat(firstHasTheLock.await(5, TimeUnit.SECONDS))
                    .as("first transaction acquired the lock")
                    .isTrue();

            Future<?> second = pool.submit(() -> tx.executeWithoutResult(status ->
                    organizationRepository.lockForUpdate(organizationId)
                            .ifPresent(ignored -> secondAcquiredAtNanos.set(System.nanoTime()))));

            // Still blocked here: nothing has released the lock yet.
            Thread.sleep(300);
            assertThat(secondAcquiredAtNanos.get())
                    .as("second transaction must not have acquired the lock yet")
                    .isZero();

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertThat(secondAcquiredAtNanos.get())
                    .as("second transaction must only acquire the lock after the first released it")
                    .isGreaterThan(firstReleasedAtNanos.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
