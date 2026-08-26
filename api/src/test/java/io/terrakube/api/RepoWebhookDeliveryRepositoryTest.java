package io.terrakube.api;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.repository.RepoWebhookDeliveryRepository;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.RepoWebhookDelivery;
import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import static org.assertj.core.api.Assertions.assertThat;

// @Modifying bulk-update queries (claimForDelivery, recordDeliveryResult, the stuck-row sweep)
// require an active transaction to execute at all - unlike save()/findById(), they don't get one
// implicitly from the repository proxy. This also gives each test method rollback-based isolation.
@Transactional
class RepoWebhookDeliveryRepositoryTest extends ServerApplicationTests {

    @Autowired
    RepoWebhookDeliveryRepository repoWebhookDeliveryRepository;

    @Autowired
    RepoWebhookRepository repoWebhookRepository;

    @PersistenceContext
    EntityManager entityManager;

    // GenericAuditFields.createdDate is @CreationTimestamp (Hibernate sets it on insert,
    // overriding any value passed to setCreatedDate) and updatable = false - a raw UPDATE is the
    // only way to backdate a row for a retention-cutoff test.
    private void backdate(UUID id, Date date) {
        entityManager.createNativeQuery("UPDATE repo_webhook_delivery SET created_date = ?1 WHERE id = ?2")
                .setParameter(1, date)
                .setParameter(2, id.toString())
                .executeUpdate();
        entityManager.clear();
    }

    private RepoWebhook savedRepoWebhook(String repositoryUrl) {
        RepoWebhook repoWebhook = new RepoWebhook();
        repoWebhook.setRepositoryUrl(repositoryUrl);
        repoWebhook.setWebhookSecret(UUID.randomUUID().toString());
        return repoWebhookRepository.saveAndFlush(repoWebhook);
    }

    private RepoWebhookDelivery newDelivery(RepoWebhook repoWebhook) {
        RepoWebhookDelivery delivery = new RepoWebhookDelivery();
        delivery.setId(UUID.randomUUID());
        delivery.setRepoWebhook(repoWebhook);
        delivery.setPayload("{}");
        delivery.setHeaders("{}");
        delivery.setStatus(RepoWebhookDeliveryStatus.PENDING);
        return delivery;
    }

    @Test
    void findDueForDispatchReturnsPendingRowsWhoseNextAttemptHasArrivedOrIsUnset() {
        RepoWebhook repoWebhook = savedRepoWebhook("https://github.com/org/repo-" + UUID.randomUUID() + ".git");

        RepoWebhookDelivery due = newDelivery(repoWebhook);
        due.setAttemptCount(1);
        due.setLastAttemptAt(new Date(System.currentTimeMillis() - 120_000));
        due.setNextAttemptAt(new Date(System.currentTimeMillis() - 60_000));
        repoWebhookDeliveryRepository.saveAndFlush(due);

        RepoWebhookDelivery notYetDue = newDelivery(repoWebhook);
        notYetDue.setAttemptCount(1);
        notYetDue.setNextAttemptAt(new Date(System.currentTimeMillis() + 300_000));
        repoWebhookDeliveryRepository.saveAndFlush(notYetDue);

        List<RepoWebhookDelivery> result = repoWebhookDeliveryRepository
                .findDueForDispatch(RepoWebhookDeliveryStatus.PENDING, new Date(), PageRequest.of(0, 200));

        assertThat(result).extracting(RepoWebhookDelivery::getId).contains(due.getId())
                .doesNotContain(notYetDue.getId());
    }

    @Test
    void claimForDeliveryOnlySucceedsOnceForTheSameRow() {
        RepoWebhook repoWebhook = savedRepoWebhook("https://github.com/org/repo-" + UUID.randomUUID() + ".git");
        RepoWebhookDelivery delivery = repoWebhookDeliveryRepository.saveAndFlush(newDelivery(repoWebhook));

        Date now = new Date();
        int firstClaim = repoWebhookDeliveryRepository.claimForDelivery(delivery.getId(), RepoWebhookDeliveryStatus.PENDING,
                RepoWebhookDeliveryStatus.PROCESSING, now);
        int secondClaim = repoWebhookDeliveryRepository.claimForDelivery(delivery.getId(), RepoWebhookDeliveryStatus.PENDING,
                RepoWebhookDeliveryStatus.PROCESSING, now);

        assertThat(firstClaim).isEqualTo(1);
        assertThat(secondClaim).isEqualTo(0);

        RepoWebhookDelivery reloaded = repoWebhookDeliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RepoWebhookDeliveryStatus.PROCESSING);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void recordDeliveryResultIsANoOpWhenTheRowWasReclaimedInTheMeantime() {
        RepoWebhook repoWebhook = savedRepoWebhook("https://github.com/org/repo-" + UUID.randomUUID() + ".git");
        RepoWebhookDelivery delivery = repoWebhookDeliveryRepository.saveAndFlush(newDelivery(repoWebhook));

        Date firstClaimAt = new Date();
        repoWebhookDeliveryRepository.claimForDelivery(delivery.getId(), RepoWebhookDeliveryStatus.PENDING,
                RepoWebhookDeliveryStatus.PROCESSING, firstClaimAt);

        repoWebhookDeliveryRepository.reclaimStuckProcessingRows(RepoWebhookDeliveryStatus.PROCESSING,
                RepoWebhookDeliveryStatus.PENDING, new Date(System.currentTimeMillis() + 1), 3, new Date());
        Date secondClaimAt = new Date(firstClaimAt.getTime() + 1000);
        repoWebhookDeliveryRepository.claimForDelivery(delivery.getId(), RepoWebhookDeliveryStatus.PENDING,
                RepoWebhookDeliveryStatus.PROCESSING, secondClaimAt);

        int updated = repoWebhookDeliveryRepository.recordDeliveryResult(delivery.getId(), RepoWebhookDeliveryStatus.PROCESSING,
                firstClaimAt, RepoWebhookDeliveryStatus.PROCESSED, null, null, new Date());

        assertThat(updated).isZero();
        RepoWebhookDelivery reloaded = repoWebhookDeliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RepoWebhookDeliveryStatus.PROCESSING);
    }

    @Test
    void stuckRowSweepReclaimsBelowMaxAttemptsAndFailsAtMaxAttempts() {
        RepoWebhook repoWebhook = savedRepoWebhook("https://github.com/org/repo-" + UUID.randomUUID() + ".git");
        Date staleAttemptAt = new Date(System.currentTimeMillis() - 600_000);

        RepoWebhookDelivery belowMax = newDelivery(repoWebhook);
        belowMax.setStatus(RepoWebhookDeliveryStatus.PROCESSING);
        belowMax.setAttemptCount(1);
        belowMax.setLastAttemptAt(staleAttemptAt);
        repoWebhookDeliveryRepository.saveAndFlush(belowMax);

        RepoWebhookDelivery atMax = newDelivery(repoWebhook);
        atMax.setStatus(RepoWebhookDeliveryStatus.PROCESSING);
        atMax.setAttemptCount(3);
        atMax.setLastAttemptAt(staleAttemptAt);
        repoWebhookDeliveryRepository.saveAndFlush(atMax);

        Date cutoff = new Date(System.currentTimeMillis() - 300_000);
        repoWebhookDeliveryRepository.reclaimStuckProcessingRows(RepoWebhookDeliveryStatus.PROCESSING,
                RepoWebhookDeliveryStatus.PENDING, cutoff, 3, new Date());
        repoWebhookDeliveryRepository.failStuckProcessingRowsAtMaxAttempts(RepoWebhookDeliveryStatus.PROCESSING,
                RepoWebhookDeliveryStatus.FAILED, cutoff, 3, "stuck", new Date());

        assertThat(repoWebhookDeliveryRepository.findById(belowMax.getId()).orElseThrow().getStatus())
                .isEqualTo(RepoWebhookDeliveryStatus.PENDING);
        assertThat(repoWebhookDeliveryRepository.findById(atMax.getId()).orElseThrow().getStatus())
                .isEqualTo(RepoWebhookDeliveryStatus.FAILED);
    }

    @Test
    void deleteTerminalRowsCreatedBeforeOnlyRemovesOldTerminalRows() {
        RepoWebhook repoWebhook = savedRepoWebhook("https://github.com/org/repo-" + UUID.randomUUID() + ".git");
        Date cutoff = new Date();
        Date old = new Date(cutoff.getTime() - 1000);
        Date recent = new Date(cutoff.getTime() + 1000);

        RepoWebhookDelivery oldProcessed = newDelivery(repoWebhook);
        oldProcessed.setStatus(RepoWebhookDeliveryStatus.PROCESSED);
        repoWebhookDeliveryRepository.saveAndFlush(oldProcessed);
        backdate(oldProcessed.getId(), old);

        RepoWebhookDelivery recentProcessed = newDelivery(repoWebhook);
        recentProcessed.setStatus(RepoWebhookDeliveryStatus.PROCESSED);
        repoWebhookDeliveryRepository.saveAndFlush(recentProcessed);
        backdate(recentProcessed.getId(), recent);

        RepoWebhookDelivery oldPending = newDelivery(repoWebhook);
        repoWebhookDeliveryRepository.saveAndFlush(oldPending);
        backdate(oldPending.getId(), old);

        repoWebhookDeliveryRepository.deleteTerminalRowsCreatedBefore(
                List.of(RepoWebhookDeliveryStatus.PROCESSED, RepoWebhookDeliveryStatus.FAILED), cutoff);

        assertThat(repoWebhookDeliveryRepository.findById(oldProcessed.getId())).isEmpty();
        assertThat(repoWebhookDeliveryRepository.findById(recentProcessed.getId())).isPresent();
        assertThat(repoWebhookDeliveryRepository.findById(oldPending.getId())).isPresent();
    }
}
