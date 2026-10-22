package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.rs.workspace.Workspace;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, Integer> {

    // Status sets for JPQL FIFO dispatch-ordering queries below, matching the lists
    // ScheduleJob.runExecution uses in Java.
    String ACTIVE_JOB_STATUSES = "io.terrakube.api.rs.job.JobStatus.pending, io.terrakube.api.rs.job.JobStatus.approved";
    String TERMINAL_JOB_STATUSES = "io.terrakube.api.rs.job.JobStatus.failed, io.terrakube.api.rs.job.JobStatus.completed, io.terrakube.api.rs.job.JobStatus.rejected, io.terrakube.api.rs.job.JobStatus.cancelled, io.terrakube.api.rs.job.JobStatus.noChanges";

    // A job in a locked workspace cannot be dispatched (ScheduleJob.doRunExecution returns early), so
    // it must not hold a place in the global FIFO queue: it would otherwise block every later job
    // in every other workspace until someone unlocks it. The one exception mirrors
    // ScheduleJob.isOwnPrApplyLock: an auto-apply job whose workspace was locked by its own PR
    // "apply" comment (WebhookService.buildPrApplyLockDescription) is still allowed to run.
    String EARLIER_NOT_LOCKED_OUT = "( earlier.workspace.locked = false" +
            " OR ( earlier.autoApply = true AND earlier.prNumber IS NOT NULL" +
            "      AND earlier.workspace.lockDescription = CONCAT('Locked by PR #', CAST(earlier.prNumber AS string), ' apply') ) )";
    String JOB_NOT_LOCKED_OUT = "( j.workspace.locked = false" +
            " OR ( j.autoApply = true AND j.prNumber IS NOT NULL" +
            "      AND j.workspace.lockDescription = CONCAT('Locked by PR #', CAST(j.prNumber AS string), ' apply') ) )";

    List<Job> findAllByOrganizationAndStatusNotInOrderByIdAsc(Organization organization, List<JobStatus> status);
    List<Job> findAllByStatusInOrderByIdAsc(List<JobStatus> status);
    List<Job> findAllByOrganizationNameAndStatusInOrderByIdAsc(String organizationName, List<JobStatus> status);

    @Query("SELECT count(j) FROM job j WHERE j.via = :via AND j.status IN :statuses")
    long countByViaAndStatusIn(@Param("via") String via, @Param("statuses") List<JobStatus> statuses);


    Optional<List<Job>> findAllByWorkspaceAndStatusNotInOrderByIdAsc(Workspace workspace, List<JobStatus> status);
    List<Job> findAllByWorkspaceAndStatusInOrderByIdDesc(Workspace workspace, List<JobStatus> jobStatuses);
    Optional<List<Job>> findByWorkspaceAndStatusNotInAndIdLessThan(Workspace workspace, List<JobStatus> jobStatuses, int jobId);
    Optional<List<Job>> findByWorkspaceAndStatusInAndIdLessThanOrderByIdDesc(Workspace workspace, List<JobStatus> jobStatuses, int jobId);

    Optional<List<Job>> findByWorkspaceAndStatusInAndIdLessThan(Workspace workspace, List<JobStatus> jobStatuses, int jobId);

    Optional<Job> findFirstByWorkspaceAndStatusInOrderByIdDesc(Workspace workspace, List<JobStatus> jobStatuses);
    Optional<Job> findFirstByWorkspaceAndStatusInOrderByIdAsc(Workspace workspace, List<JobStatus> jobStatuses);
    Optional<Job> findFirstByWorkspaceAndIdNotAndStatusInOrderByIdAsc(Workspace workspace, int jobId, Collection<JobStatus> jobStatuses);
    Optional<Job> findFirstByWorkspaceOrderByIdDesc(Workspace workspace);

    /**
     * Finds the most recent other job on the same PR with a posted plan comment, so replans can
     * update that comment in place instead of posting a new one. Apply jobs (autoApply=true) are
     * excluded so an apply's audit-trail comment is never overwritten by a later plan.
     */
    Optional<Job> findFirstByWorkspaceAndPrNumberAndIdNotAndAutoApplyFalseAndPrCommentIdIsNotNullOrderByIdDesc(
            Workspace workspace, Integer prNumber, int id);

    // Only the PR comment columns, so a service that holds an old copy of the job cannot write the rest of
    // it back, and a job that was pruned meanwhile is not written again (0 rows, not a new row).
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update job j set j.prCommentId = :commentId, j.prCommentError = :error where j.id = :jobId")
    int updatePrComment(@Param("jobId") int jobId, @Param("commentId") String commentId, @Param("error") String error);

    // Bulk update on purpose: the plan artifacts are already deleted when this runs, after the job's
    // own update has committed, so it must not trigger the job's hooks again.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update job j set j.terraformPlanArtifacts = null, j.terraformPlanArtifactsChecksum = null where j.id = :jobId")
    int clearPlanArtifactsLocation(@Param("jobId") int jobId);

    @Modifying(flushAutomatically = true)
    @Query("update job j set j.status = :status where j.id = :jobId")
    int updateStatusById(@Param("status") JobStatus status, @Param("jobId") int jobId);

    @Modifying(flushAutomatically = true)
    @Query("update job j set j.status = :newStatus where j.id = :jobId and j.status in :currentStatuses")
    int updateStatusByIdAndStatusIn(@Param("newStatus") JobStatus newStatus, @Param("jobId") int jobId, @Param("currentStatuses") List<JobStatus> currentStatuses);

    /**
     * Pins the version a job resolved from its constraint: only when none is set yet, or it is the same one,
     * only while the job is still active, in one statement, so two writers cannot both win. Returns 1 when
     * the job now has that version.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update job j set j.resolvedTerraformVersion = :version "
            + "where j.id = :jobId and j.organization.id = :organizationId "
            + "and j.status in (io.terrakube.api.rs.job.JobStatus.pending, io.terrakube.api.rs.job.JobStatus.waitingApproval, "
            + "io.terrakube.api.rs.job.JobStatus.approved, io.terrakube.api.rs.job.JobStatus.queue, "
            + "io.terrakube.api.rs.job.JobStatus.running) "
            + "and (j.resolvedTerraformVersion is null or j.resolvedTerraformVersion = :version)")
    int pinResolvedTerraformVersion(@Param("jobId") int jobId, @Param("organizationId") UUID organizationId,
                                    @Param("version") String version);

    @Query(value = "SELECT id FROM job WHERE workspace_id = :workspaceId", nativeQuery = true)
    List<Integer> findAllJobIdsByWorkspaceIncludingDeleted(@Param("workspaceId") String workspaceId);

    @Query(value = "SELECT id FROM job WHERE workspace_id = :workspaceId AND status NOT IN ('failed','completed','rejected','cancelled','noChanges','notExecuted')", nativeQuery = true)
    List<Integer> findActiveJobIdsByWorkspace(@Param("workspaceId") String workspaceId);

    /**
     * Ids of jobs that reached a terminal status inside the trailing sweep window - used to reclaim
     * their live-log Redis streams. Native so soft-deleted jobs are included.
     */
    @Query(value = "SELECT id FROM job WHERE status IN " +
            "('completed','failed','cancelled','rejected','noChanges','notExecuted') " +
            "AND updated_date >= :from AND updated_date < :cutoff", nativeQuery = true)
    List<Integer> findTerminalJobIdsUpdatedBetween(@Param("from") java.util.Date from,
                                                   @Param("cutoff") java.util.Date cutoff);

    /**
     * The run trigger fan-out idempotency key: at most one dependent job per (destination
     * workspace, upstream job) pair. {@code RunTriggerDispatchService.enqueue} checks this
     * before persisting a new dependent so a reclaimed or retried {@code RunTriggerEvent} does
     * not create a duplicate job and a duplicate Quartz schedule. {@code OrderByIdDesc} is
     * defensive only - it picks the most recent if rows written before this check existed ever
     * produced more than one.
     */
    Optional<Job> findFirstByWorkspaceAndTriggeredByJobIdOrderByIdDesc(Workspace workspace, Integer triggeredByJobId);

    /**
     * Row-locks the job for the rest of the caller's transaction. Two overlapping Quartz
     * firings for the same job (the ad-hoc trigger fired by ScheduleJobService.createJobContext
     * racing the first tick of its own 30s recurring trigger, or a createJobContextNow one-shot
     * racing an in-flight run - see the concurrency note on ScheduleJob) can otherwise both reach
     * TclService.initJobConfiguration before either has committed its step inserts, so both
     * observe "no steps yet" and both create the template's steps - each duplicate is later
     * dispatched and genuinely executed on its own scheduling cycle. Taking this lock first
     * forces a second overlapping transaction to block until the first commits, so it then sees
     * the already-created steps and skips creating its own.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from job j where j.id = :jobId")
    Job lockForUpdate(@Param("jobId") int jobId);

    /**
     * True if no earlier pending/approved job exists with a smaller id - i.e. candidateJobId is
     * the oldest job currently eligible for the shared executor pool. Does not re-check
     * candidateJobId's own per-workspace blocking; ScheduleJob already does that before calling
     * this.
     */
    @Query("SELECT CASE WHEN NOT EXISTS (" +
            "  SELECT 1 FROM job earlier" +
            "  WHERE earlier.id < :candidateJobId" +
            "    AND earlier.status IN (" + ACTIVE_JOB_STATUSES + ")" +
            "    AND earlier.deleted = false" +
            "    AND earlier.workspace.deleted = false" +
            "    AND " + EARLIER_NOT_LOCKED_OUT +
            "    AND NOT EXISTS (" +
            "      SELECT 1 FROM job blocker" +
            "      WHERE blocker.workspace = earlier.workspace" +
            "        AND blocker.id < earlier.id" +
            "        AND blocker.deleted = false" +
            "        AND blocker.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
            "    )" +
            ") THEN true ELSE false END")
    boolean isJobNextInDispatchOrder(@Param("candidateJobId") int candidateJobId);

    /**
     * The oldest pending/approved, workspace-unblocked job id waiting for the shared executor
     * pool, or null if none. Used to wake the next job immediately instead of waiting up to 30s.
     */
    @Query("SELECT MIN(j.id) FROM job j" +
            " WHERE j.status IN (" + ACTIVE_JOB_STATUSES + ")" +
            "   AND j.deleted = false" +
            "   AND j.workspace.deleted = false" +
            "   AND " + JOB_NOT_LOCKED_OUT +
            "   AND NOT EXISTS (" +
            "     SELECT 1 FROM job earlier" +
            "     WHERE earlier.workspace = j.workspace" +
            "       AND earlier.id < j.id" +
            "       AND earlier.deleted = false" +
            "       AND earlier.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
            "   )")
    Integer findNextDispatchableJobId();

    /** Pending/approved jobs with an executable step that are held out of the FIFO queue only because
     *  their workspace is locked (ScheduleJob will not dispatch them until it is unlocked). Visibility
     *  gauge for #3728. */
    @Query("SELECT COUNT(j) FROM job j" +
            " WHERE j.status IN (" + ACTIVE_JOB_STATUSES + ")" +
            "   AND j.deleted = false" +
            "   AND j.workspace.deleted = false" +
            "   AND NOT " + JOB_NOT_LOCKED_OUT +
            "   AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job = j)" +
            "         OR EXISTS (SELECT 1 FROM step s WHERE s.job = j AND s.status = io.terrakube.api.rs.job.JobStatus.pending) )")
    int countJobsHeldByLockedWorkspace();

    // --- Guarded variants (design doc 2026-09-02 §3.6) --------------------------------------
    // An earlier pending/approved job only blocks the FIFO queue when it still has an executable
    // step: it has no steps yet (not initialised) OR at least one step is still pending. A job
    // with steps but none pending has consumed all its work and must not block later jobs.
    // queue/running/waitingApproval blockers are untouched - the executor or a user owns those,
    // and a step can legitimately be 'running' with zero pending steps mid-apply.

    /** Guarded variant of {@link #isJobNextInDispatchOrder}. */
    @Query("SELECT CASE WHEN NOT EXISTS (" +
            "  SELECT 1 FROM job earlier" +
            "  WHERE earlier.id < :candidateJobId" +
            "    AND earlier.status IN (" + ACTIVE_JOB_STATUSES + ")" +
            "    AND earlier.deleted = false" +
            "    AND earlier.workspace.deleted = false" +
            "    AND " + EARLIER_NOT_LOCKED_OUT +
            "    AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job = earlier)" +
            "          OR EXISTS (SELECT 1 FROM step s WHERE s.job = earlier AND s.status = io.terrakube.api.rs.job.JobStatus.pending) )" +
            "    AND NOT EXISTS (" +
            "      SELECT 1 FROM job blocker" +
            "      WHERE blocker.workspace = earlier.workspace" +
            "        AND blocker.id < earlier.id" +
            "        AND blocker.deleted = false" +
            "        AND blocker.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
            "    )" +
            ") THEN true ELSE false END")
    boolean isJobNextInDispatchOrderExecutable(@Param("candidateJobId") int candidateJobId);

    /** Guarded variant of {@link #findNextDispatchableJobId}. */
    @Query("SELECT MIN(j.id) FROM job j" +
            " WHERE j.status IN (" + ACTIVE_JOB_STATUSES + ")" +
            "   AND j.deleted = false" +
            "   AND j.workspace.deleted = false" +
            "   AND " + JOB_NOT_LOCKED_OUT +
            "   AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job = j)" +
            "         OR EXISTS (SELECT 1 FROM step s WHERE s.job = j AND s.status = io.terrakube.api.rs.job.JobStatus.pending) )" +
            "   AND NOT EXISTS (" +
            "     SELECT 1 FROM job earlier" +
            "     WHERE earlier.workspace = j.workspace" +
            "       AND earlier.id < j.id" +
            "       AND earlier.deleted = false" +
            "       AND earlier.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
            "       AND ( earlier.status NOT IN (" + ACTIVE_JOB_STATUSES + ")" +
            "             OR NOT EXISTS (SELECT 1 FROM step s2 WHERE s2.job = earlier)" +
            "             OR EXISTS (SELECT 1 FROM step s2 WHERE s2.job = earlier AND s2.status = io.terrakube.api.rs.job.JobStatus.pending) )" +
            "   )")
    Integer findNextDispatchableExecutableJobId();

    /** Count of jobs the guarded FIFO-admission query currently considers eligible - a
     *  pending/approved job that is uninitialised or still has a pending step. Queue-depth gauge. */
    @Query("SELECT COUNT(j) FROM job j" +
            " WHERE j.status IN (" + ACTIVE_JOB_STATUSES + ")" +
            "   AND j.deleted = false" +
            "   AND j.workspace.deleted = false" +
            "   AND " + JOB_NOT_LOCKED_OUT +
            "   AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job = j)" +
            "         OR EXISTS (SELECT 1 FROM step s WHERE s.job = j AND s.status = io.terrakube.api.rs.job.JobStatus.pending) )")
    int countDispatchEligibleJobs();

    // --- Dispatch-retry-aware variants (issues #3665/#3666) --------------------------------
    // Layered on top of the guarded (step-aware) variants above, not a modification of them, so
    // admissionGuardEnabled and dispatch-retry's admissionDeferralEnabled stay two independently
    // selectable rollout phases. A job currently deferred by the bounded dispatch-retry backoff
    // (dispatchNextRetryAt in the future) is excluded from the *global, cross-workspace* "earlier
    // blocker" set - but only when earlier is in a *different* workspace from the candidate. When
    // earlier shares the candidate's own workspace, the backoff predicate is skipped (earlier
    // still counts as blocking) so same-workspace ordering survives regardless of backoff state -
    // exactly the same invariant the inner per-workspace "blocker" subquery already protects for
    // the zero-pending-step guard.

    /** Guarded + dispatch-retry-aware variant of {@link #isJobNextInDispatchOrder}. */
    @Query("SELECT CASE WHEN NOT EXISTS (" +
            "  SELECT 1 FROM job earlier" +
            "  WHERE earlier.id < :candidateJobId" +
            "    AND earlier.status IN (" + ACTIVE_JOB_STATUSES + ")" +
            "    AND earlier.deleted = false" +
            "    AND earlier.workspace.deleted = false" +
            "    AND " + EARLIER_NOT_LOCKED_OUT +
            "    AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job = earlier)" +
            "          OR EXISTS (SELECT 1 FROM step s WHERE s.job = earlier AND s.status = io.terrakube.api.rs.job.JobStatus.pending) )" +
            "    AND ( earlier.workspace = (SELECT c.workspace FROM job c WHERE c.id = :candidateJobId)" +
            "          OR earlier.dispatchNextRetryAt IS NULL OR earlier.dispatchNextRetryAt <= CURRENT_TIMESTAMP )" +
            "    AND NOT EXISTS (" +
            "      SELECT 1 FROM job blocker" +
            "      WHERE blocker.workspace = earlier.workspace" +
            "        AND blocker.id < earlier.id" +
            "        AND blocker.deleted = false" +
            "        AND blocker.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
            "    )" +
            ") THEN true ELSE false END")
    boolean isJobNextInDispatchOrderExecutableNotDeferred(@Param("candidateJobId") int candidateJobId);

    /** Guarded + dispatch-retry-aware variant of {@link #findNextDispatchableJobId}. */
    @Query("SELECT MIN(j.id) FROM job j" +
            " WHERE j.status IN (" + ACTIVE_JOB_STATUSES + ")" +
            "   AND j.deleted = false" +
            "   AND j.workspace.deleted = false" +
            "   AND " + JOB_NOT_LOCKED_OUT +
            "   AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job = j)" +
            "         OR EXISTS (SELECT 1 FROM step s WHERE s.job = j AND s.status = io.terrakube.api.rs.job.JobStatus.pending) )" +
            "   AND ( j.dispatchNextRetryAt IS NULL OR j.dispatchNextRetryAt <= CURRENT_TIMESTAMP )" +
            "   AND NOT EXISTS (" +
            "     SELECT 1 FROM job earlier" +
            "     WHERE earlier.workspace = j.workspace" +
            "       AND earlier.id < j.id" +
            "       AND earlier.deleted = false" +
            "       AND earlier.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
            "       AND ( earlier.status NOT IN (" + ACTIVE_JOB_STATUSES + ")" +
            "             OR NOT EXISTS (SELECT 1 FROM step s2 WHERE s2.job = earlier)" +
            "             OR EXISTS (SELECT 1 FROM step s2 WHERE s2.job = earlier AND s2.status = io.terrakube.api.rs.job.JobStatus.pending) )" +
            "   )")
    Integer findNextDispatchableExecutableNotDeferredJobId();

    /** Count for the "dispatch-deferred" gauge: the subset of {@link #countDispatchEligibleJobs}
     *  that is additionally excluded purely by dispatch-retry backoff. */
    @Query("SELECT COUNT(j) FROM job j" +
            " WHERE j.status IN (" + ACTIVE_JOB_STATUSES + ")" +
            "   AND j.deleted = false" +
            "   AND j.workspace.deleted = false" +
            "   AND " + JOB_NOT_LOCKED_OUT +
            "   AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job = j)" +
            "         OR EXISTS (SELECT 1 FROM step s WHERE s.job = j AND s.status = io.terrakube.api.rs.job.JobStatus.pending) )" +
            "   AND ( j.dispatchNextRetryAt IS NULL OR j.dispatchNextRetryAt <= CURRENT_TIMESTAMP )")
    int countDispatchEligibleNotDeferredJobs();
}
