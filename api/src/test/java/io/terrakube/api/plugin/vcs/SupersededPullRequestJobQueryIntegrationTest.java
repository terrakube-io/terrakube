package io.terrakube.api.plugin.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * The SQL of the runs a new pull request run supersedes, against a real Postgres (and with the Liquibase
 * changelog applied). {@code @Transactional} rolls back each test's writes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class SupersededPullRequestJobQueryIntegrationTest {

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

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
        // Distinct scheduler instance name, see JobDispatchOrderRepositoryIntegrationTest.
        registry.add("io.terrakube.api.plugin.scheduler.instanceName", () -> "supersededPullRequestJobQueryIT");
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

    private Job newJob(Workspace workspace, String branch, JobStatus status) {
        Job job = new Job();
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job.setOverrideBranch(branch);
        job.setStatus(status);
        return jobRepository.save(job);
    }

    private List<Integer> superseded(Workspace workspace, String branch, Job newJob) {
        return jobRepository
                .findByWorkspaceAndOverrideBranchAndIdLessThanAndStatusInOrderByIdAsc(workspace, branch,
                        newJob.getId(), SupersededPullRequestJobTransactions.NOT_STARTED)
                .stream()
                .map(Job::getId)
                .toList();
    }

    @Test
    void findsOnlyOlderNotStartedRunsOfTheSameWorkspaceAndBranch() {
        Workspace workspace = newWorkspace();
        Workspace otherWorkspace = newWorkspace();
        Job pending = newJob(workspace, "feature/login", JobStatus.pending);
        Job waitingApproval = newJob(workspace, "feature/login", JobStatus.waitingApproval);
        Job approved = newJob(workspace, "feature/login", JobStatus.approved);
        newJob(workspace, "feature/login", JobStatus.queue);
        newJob(workspace, "feature/login", JobStatus.running);
        newJob(workspace, "feature/login", JobStatus.completed);
        newJob(workspace, "feature/other", JobStatus.pending);
        newJob(workspace, null, JobStatus.pending);
        newJob(otherWorkspace, "feature/login", JobStatus.pending);
        Job newRun = newJob(workspace, "feature/login", JobStatus.pending);
        newJob(workspace, "feature/login", JobStatus.pending);

        assertThat(superseded(workspace, "feature/login", newRun))
                .containsExactly(pending.getId(), waitingApproval.getId(), approved.getId());
    }

    @Test
    void theCancelLeavesARunThatAlreadyStartedAlone() {
        Workspace workspace = newWorkspace();
        Job queued = newJob(workspace, "feature/login", JobStatus.queue);
        Job pending = newJob(workspace, "feature/login", JobStatus.pending);

        assertThat(jobRepository.updateStatusByIdAndStatusIn(JobStatus.cancelled, queued.getId(),
                SupersededPullRequestJobTransactions.NOT_STARTED)).isZero();
        assertThat(jobRepository.updateStatusByIdAndStatusIn(JobStatus.cancelled, pending.getId(),
                SupersededPullRequestJobTransactions.NOT_STARTED)).isEqualTo(1);
    }
}
