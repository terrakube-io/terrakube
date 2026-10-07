package io.terrakube.api;

import io.terrakube.api.repository.RunCascadeNodeRepository;
import io.terrakube.api.repository.RunCascadeRepository;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeNode;
import io.terrakube.api.rs.cascade.RunCascadeNodeStatus;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code RunCascadeNodeRepository.lockByCascade_IdAndWorkspace_IdForUpdate} really blocks
 * a second transaction until the first ends, against a real database rather than a mock - same
 * approach as {@link WorkspaceGraphLockConcurrencyTest} for the organization-row lock. Without
 * this lock, two parents of the same {@code ANY}/{@code ALL} join calling
 * {@code RunCascadeCoordinatorService.shouldDispatch} at nearly the same time could both read the
 * destination node as {@code PENDING} before either commits {@code RUNNING}, and both dispatch it.
 */
class RunCascadeNodeLockConcurrencyTest extends ServerApplicationTests {

    private static final String WORKSPACE_SOURCE = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String WORKSPACE_DESTINATION = "c20633b2-82cc-4105-9806-16e23ad0e1df";

    @Autowired
    private RunCascadeRepository runCascadeRepository;

    @Autowired
    private RunCascadeNodeRepository runCascadeNodeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Integer originJobId;
    private UUID cascadeId;

    @AfterEach
    void cleanup() {
        if (cascadeId != null) {
            runCascadeRepository.deleteById(cascadeId);
        }
        if (originJobId != null) {
            jobRepository.findById(originJobId).ifPresent(job -> {
                job.setDeleted(true);
                jobRepository.save(job);
            });
        }
    }

    @Test
    void lockByCascadeAndWorkspaceBlocksASecondTransactionUntilTheFirstEnds() throws Exception {
        Workspace source = workspaceRepository.findById(UUID.fromString(WORKSPACE_SOURCE)).orElseThrow();
        Workspace destination = workspaceRepository.findById(UUID.fromString(WORKSPACE_DESTINATION)).orElseThrow();

        Date now = new Date();
        Job originJob = new Job();
        originJob.setWorkspace(source);
        originJob.setOrganization(source.getOrganization());
        originJob.setStatus(JobStatus.completed);
        originJob.setCreatedBy("test");
        originJob.setUpdatedBy("test");
        originJob.setCreatedDate(now);
        originJob.setUpdatedDate(now);
        originJob = jobRepository.save(originJob);
        originJobId = originJob.getId();

        RunCascade cascade = new RunCascade();
        cascade.setId(UUID.randomUUID());
        cascade.setOriginJob(originJob);
        cascade.setOrganization(source.getOrganization());
        cascade = runCascadeRepository.save(cascade);
        cascadeId = cascade.getId();

        RunCascadeNode node = new RunCascadeNode();
        node.setId(UUID.randomUUID());
        node.setCascade(cascade);
        node.setWorkspace(destination);
        node.setDepth(1);
        runCascadeNodeRepository.save(node);

        UUID finalCascadeId = cascade.getId();
        UUID destinationId = destination.getId();

        CountDownLatch firstHasTheLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong firstReleasedAtNanos = new AtomicLong();
        AtomicLong secondAcquiredAtNanos = new AtomicLong();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
                runCascadeNodeRepository.lockByCascade_IdAndWorkspace_IdForUpdate(finalCascadeId, destinationId);
                firstHasTheLock.countDown();
                awaitUninterruptibly(releaseFirst);
                firstReleasedAtNanos.set(System.nanoTime());
                // Transaction ends (and the row lock releases) when this callback returns.
            }));

            assertThat(firstHasTheLock.await(5, TimeUnit.SECONDS))
                    .as("first transaction acquired the lock")
                    .isTrue();

            Future<?> second = pool.submit(() -> tx.executeWithoutResult(status ->
                    runCascadeNodeRepository.lockByCascade_IdAndWorkspace_IdForUpdate(finalCascadeId, destinationId)
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

        RunCascadeNode reloaded = runCascadeNodeRepository.findById(node.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RunCascadeNodeStatus.PENDING);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
