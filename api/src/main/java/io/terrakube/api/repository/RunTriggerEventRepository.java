package io.terrakube.api.repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;

// Every bulk UPDATE below uses @Modifying(clearAutomatically = true): a bulk UPDATE bypasses
// Hibernate's first-level cache, so without clearing, an entity already loaded in the caller's
// persistence context would keep showing its pre-update values on a subsequent find/query.
// Mirrors NotificationOutboxRepository's claim/record/sweep/prune shape.
public interface RunTriggerEventRepository extends JpaRepository<RunTriggerEvent, UUID> {

    boolean existsByJob_Id(int jobId);

    Optional<RunTriggerEvent> findByJob_Id(int jobId);

    /**
     * Backs the admin listing endpoint - operator-facing, so newest first. Joins {@code job}
     * (and its {@code workspace}) in the same query: the endpoint reads both off every row it
     * returns, and without the fetch join that would be a lazy-init query per row instead.
     */
    @Query("SELECT e FROM run_trigger_event e JOIN FETCH e.job j JOIN FETCH j.workspace "
            + "WHERE e.status = :status ORDER BY e.createdDate DESC")
    List<RunTriggerEvent> findByStatusOrderByCreatedDateDesc(
            @Param("status") RunTriggerEventStatus status, Pageable pageable);

    // Atomic claim: flips PENDING -> PROCESSING in one statement, so only one of several
    // concurrent callers (overlapping poller cycle, another replica) ever sees rows == 1.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE run_trigger_event e SET e.status = :newStatus, e.attemptCount = e.attemptCount + 1, "
            + "e.lastAttemptAt = :now, e.updatedDate = :now "
            + "WHERE e.id = :id AND e.status = :expectedStatus")
    int claimForProcessing(@Param("id") UUID id, @Param("expectedStatus") RunTriggerEventStatus expectedStatus,
            @Param("newStatus") RunTriggerEventStatus newStatus, @Param("now") Date now);

    // Keyed on the exact lastAttemptAt from claim time so a stale attempt can't clobber a newer one.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE run_trigger_event e SET e.status = :newStatus, e.lastError = :lastError, "
            + "e.nextAttemptAt = :nextAttemptAt, e.updatedDate = :now "
            + "WHERE e.id = :id AND e.status = :expectedStatus AND e.lastAttemptAt = :expectedLastAttemptAt")
    int recordResult(@Param("id") UUID id, @Param("expectedStatus") RunTriggerEventStatus expectedStatus,
            @Param("expectedLastAttemptAt") Date expectedLastAttemptAt,
            @Param("newStatus") RunTriggerEventStatus newStatus, @Param("lastError") String lastError,
            @Param("nextAttemptAt") Date nextAttemptAt, @Param("now") Date now);

    // Bounded, fairness-ordered (oldest first). nextAttemptAt is null for a row that has never
    // failed; such a row is due as soon as it's PENDING.
    @Query("SELECT e FROM run_trigger_event e WHERE e.status = :status "
            + "AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now) ORDER BY e.createdDate ASC")
    List<RunTriggerEvent> findDueForProcessing(@Param("status") RunTriggerEventStatus status,
            @Param("now") Date now, Pageable pageable);

    // Crash recovery for a row stuck in PROCESSING. attemptCount isn't bumped again - it was
    // already counted at claim time.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE run_trigger_event e SET e.status = :pending, e.updatedDate = :cutoffQueriedAt "
            + "WHERE e.status = :processing AND e.lastAttemptAt < :cutoff AND e.attemptCount < :maxAttempts")
    int reclaimStuckProcessingRows(@Param("processing") RunTriggerEventStatus processing,
            @Param("pending") RunTriggerEventStatus pending, @Param("cutoff") Date cutoff,
            @Param("maxAttempts") int maxAttempts, @Param("cutoffQueriedAt") Date cutoffQueriedAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE run_trigger_event e SET e.status = :failed, e.lastError = :note, e.updatedDate = :cutoffQueriedAt "
            + "WHERE e.status = :processing AND e.lastAttemptAt < :cutoff AND e.attemptCount >= :maxAttempts")
    int failStuckProcessingRowsAtMaxAttempts(@Param("processing") RunTriggerEventStatus processing,
            @Param("failed") RunTriggerEventStatus failed, @Param("cutoff") Date cutoff,
            @Param("maxAttempts") int maxAttempts, @Param("note") String note,
            @Param("cutoffQueriedAt") Date cutoffQueriedAt);

    // Operator replay of a permanently failed event: same conditional-update discipline as the
    // claim/record steps above rather than read-then-write.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE run_trigger_event e SET e.status = :pending, e.attemptCount = 0, e.lastError = null, "
            + "e.nextAttemptAt = null, e.updatedDate = :now "
            + "WHERE e.id = :id AND e.status = :failed")
    int rearmFailedForRetry(@Param("id") UUID id, @Param("failed") RunTriggerEventStatus failed,
            @Param("pending") RunTriggerEventStatus pending, @Param("now") Date now);

    // Only targets terminal rows - a PENDING/PROCESSING row is mid-flight regardless of age.
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM run_trigger_event e WHERE e.status IN :terminalStatuses AND e.createdDate < :cutoff")
    int deleteTerminalRowsCreatedBefore(@Param("terminalStatuses") List<RunTriggerEventStatus> terminalStatuses,
            @Param("cutoff") Date cutoff);
}
