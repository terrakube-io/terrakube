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
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
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
}
