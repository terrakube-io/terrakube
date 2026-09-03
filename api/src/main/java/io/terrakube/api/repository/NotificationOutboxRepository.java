package io.terrakube.api.repository;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;

// Every bulk UPDATE below uses @Modifying(clearAutomatically = true): a bulk UPDATE bypasses
// Hibernate's first-level cache, so without clearing, an entity already loaded in the caller's
// persistence context would keep showing its pre-update values on a subsequent find/query.
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {

    List<NotificationOutbox> findByJob_Workspace_IdOrderByCreatedDateDesc(UUID workspaceId, Pageable pageable);

    List<NotificationOutbox> findByJob_Workspace_IdAndJob_IdInOrderByCreatedDateDesc(UUID workspaceId,
            List<Integer> jobIds);

    // Ownership check for the manual-retry endpoint: confirms the delivery actually belongs to
    // the workspace in the request path before rearming it, so a caller who can manage
    // notifications for their own workspace can't retry (or probe the existence of) a delivery
    // belonging to a workspace they have no access to just by guessing its UUID.
    boolean existsByIdAndJob_Workspace_Id(UUID id, UUID workspaceId);

    // Atomic claim: flips PENDING -> SENDING and bumps bookkeeping in one statement, so two
    // concurrent callers on the same row (an overlapping poller cycle, another pod, the
    // immediate-dispatch path racing the poller) can't both pass a check-then-act race - only
    // one UPDATE can match the WHERE status = :expectedStatus predicate, so only one caller
    // ever sees rows == 1 and proceeds to actually deliver. The HTTP call itself happens with
    // no transaction/lock held once this returns.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE notification_outbox o SET o.status = :newStatus, o.attemptCount = o.attemptCount + 1, "
            + "o.lastAttemptAt = :now, o.updatedDate = :now "
            + "WHERE o.id = :id AND o.status = :expectedStatus")
    int claimForDelivery(@Param("id") UUID id, @Param("expectedStatus") NotificationOutboxStatus expectedStatus,
            @Param("newStatus") NotificationOutboxStatus newStatus, @Param("now") Date now);

    // Keyed on the exact lastAttemptAt observed at claim time (not just id+SENDING) so a result
    // from a delivery attempt that outlived the stuck-row sweep's reclaim window is discarded
    // instead of clobbering whatever a later claimant already recorded for this row.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE notification_outbox o SET o.status = :newStatus, o.lastError = :lastError, "
            + "o.nextAttemptAt = :nextAttemptAt, o.updatedDate = :now "
            + "WHERE o.id = :id AND o.status = :expectedStatus AND o.lastAttemptAt = :expectedLastAttemptAt")
    int recordDeliveryResult(@Param("id") UUID id, @Param("expectedStatus") NotificationOutboxStatus expectedStatus,
            @Param("expectedLastAttemptAt") Date expectedLastAttemptAt, @Param("newStatus") NotificationOutboxStatus newStatus,
            @Param("lastError") String lastError, @Param("nextAttemptAt") Date nextAttemptAt, @Param("now") Date now);

    // Bounded, fairness-ordered (oldest first) replacement for the old pair of unbounded,
    // unordered finders. nextAttemptAt is null for a row that has never failed with a
    // server-supplied delay (e.g. Retry-After); such a row is due as soon as it's PENDING.
    @Query("SELECT o FROM notification_outbox o WHERE o.status = :status "
            + "AND (o.nextAttemptAt IS NULL OR o.nextAttemptAt <= :now) ORDER BY o.createdDate ASC")
    List<NotificationOutbox> findDueForDispatch(@Param("status") NotificationOutboxStatus status,
            @Param("now") Date now, Pageable pageable);

    // Crash recovery: a pod that claimed a row (PENDING -> SENDING) and died mid-HTTP-call
    // leaves it stuck in SENDING forever without this. attemptCount is NOT bumped again here -
    // the attempt was already counted at claim time.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE notification_outbox o SET o.status = :pending, o.updatedDate = :cutoffQueriedAt "
            + "WHERE o.status = :sending AND o.lastAttemptAt < :cutoff AND o.attemptCount < :maxAttempts")
    int reclaimStuckSendingRows(@Param("sending") NotificationOutboxStatus sending,
            @Param("pending") NotificationOutboxStatus pending, @Param("cutoff") Date cutoff,
            @Param("maxAttempts") int maxAttempts, @Param("cutoffQueriedAt") Date cutoffQueriedAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE notification_outbox o SET o.status = :failed, o.lastError = :note, o.updatedDate = :cutoffQueriedAt "
            + "WHERE o.status = :sending AND o.lastAttemptAt < :cutoff AND o.attemptCount >= :maxAttempts")
    int failStuckSendingRowsAtMaxAttempts(@Param("sending") NotificationOutboxStatus sending,
            @Param("failed") NotificationOutboxStatus failed, @Param("cutoff") Date cutoff,
            @Param("maxAttempts") int maxAttempts, @Param("note") String note,
            @Param("cutoffQueriedAt") Date cutoffQueriedAt);

    // Manual retry: only re-arms a row that is actually FAILED, via the same conditional-update
    // discipline as the claim/record steps above rather than read-then-write.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE notification_outbox o SET o.status = :pending, o.attemptCount = 0, o.lastError = null, "
            + "o.nextAttemptAt = null, o.updatedDate = :now "
            + "WHERE o.id = :id AND o.status = :failed")
    int rearmFailedForRetry(@Param("id") UUID id, @Param("failed") NotificationOutboxStatus failed,
            @Param("pending") NotificationOutboxStatus pending, @Param("now") Date now);

    // Retention sweep: only ever targets rows in a terminal state (SENT/FAILED/SKIPPED) older
    // than the cutoff - a row still PENDING/SENDING is mid-flight regardless of age and must
    // never be deleted out from under the dispatch pipeline.
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM notification_outbox o WHERE o.status IN :terminalStatuses AND o.createdDate < :cutoff")
    int deleteTerminalRowsCreatedBefore(@Param("terminalStatuses") List<NotificationOutboxStatus> terminalStatuses,
            @Param("cutoff") Date cutoff);
}
