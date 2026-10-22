package io.terrakube.api.plugin.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * Proves the SQL semantics of the FIFO dispatch-ordering queries against a real Postgres instance
 * (native queries, not exercised by mocked-repository unit tests elsewhere). {@code @Transactional}
 * rolls back each test's writes since the {@code @Container} instance is shared across the class.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class JobDispatchOrderRepositoryIntegrationTest {

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    // Without this, ExecutorAvailabilityListener's @PostConstruct subscribe() forces the real
    // container to connect to Redis on context startup - there's no Redis available here.
    @MockitoBean
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("terrakube")
            .withUsername("terrakube")
            .withPassword("terrakube");

    @DynamicPropertySource
    static void registerPostgreSQLProperties(DynamicPropertyRegistry registry) {
        registry.add("io.terrakube.api.plugin.datasource.type", () -> "POSTGRESQL");
        registry.add("io.terrakube.api.plugin.datasource.hostname", postgreSQLContainer::getHost);
        registry.add("io.terrakube.api.plugin.datasource.databasePort", () -> postgreSQLContainer.getMappedPort(5432).toString());
        registry.add("io.terrakube.api.plugin.datasource.databaseName", postgreSQLContainer::getDatabaseName);
        registry.add("io.terrakube.api.plugin.datasource.databaseUser", postgreSQLContainer::getUsername);
        registry.add("io.terrakube.api.plugin.datasource.databasePassword", postgreSQLContainer::getPassword);
        // See RepoWebhookSyncCoalescingIntegrationTest for why this must be distinct: Quartz's
        // SchedulerFactoryBean registers its DataSource in a JVM-wide static registry keyed by
        // scheduler instance name, which otherwise clobbers the shared default test context's entry.
        registry.add("io.terrakube.api.plugin.scheduler.instanceName", () -> "jobDispatchOrderRepositoryIT");
    }

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private StepRepository stepRepository;

    private Organization organization;

    @BeforeEach
    void setup() {
        // organization/workspace name columns are varchar(32) - keep well under that.
        organization = new Organization();
        organization.setName("org-" + UUID.randomUUID().toString().substring(0, 8));
        organization = organizationRepository.save(organization);
    }

    private Workspace newWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setName("ws-" + UUID.randomUUID().toString().substring(0, 8));
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.6.0");
        workspace.setOrganization(organization);
        return workspaceRepository.save(workspace);
    }

    private Job newJob(Workspace workspace, JobStatus status) {
        Job job = new Job();
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job.setStatus(status);
        return jobRepository.save(job);
    }

    private Step newStep(Job job, int stepNumber, JobStatus status) {
        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(stepNumber);
        step.setStatus(status);
        return stepRepository.save(step);
    }

    @Test
    void theOldestPendingJobAcrossDifferentWorkspacesIsNextInLine() {
        Workspace workspaceA = newWorkspace();
        Workspace workspaceB = newWorkspace();
        Job older = newJob(workspaceA, JobStatus.pending);
        Job newer = newJob(workspaceB, JobStatus.pending);

        assertThat(jobRepository.isJobNextInDispatchOrder(older.getId())).isTrue();
        assertThat(jobRepository.isJobNextInDispatchOrder(newer.getId())).isFalse();
        assertThat(jobRepository.findNextDispatchableJobId()).isEqualTo(older.getId());
    }

    @Test
    void aJobBlockedByAnEarlierUnfinishedJobInTheSameWorkspaceIsSkippedInFavorOfAnotherWorkspace() {
        Workspace workspaceA = newWorkspace();
        Workspace workspaceB = newWorkspace();
        Job stillRunningInA = newJob(workspaceA, JobStatus.running);
        Job pendingInA = newJob(workspaceA, JobStatus.pending); // blocked by stillRunningInA, same workspace
        Job pendingInB = newJob(workspaceB, JobStatus.pending); // unrelated workspace, not blocked

        // isJobNextInDispatchOrder deliberately does not re-derive the candidate's own
        // workspace-blocking (ScheduleJob's existing findByWorkspaceAndStatusNotInAndIdLessThan
        // check already handles that before this method is ever called) - it only asks "is anyone
        // else more senior," and stillRunningInA (status=running) isn't itself pending/approved so
        // it doesn't count as one. findNextDispatchableJobId, below, is the one that accounts for
        // a candidate's own workspace-blocking.
        assertThat(jobRepository.isJobNextInDispatchOrder(pendingInA.getId())).isTrue();
        assertThat(jobRepository.isJobNextInDispatchOrder(pendingInB.getId())).isTrue();
        assertThat(jobRepository.findNextDispatchableJobId()).isEqualTo(pendingInB.getId());
        assertThat(stillRunningInA.getId()).isGreaterThan(0); // sanity: entity was actually persisted
    }

    @Test
    void aJobBlockedOnlyByATerminalEarlierJobInTheSameWorkspaceIsNotSkipped() {
        Workspace workspace = newWorkspace();
        Job earlierFailed = newJob(workspace, JobStatus.failed);
        Job pending = newJob(workspace, JobStatus.pending);

        assertThat(jobRepository.isJobNextInDispatchOrder(pending.getId())).isTrue();
        assertThat(jobRepository.findNextDispatchableJobId()).isEqualTo(pending.getId());
        assertThat(earlierFailed.getStatus()).isEqualTo(JobStatus.failed); // sanity
    }

    @Test
    void approvedJobsParticipateInTheSameOrderingAsPendingJobs() {
        Workspace workspace = newWorkspace();
        Job approved = newJob(workspace, JobStatus.approved);

        assertThat(jobRepository.isJobNextInDispatchOrder(approved.getId())).isTrue();
        assertThat(jobRepository.findNextDispatchableJobId()).isEqualTo(approved.getId());
    }

    @Test
    void findNextDispatchableJobIdReturnsNullWhenNothingIsEligible() {
        Workspace workspace = newWorkspace();
        newJob(workspace, JobStatus.completed);

        assertThat(jobRepository.findNextDispatchableJobId()).isNull();
    }

    @Test
    void guardedQuery_zombieApprovedJobWithNoPendingStepDoesNotBlockLaterJob() {
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        Job zombie = newJob(wsA, JobStatus.approved);           // older
        newStep(zombie, 100, JobStatus.completed);              // has a step, none pending
        Job later = newJob(wsB, JobStatus.pending);             // newer, different workspace
        newStep(later, 100, JobStatus.pending);                 // genuine executable work

        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(later.getId())).isTrue();
        // the un-guarded query still (wrongly) reports it blocked - proves the guard is the fix
        assertThat(jobRepository.isJobNextInDispatchOrder(later.getId())).isFalse();
    }

    @Test
    void guardedQuery_earlierPendingJobWithAPendingStepStillBlocks() {
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        Job earlier = newJob(wsA, JobStatus.pending);
        newStep(earlier, 100, JobStatus.pending);
        Job later = newJob(wsB, JobStatus.pending);
        newStep(later, 100, JobStatus.pending);

        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(later.getId())).isFalse();
    }

    @Test
    void guardedQuery_earlierUninitialisedPendingJobWithNoStepsStillBlocks() {
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        newJob(wsA, JobStatus.pending);                         // steps not created yet
        Job later = newJob(wsB, JobStatus.pending);
        newStep(later, 100, JobStatus.pending);

        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(later.getId())).isFalse();
    }

    @Test
    void guardedQuery_earlierRunningJobMidApplyStillBlocksItsWorkspaceSuccessor() {
        Workspace ws = newWorkspace();
        Job running = newJob(ws, JobStatus.running);            // executor owns it; step is running
        newStep(running, 100, JobStatus.running);
        Job later = newJob(ws, JobStatus.pending);
        newStep(later, 100, JobStatus.pending);

        // the running job is the only workspace member with a lower id and it still blocks,
        // so nothing in this workspace is eligible - later must not jump ahead of it
        assertThat(jobRepository.findNextDispatchableExecutableJobId()).isNull();
    }

    @Test
    void guardedQuery_fifoPreservedAmongGenuinelyEligibleJobs() {
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        Job first = newJob(wsA, JobStatus.pending);
        newStep(first, 100, JobStatus.pending);
        Job second = newJob(wsB, JobStatus.approved);
        newStep(second, 100, JobStatus.pending);

        assertThat(jobRepository.findNextDispatchableExecutableJobId()).isEqualTo(first.getId());
        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(second.getId())).isFalse();
    }

    @Test
    void guardedQuery_earlierPendingJobInDeletedWorkspaceDoesNotBlockLaterJob() {
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        Job earlier = newJob(wsA, JobStatus.pending);
        newStep(earlier, 100, JobStatus.pending);
        Job later = newJob(wsB, JobStatus.pending);
        newStep(later, 100, JobStatus.pending);

        wsA.setDeleted(true);
        workspaceRepository.save(wsA);

        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(later.getId())).isTrue();
        assertThat(jobRepository.isJobNextInDispatchOrder(later.getId())).isTrue();
        assertThat(jobRepository.findNextDispatchableExecutableJobId()).isEqualTo(later.getId());
        assertThat(jobRepository.findNextDispatchableJobId()).isEqualTo(later.getId());
    }

    // --- Dispatch-retry-aware variants (issues #3665/#3666) ---------------------------------

    @Test
    void notDeferredQuery_earlierJobInBackoffDoesNotBlockALaterJobInADifferentWorkspace() {
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        Job inBackoff = newJob(wsA, JobStatus.pending);
        newStep(inBackoff, 100, JobStatus.pending);
        inBackoff.setDispatchNextRetryAt(new Date(System.currentTimeMillis() + 60_000));
        jobRepository.save(inBackoff);
        Job later = newJob(wsB, JobStatus.pending);
        newStep(later, 100, JobStatus.pending);

        // The plain guarded (step-aware) query still (correctly, for its own purpose) reports
        // the backoff job as blocking - it has a pending step - proving the new predicate, not
        // the existing guard, is what makes the later job eligible here.
        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(later.getId())).isFalse();
        assertThat(jobRepository.isJobNextInDispatchOrderExecutableNotDeferred(later.getId())).isTrue();
        assertThat(jobRepository.findNextDispatchableExecutableNotDeferredJobId()).isEqualTo(later.getId());
    }

    @Test
    void notDeferredQuery_earlierJobInBackoffStillBlocksALaterJobInTheSameWorkspace() {
        Workspace workspace = newWorkspace();
        Job inBackoff = newJob(workspace, JobStatus.pending);
        newStep(inBackoff, 100, JobStatus.pending);
        inBackoff.setDispatchNextRetryAt(new Date(System.currentTimeMillis() + 60_000));
        jobRepository.save(inBackoff);
        Job later = newJob(workspace, JobStatus.pending);
        newStep(later, 100, JobStatus.pending);

        // Same-workspace ordering must survive regardless of backoff state - the per-workspace
        // blocker subquery deliberately never looks at dispatchNextRetryAt.
        assertThat(jobRepository.isJobNextInDispatchOrderExecutableNotDeferred(later.getId())).isFalse();
        assertThat(jobRepository.findNextDispatchableExecutableNotDeferredJobId()).isNull();
    }

    @Test
    void notDeferredQuery_nullNextRetryAtIsTreatedAsEligible() {
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        Job neverFailed = newJob(wsA, JobStatus.pending); // dispatchNextRetryAt left null
        newStep(neverFailed, 100, JobStatus.pending);
        Job later = newJob(wsB, JobStatus.pending);
        newStep(later, 100, JobStatus.pending);

        assertThat(jobRepository.isJobNextInDispatchOrderExecutableNotDeferred(later.getId())).isFalse();
        assertThat(jobRepository.findNextDispatchableExecutableNotDeferredJobId()).isEqualTo(neverFailed.getId());
    }

    @Test
    void notDeferredQuery_expiredBackoffIsTreatedAsEligibleAgain() {
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        Job backoffExpired = newJob(wsA, JobStatus.pending);
        newStep(backoffExpired, 100, JobStatus.pending);
        backoffExpired.setDispatchNextRetryAt(new Date(System.currentTimeMillis() - 60_000));
        jobRepository.save(backoffExpired);
        Job later = newJob(wsB, JobStatus.pending);
        newStep(later, 100, JobStatus.pending);

        // The deadline has passed - the earlier job is a normal eligible candidate again and
        // blocks the later job in a different workspace, same as the non-deferral-aware query.
        assertThat(jobRepository.isJobNextInDispatchOrderExecutableNotDeferred(later.getId())).isFalse();
        assertThat(jobRepository.findNextDispatchableExecutableNotDeferredJobId()).isEqualTo(backoffExpired.getId());
    }

    @Test
    void countDispatchEligibleNotDeferredJobsExcludesOnlyJobsCurrentlyInBackoff() {
        Workspace wsA = newWorkspace();
        Workspace wsB = newWorkspace();
        Job eligible = newJob(wsA, JobStatus.pending);
        newStep(eligible, 100, JobStatus.pending);
        Job inBackoff = newJob(wsB, JobStatus.pending);
        newStep(inBackoff, 100, JobStatus.pending);
        inBackoff.setDispatchNextRetryAt(new Date(System.currentTimeMillis() + 60_000));
        jobRepository.save(inBackoff);

        assertThat(jobRepository.countDispatchEligibleJobs()).isEqualTo(2);
        assertThat(jobRepository.countDispatchEligibleNotDeferredJobs()).isEqualTo(1);
    }

    // --- Locked workspaces (#3728) ---------------------------------------------------------

    private Workspace lock(Workspace workspace, String description) {
        workspace.setLocked(true);
        workspace.setLockDescription(description);
        return workspaceRepository.saveAndFlush(workspace);
    }

    @Test
    void aPendingJobInALockedWorkspaceDoesNotBlockJobsInOtherWorkspaces() {
        Workspace locked = lock(newWorkspace(), "manual lock");
        Workspace other = newWorkspace();
        Job inLocked = newJob(locked, JobStatus.pending);
        Job inOther = newJob(other, JobStatus.pending);

        assertThat(jobRepository.isJobNextInDispatchOrder(inOther.getId())).isTrue();
        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(inOther.getId())).isTrue();
        assertThat(jobRepository.isJobNextInDispatchOrderExecutableNotDeferred(inOther.getId())).isTrue();
        assertThat(jobRepository.findNextDispatchableJobId()).isEqualTo(inOther.getId());
        assertThat(jobRepository.findNextDispatchableExecutableJobId()).isEqualTo(inOther.getId());
        assertThat(jobRepository.findNextDispatchableExecutableNotDeferredJobId()).isEqualTo(inOther.getId());
        assertThat(jobRepository.countDispatchEligibleJobs()).isEqualTo(1);
        assertThat(jobRepository.countDispatchEligibleNotDeferredJobs()).isEqualTo(1);
        assertThat(inLocked.getId()).isLessThan(inOther.getId());
    }

    @Test
    void unlockingAWorkspaceRestoresItsOriginalPlaceInTheQueue() {
        Workspace workspace = lock(newWorkspace(), "manual lock");
        Workspace other = newWorkspace();
        Job inWorkspace = newJob(workspace, JobStatus.pending);
        Job inOther = newJob(other, JobStatus.pending);

        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(inOther.getId())).isTrue();

        workspace.setLocked(false);
        workspace.setLockDescription(null);
        workspaceRepository.saveAndFlush(workspace);

        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(inOther.getId())).isFalse();
        assertThat(jobRepository.findNextDispatchableExecutableJobId()).isEqualTo(inWorkspace.getId());
    }

    @Test
    void aJobWhoseWorkspaceWasLockedByItsOwnPrApplyStaysEligible() {
        Workspace workspace = lock(newWorkspace(), WebhookService.buildPrApplyLockDescription(42));
        Workspace other = newWorkspace();
        Job prApply = newJob(workspace, JobStatus.pending);
        prApply.setAutoApply(true);
        prApply.setPrNumber(42);
        jobRepository.saveAndFlush(prApply);
        Job inOther = newJob(other, JobStatus.pending);

        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(inOther.getId())).isFalse();
        assertThat(jobRepository.findNextDispatchableExecutableJobId()).isEqualTo(prApply.getId());
    }

    @Test
    void aPrApplyJobDoesNotGetTheExceptionWhenTheLockIsSomeoneElses() {
        Workspace workspace = lock(newWorkspace(), "Locked by PR #7 apply");
        Workspace other = newWorkspace();
        Job prApply = newJob(workspace, JobStatus.pending);
        prApply.setAutoApply(true);
        prApply.setPrNumber(42); // lock belongs to PR 7, not this job
        jobRepository.saveAndFlush(prApply);
        Job inOther = newJob(other, JobStatus.pending);

        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(inOther.getId())).isTrue();
        assertThat(jobRepository.findNextDispatchableExecutableJobId()).isEqualTo(inOther.getId());
    }

    @Test
    void countsOnlyJobsHeldOutOfTheQueueByALockedWorkspace() {
        Workspace locked = lock(newWorkspace(), "manual lock");
        Workspace prLocked = lock(newWorkspace(), WebhookService.buildPrApplyLockDescription(9));
        Workspace other = newWorkspace();
        newJob(locked, JobStatus.pending);
        newJob(locked, JobStatus.approved);
        newJob(locked, JobStatus.running); // not pending/approved, not counted
        Job prApply = newJob(prLocked, JobStatus.pending);
        prApply.setAutoApply(true);
        prApply.setPrNumber(9);
        jobRepository.saveAndFlush(prApply); // own PR apply lock, not held out
        newJob(other, JobStatus.pending);

        assertThat(jobRepository.countJobsHeldByLockedWorkspace()).isEqualTo(2);
    }

    @Test
    void doesNotCountALockedJobWithNoExecutableStep() {
        Workspace locked = lock(newWorkspace(), "manual lock");
        Job zombie = newJob(locked, JobStatus.approved);
        newStep(zombie, 100, JobStatus.completed); // unlocking would not make it dispatchable
        Job genuine = newJob(locked, JobStatus.pending);
        newStep(genuine, 100, JobStatus.pending);

        assertThat(jobRepository.countJobsHeldByLockedWorkspace()).isEqualTo(1);
    }

    // --- Stale workspace status (#3727) ----------------------------------------------------

    private Workspace workspaceShowing(JobStatus lastJobStatus) {
        Workspace workspace = newWorkspace();
        workspace.setLastJobStatus(lastJobStatus);
        return workspaceRepository.saveAndFlush(workspace);
    }

    private static final java.util.List<JobStatus> ACTIVE = java.util.List.of(JobStatus.pending, JobStatus.approved,
            JobStatus.waitingApproval, JobStatus.queue, JobStatus.running);

    private java.util.List<UUID> staleIds() {
        return workspaceRepository.findWithStaleActiveJobStatus(ACTIVE, org.springframework.data.domain.PageRequest.of(0, 100))
                .stream().map(Workspace::getId).toList();
    }

    @Test
    void aWorkspaceShowingWaitingApprovalWithOnlyFinishedRunsIsStale() {
        Workspace stale = workspaceShowing(JobStatus.waitingApproval);
        newJob(stale, JobStatus.completed);
        newJob(stale, JobStatus.failed);

        assertThat(staleIds()).contains(stale.getId());
    }

    @Test
    void aWorkspaceShowingAnActiveStatusWithNoRunsAtAllIsStale() {
        Workspace stale = workspaceShowing(JobStatus.running);

        assertThat(staleIds()).contains(stale.getId());
    }

    @Test
    void aWorkspaceWithAnActiveRunIsNotStaleWhateverItsLastStatusSays() {
        Workspace hasWaiting = workspaceShowing(JobStatus.pending);
        newJob(hasWaiting, JobStatus.waitingApproval);
        newJob(hasWaiting, JobStatus.pending);

        assertThat(staleIds()).doesNotContain(hasWaiting.getId());
    }

    @Test
    void aWorkspaceShowingAFinishedStatusIsNeverReportedAsStale() {
        Workspace finished = workspaceShowing(JobStatus.failed);
        newJob(finished, JobStatus.failed);
        Workspace neverRun = workspaceShowing(JobStatus.NeverExecuted);

        assertThat(staleIds()).doesNotContain(finished.getId(), neverRun.getId());
    }

    @Test
    void aDeletedWorkspaceIsNotReportedAsStale() {
        Workspace deleted = workspaceShowing(JobStatus.waitingApproval);
        deleted.setDeleted(true);
        workspaceRepository.saveAndFlush(deleted);

        assertThat(staleIds()).doesNotContain(deleted.getId());
    }

    // The reproduction from #3727, against a real database.
    @Autowired
    private io.terrakube.api.plugin.scheduler.inactive.InactiveJobs inactiveJobs;
    @Autowired
    private io.terrakube.api.plugin.scheduler.inactive.InactiveJobsProperties inactiveJobsProperties;

    private void runInactiveJobs() throws Exception {
        org.quartz.JobExecutionContext context = org.mockito.Mockito.mock(org.quartz.JobExecutionContext.class);
        org.mockito.Mockito.doReturn(org.mockito.Mockito.mock(org.quartz.Scheduler.class)).when(context).getScheduler();
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, Object> valueOperations =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.doReturn(valueOperations).when(redisTemplate).opsForValue();
        org.mockito.Mockito.doReturn(true).when(valueOperations)
                .setIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        inactiveJobs.execute(context);
    }

    private Job oldJob(Workspace workspace, JobStatus status, int hoursOld) {
        Job job = newJob(workspace, status);
        jobRepository.flush();
        java.sql.Timestamp created = new java.sql.Timestamp(System.currentTimeMillis() - hoursOld * 3_600_000L);
        jdbcTemplate.update("update job set created_date = ?, updated_date = ? where id = ?", created, created, job.getId());
        jobRepository.flush();
        entityManager.clear();
        return jobRepository.findById(job.getId()).orElseThrow();
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void aRunWaitingForApprovalForSevenHoursIsNotClosedByDefault() throws Exception {
        Workspace workspace = workspaceShowing(JobStatus.waitingApproval);
        Job job = oldJob(workspace, JobStatus.waitingApproval, 7);

        runInactiveJobs();

        entityManager.flush();
        entityManager.clear();
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.waitingApproval);
        assertThat(workspaceRepository.findById(workspace.getId()).orElseThrow().getLastJobStatus())
                .isEqualTo(JobStatus.waitingApproval);
    }

    @Test
    void aRunClosedByTheLimitUpdatesTheWorkspaceAndRecordsWhy() throws Exception {
        Workspace workspace = workspaceShowing(JobStatus.running);
        Job job = oldJob(workspace, JobStatus.running, 7);

        runInactiveJobs();

        entityManager.flush();
        entityManager.clear();
        Job closed = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(JobStatus.failed);
        assertThat(closed.getOutput()).contains("Closed by Terrakube: The run did not finish within 6 hours");
        Workspace reloaded = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(reloaded.getLastJobStatus()).isEqualTo(JobStatus.failed);
        assertThat(reloaded.getLastJobDate()).isNotNull();
    }

    @Test
    void anApprovalClosedByTheOptInTimeoutCancelsTheRunAndUpdatesTheWorkspace() throws Exception {
        inactiveJobsProperties.setWaitingApprovalTimeoutHours(48);
        try {
            Workspace workspace = workspaceShowing(JobStatus.waitingApproval);
            Job job = oldJob(workspace, JobStatus.waitingApproval, 72);

            runInactiveJobs();

            entityManager.flush();
            entityManager.clear();
            assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.cancelled);
            assertThat(workspaceRepository.findById(workspace.getId()).orElseThrow().getLastJobStatus())
                    .isEqualTo(JobStatus.cancelled);
        } finally {
            inactiveJobsProperties.setWaitingApprovalTimeoutHours(0);
        }
    }

    // A run behind an unfinished one has not started: it is not closed because it is old, and it gets its
    // full time once the run in front of it has ended.
    @Test
    void aRunQueuedBehindAWaitingApprovalIsNotClosedHoweverOldItIs() throws Exception {
        Workspace workspace = workspaceShowing(JobStatus.waitingApproval);
        oldJob(workspace, JobStatus.waitingApproval, 31);
        Job behind = oldJob(workspace, JobStatus.queue, 30);

        runInactiveJobs();

        entityManager.flush();
        entityManager.clear();
        assertThat(jobRepository.findById(behind.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.queue);
    }

    @Test
    void aRunIsNotClosedTheMomentTheRunInFrontOfItEnds() throws Exception {
        Workspace workspace = workspaceShowing(JobStatus.pending);
        Job inFront = oldJob(workspace, JobStatus.completed, 31);
        Job behind = oldJob(workspace, JobStatus.pending, 30);
        jdbcTemplate.update("update job set updated_date = ? where id = ?",
                new java.sql.Timestamp(System.currentTimeMillis() - 3_600_000L), inFront.getId());
        entityManager.clear();

        runInactiveJobs();

        entityManager.flush();
        entityManager.clear();
        assertThat(jobRepository.findById(behind.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.pending);
    }

    // --- The conditional repair of a stale workspace status (#3727) ------------------------------

    private Workspace reload(Workspace workspace) {
        entityManager.flush();
        entityManager.clear();
        return workspaceRepository.findById(workspace.getId()).orElseThrow();
    }

    @Test
    void theRepairCorrectsOnlyTheStatusAndDateOfAStaleWorkspace() {
        Workspace stale = workspaceShowing(JobStatus.waitingApproval);
        newJob(stale, JobStatus.failed);
        Date when = new Date(System.currentTimeMillis() - 60_000);
        String name = stale.getName();

        int repaired = workspaceRepository.repairStaleJobStatus(
                stale.getId(), JobStatus.waitingApproval, JobStatus.failed, when, ACTIVE);

        assertThat(repaired).isEqualTo(1);
        Workspace reloaded = reload(stale);
        assertThat(reloaded.getLastJobStatus()).isEqualTo(JobStatus.failed);
        assertThat(reloaded.getName()).isEqualTo(name);
    }

    @Test
    void theRepairDoesNothingWhenARunBecameActiveInTheMeantime() {
        Workspace workspace = workspaceShowing(JobStatus.waitingApproval);
        newJob(workspace, JobStatus.failed);
        newJob(workspace, JobStatus.pending); // created after the workspace was listed as stale

        int repaired = workspaceRepository.repairStaleJobStatus(
                workspace.getId(), JobStatus.waitingApproval, JobStatus.failed, new Date(), ACTIVE);

        assertThat(repaired).isZero();
        assertThat(reload(workspace).getLastJobStatus()).isEqualTo(JobStatus.waitingApproval);
    }

    @Test
    void theRepairDoesNothingWhenTheStatusIsNoLongerTheOneThatWasSeen() {
        Workspace workspace = workspaceShowing(JobStatus.pending);
        newJob(workspace, JobStatus.failed);

        int repaired = workspaceRepository.repairStaleJobStatus(
                workspace.getId(), JobStatus.waitingApproval, JobStatus.failed, new Date(), ACTIVE);

        assertThat(repaired).isZero();
        assertThat(reload(workspace).getLastJobStatus()).isEqualTo(JobStatus.pending);
    }

    @Test
    void staleWorkspacesAreListedInAStableOrder() {
        Workspace a = workspaceShowing(JobStatus.running);
        Workspace b = workspaceShowing(JobStatus.running);

        java.util.List<UUID> ids = staleIds();

        // the id is stored as text, so that is the order it is sorted in
        assertThat(ids.stream().map(UUID::toString).toList()).isSorted();
        assertThat(ids).contains(a.getId(), b.getId());
    }
}
