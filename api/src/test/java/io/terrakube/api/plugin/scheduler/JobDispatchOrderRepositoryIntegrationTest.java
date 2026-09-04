package io.terrakube.api.plugin.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

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
}
