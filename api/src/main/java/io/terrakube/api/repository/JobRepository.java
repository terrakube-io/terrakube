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
}
