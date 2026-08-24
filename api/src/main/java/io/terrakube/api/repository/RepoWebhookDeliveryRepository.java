package io.terrakube.api.repository;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.terrakube.api.rs.webhook.RepoWebhookDelivery;
import io.terrakube.api.rs.webhook.RepoWebhookDeliveryStatus;

// Every bulk UPDATE below uses @Modifying(clearAutomatically = true): a bulk UPDATE bypasses
// Hibernate's first-level cache, so without clearing, an entity already loaded in the caller's
// persistence context would keep showing its pre-update values on a subsequent find/query.
public interface RepoWebhookDeliveryRepository extends JpaRepository<RepoWebhookDelivery, UUID> {

    // Atomic claim: flips PENDING -> PROCESSING and bumps bookkeeping in one statement, so two
    // concurrent callers on the same row (the immediate post-accept dispatch racing the poller)
    // can't both pass a check-then-act race - only one UPDATE can match the WHERE status =
    // :expectedStatus predicate.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE repo_webhook_delivery d SET d.status = :newStatus, d.attemptCount = d.attemptCount + 1, "
            + "d.lastAttemptAt = :now, d.updatedDate = :now "
            + "WHERE d.id = :id AND d.status = :expectedStatus")
    int claimForDelivery(@Param("id") UUID id, @Param("expectedStatus") RepoWebhookDeliveryStatus expectedStatus,
            @Param("newStatus") RepoWebhookDeliveryStatus newStatus, @Param("now") Date now);

    // Keyed on the exact lastAttemptAt observed at claim time so a result from an attempt that
    // outlived the stuck-row sweep's reclaim window is discarded instead of clobbering whatever a
    // later claimant already recorded for this row.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE repo_webhook_delivery d SET d.status = :newStatus, d.lastError = :lastError, "
            + "d.nextAttemptAt = :nextAttemptAt, d.updatedDate = :now "
            + "WHERE d.id = :id AND d.status = :expectedStatus AND d.lastAttemptAt = :expectedLastAttemptAt")
    int recordDeliveryResult(@Param("id") UUID id, @Param("expectedStatus") RepoWebhookDeliveryStatus expectedStatus,
            @Param("expectedLastAttemptAt") Date expectedLastAttemptAt,
            @Param("newStatus") RepoWebhookDeliveryStatus newStatus, @Param("lastError") String lastError,
            @Param("nextAttemptAt") Date nextAttemptAt, @Param("now") Date now);

    // Bounded, fairness-ordered (oldest first) so one repo with a burst of deliveries can't starve
    // every other repo's deliveries out of a poll cycle. nextAttemptAt is null for a row that has
    // never failed; such a row is due as soon as it's PENDING.
    @Query("SELECT d FROM repo_webhook_delivery d WHERE d.status = :status "
            + "AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now) ORDER BY d.createdDate ASC")
    List<RepoWebhookDelivery> findDueForDispatch(@Param("status") RepoWebhookDeliveryStatus status,
            @Param("now") Date now, Pageable pageable);

    // Crash recovery: an instance that claimed a row (PENDING -> PROCESSING) and died mid-fan-out
    // leaves it stuck in PROCESSING forever without this. attemptCount is NOT bumped again here -
    // the attempt was already counted at claim time.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE repo_webhook_delivery d SET d.status = :pending, d.updatedDate = :cutoffQueriedAt "
            + "WHERE d.status = :processing AND d.lastAttemptAt < :cutoff AND d.attemptCount < :maxAttempts")
    int reclaimStuckProcessingRows(@Param("processing") RepoWebhookDeliveryStatus processing,
            @Param("pending") RepoWebhookDeliveryStatus pending, @Param("cutoff") Date cutoff,
            @Param("maxAttempts") int maxAttempts, @Param("cutoffQueriedAt") Date cutoffQueriedAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE repo_webhook_delivery d SET d.status = :failed, d.lastError = :note, d.updatedDate = :cutoffQueriedAt "
            + "WHERE d.status = :processing AND d.lastAttemptAt < :cutoff AND d.attemptCount >= :maxAttempts")
    int failStuckProcessingRowsAtMaxAttempts(@Param("processing") RepoWebhookDeliveryStatus processing,
            @Param("failed") RepoWebhookDeliveryStatus failed, @Param("cutoff") Date cutoff,
            @Param("maxAttempts") int maxAttempts, @Param("note") String note,
            @Param("cutoffQueriedAt") Date cutoffQueriedAt);

    // Retention sweep: only ever targets rows in a terminal state (PROCESSED/FAILED) older than
    // the cutoff - a row still PENDING/PROCESSING is mid-flight regardless of age and must never
    // be deleted out from under the dispatch pipeline.
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM repo_webhook_delivery d WHERE d.status IN :terminalStatuses AND d.createdDate < :cutoff")
    int deleteTerminalRowsCreatedBefore(@Param("terminalStatuses") List<RepoWebhookDeliveryStatus> terminalStatuses,
            @Param("cutoff") Date cutoff);
}
