package io.terrakube.api;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.NotificationConfigurationRepository;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// @Modifying bulk-update queries (claimForDelivery, recordDeliveryResult, the stuck-row sweep,
// rearmFailedForRetry) require an active transaction to execute at all - unlike save()/findById(),
// they don't get one implicitly from the repository proxy. This also gives each test method
// rollback-based isolation.
@Transactional
class NotificationOutboxRepositoryTest extends ServerApplicationTests {

    @Autowired
    NotificationOutboxRepository notificationOutboxRepository;

    @Autowired
    NotificationConfigurationRepository notificationConfigurationRepository;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @PersistenceContext
    EntityManager entityManager;

    // GenericAuditFields.createdDate is @CreationTimestamp (Hibernate sets it on insert,
    // overriding any value passed to setCreatedDate) and updatable = false (a later entity-level
    // save never touches the column either) - a raw UPDATE is the only way to backdate a row for
    // a retention-cutoff test.
    private void backdate(UUID id, Date date) {
        entityManager.createNativeQuery("UPDATE notification_outbox SET created_date = ?1 WHERE id = ?2")
                .setParameter(1, date)
                .setParameter(2, id.toString())
                .executeUpdate();
        entityManager.clear();
    }

    private NotificationConfiguration savedConfiguration(Organization organization, String name) {
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setName(name);
        configuration.setOrganization(organization);
        configuration.setChannelType(NotificationChannelType.WEBHOOK);
        configuration.setDestinationUrl("https://example.com/hook");
        configuration.setActive(true);
        return notificationConfigurationRepository.saveAndFlush(configuration);
    }

    private Job savedJob(Organization organization, Workspace workspace) {
        Job job = new Job();
        job.setStatus(JobStatus.failed);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        jobRepository.saveAndFlush(job);
        return job;
    }

    @Test
    void findDueForDispatchReturnsPendingRowsWhoseNextAttemptHasArrivedOrIsUnset() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        NotificationConfiguration configuration = savedConfiguration(organization, "Outbox Test Config");

        NotificationOutbox due = new NotificationOutbox();
        due.setId(UUID.randomUUID());
        due.setJob(savedJob(organization, workspace));
        due.setConfiguration(configuration);
        due.setPayload("{}");
        due.setStatus(NotificationOutboxStatus.PENDING);
        due.setAttemptCount(1);
        due.setLastAttemptAt(new Date(System.currentTimeMillis() - 120_000));
        due.setNextAttemptAt(new Date(System.currentTimeMillis() - 60_000));
        notificationOutboxRepository.saveAndFlush(due);

        NotificationOutbox notYetDue = new NotificationOutbox();
        notYetDue.setId(UUID.randomUUID());
        notYetDue.setJob(savedJob(organization, workspace));
        notYetDue.setConfiguration(configuration);
        notYetDue.setPayload("{}");
        notYetDue.setStatus(NotificationOutboxStatus.PENDING);
        notYetDue.setAttemptCount(1);
        notYetDue.setNextAttemptAt(new Date(System.currentTimeMillis() + 300_000));
        notificationOutboxRepository.saveAndFlush(notYetDue);

        List<NotificationOutbox> result = notificationOutboxRepository
                .findDueForDispatch(NotificationOutboxStatus.PENDING, new Date(), PageRequest.of(0, 200));

        assertThat(result).extracting(NotificationOutbox::getId).contains(due.getId())
                .doesNotContain(notYetDue.getId());
    }

    @Test
    void claimForDeliveryOnlySucceedsOnceForTheSameRow() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        NotificationConfiguration configuration = savedConfiguration(organization, "Claim Test Config");

        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setId(UUID.randomUUID());
        outbox.setJob(savedJob(organization, workspace));
        outbox.setConfiguration(configuration);
        outbox.setPayload("{}");
        outbox.setStatus(NotificationOutboxStatus.PENDING);
        notificationOutboxRepository.saveAndFlush(outbox);

        Date now = new Date();
        int firstClaim = notificationOutboxRepository.claimForDelivery(outbox.getId(), NotificationOutboxStatus.PENDING,
                NotificationOutboxStatus.SENDING, now);
        int secondClaim = notificationOutboxRepository.claimForDelivery(outbox.getId(), NotificationOutboxStatus.PENDING,
                NotificationOutboxStatus.SENDING, now);

        assertThat(firstClaim).isEqualTo(1);
        assertThat(secondClaim).isEqualTo(0);

        NotificationOutbox reloaded = notificationOutboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationOutboxStatus.SENDING);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void recordDeliveryResultIsANoOpWhenTheRowWasReclaimedInTheMeantime() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        NotificationConfiguration configuration = savedConfiguration(organization, "Record Result Test Config");

        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setId(UUID.randomUUID());
        outbox.setJob(savedJob(organization, workspace));
        outbox.setConfiguration(configuration);
        outbox.setPayload("{}");
        outbox.setStatus(NotificationOutboxStatus.PENDING);
        notificationOutboxRepository.saveAndFlush(outbox);

        Date firstClaimAt = new Date();
        notificationOutboxRepository.claimForDelivery(outbox.getId(), NotificationOutboxStatus.PENDING,
                NotificationOutboxStatus.SENDING, firstClaimAt);

        // Simulate the stuck-row sweep reclaiming and a second caller re-claiming with a newer
        // lastAttemptAt while the first caller's delivery is still "in flight" from its point of
        // view.
        notificationOutboxRepository.reclaimStuckSendingRows(NotificationOutboxStatus.SENDING,
                NotificationOutboxStatus.PENDING, new Date(System.currentTimeMillis() + 1), 3, new Date());
        Date secondClaimAt = new Date(firstClaimAt.getTime() + 1000);
        notificationOutboxRepository.claimForDelivery(outbox.getId(), NotificationOutboxStatus.PENDING,
                NotificationOutboxStatus.SENDING, secondClaimAt);

        // The first (stale) caller's result, keyed on its original lastAttemptAt, must not apply.
        int updated = notificationOutboxRepository.recordDeliveryResult(outbox.getId(), NotificationOutboxStatus.SENDING,
                firstClaimAt, NotificationOutboxStatus.SENT, null, null, new Date());

        assertThat(updated).isZero();
        NotificationOutbox reloaded = notificationOutboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationOutboxStatus.SENDING);
    }

    @Test
    void stuckRowSweepReclaimsBelowMaxAttemptsAndFailsAtMaxAttempts() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        NotificationConfiguration configuration = savedConfiguration(organization, "Sweep Test Config");
        Date staleAttemptAt = new Date(System.currentTimeMillis() - 600_000);

        NotificationOutbox belowMax = new NotificationOutbox();
        belowMax.setId(UUID.randomUUID());
        belowMax.setJob(savedJob(organization, workspace));
        belowMax.setConfiguration(configuration);
        belowMax.setPayload("{}");
        belowMax.setStatus(NotificationOutboxStatus.SENDING);
        belowMax.setAttemptCount(1);
        belowMax.setLastAttemptAt(staleAttemptAt);
        notificationOutboxRepository.saveAndFlush(belowMax);

        NotificationOutbox atMax = new NotificationOutbox();
        atMax.setId(UUID.randomUUID());
        atMax.setJob(savedJob(organization, workspace));
        atMax.setConfiguration(configuration);
        atMax.setPayload("{}");
        atMax.setStatus(NotificationOutboxStatus.SENDING);
        atMax.setAttemptCount(3);
        atMax.setLastAttemptAt(staleAttemptAt);
        notificationOutboxRepository.saveAndFlush(atMax);

        Date cutoff = new Date(System.currentTimeMillis() - 300_000);
        notificationOutboxRepository.reclaimStuckSendingRows(NotificationOutboxStatus.SENDING,
                NotificationOutboxStatus.PENDING, cutoff, 3, new Date());
        notificationOutboxRepository.failStuckSendingRowsAtMaxAttempts(NotificationOutboxStatus.SENDING,
                NotificationOutboxStatus.FAILED, cutoff, 3, "stuck", new Date());

        assertThat(notificationOutboxRepository.findById(belowMax.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(notificationOutboxRepository.findById(atMax.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.FAILED);
    }

    @Test
    void rearmFailedForRetryOnlyAffectsFailedRows() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        NotificationConfiguration configuration = savedConfiguration(organization, "Rearm Test Config");

        NotificationOutbox failed = new NotificationOutbox();
        failed.setId(UUID.randomUUID());
        failed.setJob(savedJob(organization, workspace));
        failed.setConfiguration(configuration);
        failed.setPayload("{}");
        failed.setStatus(NotificationOutboxStatus.FAILED);
        failed.setAttemptCount(3);
        failed.setLastError("boom");
        notificationOutboxRepository.saveAndFlush(failed);

        NotificationOutbox sent = new NotificationOutbox();
        sent.setId(UUID.randomUUID());
        sent.setJob(savedJob(organization, workspace));
        sent.setConfiguration(configuration);
        sent.setPayload("{}");
        sent.setStatus(NotificationOutboxStatus.SENT);
        notificationOutboxRepository.saveAndFlush(sent);

        int failedRowsUpdated = notificationOutboxRepository.rearmFailedForRetry(failed.getId(),
                NotificationOutboxStatus.FAILED, NotificationOutboxStatus.PENDING, new Date());
        int sentRowsUpdated = notificationOutboxRepository.rearmFailedForRetry(sent.getId(),
                NotificationOutboxStatus.FAILED, NotificationOutboxStatus.PENDING, new Date());

        assertThat(failedRowsUpdated).isEqualTo(1);
        assertThat(sentRowsUpdated).isZero();
        NotificationOutbox reloaded = notificationOutboxRepository.findById(failed.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(reloaded.getAttemptCount()).isZero();
        assertThat(reloaded.getLastError()).isNull();
    }

    @Test
    void deleteTerminalRowsCreatedBeforeOnlyRemovesOldTerminalRows() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        NotificationConfiguration configuration = savedConfiguration(organization, "Retention Test Config");
        Date cutoff = new Date();
        Date old = new Date(cutoff.getTime() - 1000);
        Date recent = new Date(cutoff.getTime() + 1000);

        NotificationOutbox oldSent = new NotificationOutbox();
        oldSent.setId(UUID.randomUUID());
        oldSent.setJob(savedJob(organization, workspace));
        oldSent.setConfiguration(configuration);
        oldSent.setPayload("{}");
        oldSent.setStatus(NotificationOutboxStatus.SENT);
        notificationOutboxRepository.saveAndFlush(oldSent);
        backdate(oldSent.getId(), old);

        NotificationOutbox recentSent = new NotificationOutbox();
        recentSent.setId(UUID.randomUUID());
        recentSent.setJob(savedJob(organization, workspace));
        recentSent.setConfiguration(configuration);
        recentSent.setPayload("{}");
        recentSent.setStatus(NotificationOutboxStatus.SENT);
        notificationOutboxRepository.saveAndFlush(recentSent);
        backdate(recentSent.getId(), recent);

        NotificationOutbox oldPending = new NotificationOutbox();
        oldPending.setId(UUID.randomUUID());
        oldPending.setJob(savedJob(organization, workspace));
        oldPending.setConfiguration(configuration);
        oldPending.setPayload("{}");
        oldPending.setStatus(NotificationOutboxStatus.PENDING);
        notificationOutboxRepository.saveAndFlush(oldPending);
        backdate(oldPending.getId(), old);

        // Not asserting the exact deleted count: this suite shares a real Postgres testcontainer
        // across test classes, and sibling tests that don't roll back (e.g.
        // NotificationDispatchConcurrencyTest) can leave their own old terminal rows behind -
        // what matters here is the filter logic, checked precisely against these three rows by id.
        notificationOutboxRepository.deleteTerminalRowsCreatedBefore(
                List.of(NotificationOutboxStatus.SENT, NotificationOutboxStatus.FAILED), cutoff);

        assertThat(notificationOutboxRepository.findById(oldSent.getId())).isEmpty();
        assertThat(notificationOutboxRepository.findById(recentSent.getId())).isPresent();
        assertThat(notificationOutboxRepository.findById(oldPending.getId())).isPresent();
    }

    @Test
    void findsRecentDeliveriesForAWorkspaceNewestFirst() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();

        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setName("Delivery Visibility Config");
        configuration.setOrganization(organization);
        configuration.setChannelType(NotificationChannelType.SLACK);
        configuration.setDestinationUrl("https://hooks.slack.com/services/X");
        configuration.setActive(true);
        configuration = notificationConfigurationRepository.saveAndFlush(configuration);

        Job olderJob = new Job();
        olderJob.setStatus(JobStatus.completed);
        olderJob.setOrganization(organization);
        olderJob.setWorkspace(workspace);
        jobRepository.saveAndFlush(olderJob);

        Job newerJob = new Job();
        newerJob.setStatus(JobStatus.failed);
        newerJob.setOrganization(organization);
        newerJob.setWorkspace(workspace);
        jobRepository.saveAndFlush(newerJob);

        NotificationOutbox older = new NotificationOutbox();
        older.setId(UUID.randomUUID());
        older.setJob(olderJob);
        older.setConfiguration(configuration);
        older.setPayload("{}");
        older.setStatus(NotificationOutboxStatus.SENT);
        notificationOutboxRepository.saveAndFlush(older);
        // Relying on real-time separation between the two saveAndFlush calls to order these two
        // rows is flaky under load (fast execution + timestamp resolution can tie or invert them);
        // backdating "older" explicitly makes the ordering deterministic.
        backdate(older.getId(), new Date(System.currentTimeMillis() - 60_000));

        NotificationOutbox newer = new NotificationOutbox();
        newer.setId(UUID.randomUUID());
        newer.setJob(newerJob);
        newer.setConfiguration(configuration);
        newer.setPayload("{}");
        newer.setStatus(NotificationOutboxStatus.FAILED);
        newer.setLastError("connection refused");
        notificationOutboxRepository.saveAndFlush(newer);

        List<NotificationOutbox> deliveries = notificationOutboxRepository
                .findByJob_Workspace_IdOrderByCreatedDateDesc(workspace.getId(), PageRequest.of(0, 10));

        assertThat(deliveries).extracting(NotificationOutbox::getId)
                .containsSubsequence(newer.getId(), older.getId());
    }
}
