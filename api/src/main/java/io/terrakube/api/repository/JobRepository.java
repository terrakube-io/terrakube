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
import io.terrakube.api.rs.workspace.Workspace;

import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Integer> {

    // Terminal statuses that don't block a later job - shared by the FIFO dispatch-ordering
    // queries below, mirrors the list ScheduleJob.runExecution already uses in Java.
    String TERMINAL_JOB_STATUSES = "'failed','completed','rejected','cancelled','noChanges'";

    List<Job> findAllByOrganizationAndStatusNotInOrderByIdAsc(Organization organization, List<JobStatus> status);
    List<Job> findAllByStatusInOrderByIdAsc(List<JobStatus> status);
    List<Job> findAllByOrganizationNameAndStatusInOrderByIdAsc(String organizationName, List<JobStatus> status);


    Optional<List<Job>> findAllByWorkspaceAndStatusNotInOrderByIdAsc(Workspace workspace, List<JobStatus> status);
    List<Job> findAllByWorkspaceAndStatusInOrderByIdDesc(Workspace workspace, List<JobStatus> jobStatuses);
    Optional<List<Job>> findByWorkspaceAndStatusNotInAndIdLessThan(Workspace workspace, List<JobStatus> jobStatuses, int jobId);
    Optional<List<Job>> findByWorkspaceAndStatusInAndIdLessThanOrderByIdDesc(Workspace workspace, List<JobStatus> jobStatuses, int jobId);

    Optional<List<Job>> findByWorkspaceAndStatusInAndIdLessThan(Workspace workspace, List<JobStatus> jobStatuses, int jobId);

    Optional<Job> findFirstByWorkspaceAndAndStatusInOrderByIdDesc(Workspace workspace, List<JobStatus> jobStatuses);
    Optional<Job> findFirstByWorkspaceAndStatusInOrderByIdAsc(Workspace workspace, List<JobStatus> jobStatuses);
    Optional<Job> findFirstByWorkspaceOrderByIdDesc(Workspace workspace);

    /**
     * Finds the most recent other job on the same PR with a posted plan comment, so replans can
     * update that comment in place instead of posting a new one. Apply jobs (autoApply=true) are
     * excluded so an apply's audit-trail comment is never overwritten by a later plan.
     */
    Optional<Job> findFirstByWorkspaceAndPrNumberAndIdNotAndAutoApplyFalseAndPrCommentIdIsNotNullOrderByIdDesc(
            Workspace workspace, Integer prNumber, int id);

    @Modifying(flushAutomatically = true)
    @Query("update job j set j.status = :status where j.id = :jobId")
    int updateStatusById(@Param("status") JobStatus status, @Param("jobId") int jobId);

    @Query(value = "SELECT id FROM job WHERE workspace_id = :workspaceId", nativeQuery = true)
    List<Integer> findAllJobIdsByWorkspaceIncludingDeleted(@Param("workspaceId") String workspaceId);

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
    @Query(value = "SELECT NOT EXISTS (" +
            "  SELECT 1 FROM job earlier" +
            "  WHERE earlier.id < :candidateJobId" +
            "    AND earlier.status IN ('pending', 'approved')" +
            "    AND earlier.deleted = false" +
            "    AND NOT EXISTS (" +
            "      SELECT 1 FROM job blocker" +
            "      WHERE blocker.workspace_id = earlier.workspace_id" +
            "        AND blocker.id < earlier.id" +
            "        AND blocker.deleted = false" +
            "        AND blocker.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
            "    )" +
            ")", nativeQuery = true)
    boolean isJobNextInDispatchOrder(@Param("candidateJobId") int candidateJobId);

    /**
     * The oldest pending/approved, workspace-unblocked job id waiting for the shared executor
     * pool, or null if none. Used to wake the next job immediately instead of waiting up to 30s.
     */
    @Query(value = "SELECT MIN(j.id) FROM job j" +
            " WHERE j.status IN ('pending', 'approved')" +
            "   AND j.deleted = false" +
            "   AND NOT EXISTS (" +
            "     SELECT 1 FROM job earlier" +
            "     WHERE earlier.workspace_id = j.workspace_id" +
            "       AND earlier.id < j.id" +
            "       AND earlier.deleted = false" +
            "       AND earlier.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
            "   )", nativeQuery = true)
    Integer findNextDispatchableJobId();

    // --- Guarded variants (design doc 2026-09-02 §3.6) --------------------------------------
    // An earlier pending/approved job only blocks the FIFO queue when it still has an executable
    // step: it has no steps yet (not initialised) OR at least one step is still pending. A job
    // with steps but none pending has consumed all its work and must not block later jobs.
    // queue/running/waitingApproval blockers are untouched - the executor or a user owns those,
    // and a step can legitimately be 'running' with zero pending steps mid-apply.

    /** Guarded variant of {@link #isJobNextInDispatchOrder}. */
    @Query(value = "SELECT NOT EXISTS (" +
            "  SELECT 1 FROM job earlier" +
            "  WHERE earlier.id < :candidateJobId" +
            "    AND earlier.status IN ('pending', 'approved')" +
            "    AND earlier.deleted = false" +
            "    AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job_id = earlier.id)" +
            "          OR EXISTS (SELECT 1 FROM step s WHERE s.job_id = earlier.id AND s.status = 'pending') )" +
            "    AND NOT EXISTS (" +
            "      SELECT 1 FROM job blocker" +
            "      WHERE blocker.workspace_id = earlier.workspace_id" +
            "        AND blocker.id < earlier.id" +
            "        AND blocker.deleted = false" +
            "        AND blocker.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
            "    )" +
            ")", nativeQuery = true)
    boolean isJobNextInDispatchOrderExecutable(@Param("candidateJobId") int candidateJobId);

    /** Guarded variant of {@link #findNextDispatchableJobId}. */
    @Query(value = "SELECT MIN(j.id) FROM job j" +
            " WHERE j.status IN ('pending', 'approved')" +
            "   AND j.deleted = false" +
            "   AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job_id = j.id)" +
            "         OR EXISTS (SELECT 1 FROM step s WHERE s.job_id = j.id AND s.status = 'pending') )" +
            "   AND NOT EXISTS (" +
            "     SELECT 1 FROM job earlier" +
            "     WHERE earlier.workspace_id = j.workspace_id" +
            "       AND earlier.id < j.id" +
            "       AND earlier.deleted = false" +
            "       AND earlier.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
            "       AND ( earlier.status NOT IN ('pending','approved')" +
            "             OR NOT EXISTS (SELECT 1 FROM step s2 WHERE s2.job_id = earlier.id)" +
            "             OR EXISTS (SELECT 1 FROM step s2 WHERE s2.job_id = earlier.id AND s2.status = 'pending') )" +
            "   )", nativeQuery = true)
    Integer findNextDispatchableExecutableJobId();

    /** Count of jobs the guarded FIFO-admission query currently considers eligible - a
     *  pending/approved job that is uninitialised or still has a pending step. Queue-depth gauge. */
    @Query(value = "SELECT COUNT(*) FROM job j" +
            " WHERE j.status IN ('pending','approved')" +
            "   AND j.deleted = false" +
            "   AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job_id = j.id)" +
            "         OR EXISTS (SELECT 1 FROM step s WHERE s.job_id = j.id AND s.status = 'pending') )",
            nativeQuery = true)
    int countDispatchEligibleJobs();
}
