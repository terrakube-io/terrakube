package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two threads reconcile the same stuck job at once. The pessimistic row lock in
 * {@link JobReconciliationService#reconcile} plus the idempotent deriver must give exactly one
 * committed transition and one APPLIED disposition; the loser sees the terminal state and returns
 * ALREADY_TERMINAL. Not {@code @Transactional}: the threads need each other's commits to be visible.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class JobReconciliationConcurrencyIntegrationTest {

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
        registry.add("io.terrakube.api.plugin.scheduler.instanceName", () -> "jobReconciliationConcurrencyIT");
    }

    @Autowired
    private JobReconciliationService reconciliationService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private StepRepository stepRepository;
    @Autowired
    private OrganizationRepository organizationRepository;

    private Organization organization;

    @BeforeEach
    void setup() {
        organization = new Organization();
        organization.setName("org-" + UUID.randomUUID().toString().substring(0, 8));
        organization = organizationRepository.save(organization);
    }

    private int createApprovedZombie() {
        Workspace workspace = new Workspace();
        workspace.setName("ws-" + UUID.randomUUID().toString().substring(0, 8));
        workspace.setSource("https://github.com/example/repo.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.6.0");
        workspace.setOrganization(organization);
        workspace = workspaceRepository.save(workspace);

        Job job = new Job();
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job.setStatus(JobStatus.approved);
        job = jobRepository.save(job);

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(100);
        step.setStatus(JobStatus.completed);
        stepRepository.save(step);
        return job.getId();
    }

    @Test
    void twoConcurrentReconcileCallsProduceExactlyOneTransition() throws Exception {
        int jobId = createApprovedZombie();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ReconciliationResult> a = pool.submit(() -> {
            start.await();
            return reconciliationService.reconcile(jobId, false);
        });
        Future<ReconciliationResult> b = pool.submit(() -> {
            start.await();
            return reconciliationService.reconcile(jobId, false);
        });
        start.countDown();

        List<ReconciliationResult> results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        pool.shutdown();

        long applied = results.stream()
                .filter(r -> r.disposition() == ReconciliationResult.ReconciliationDisposition.APPLIED)
                .count();
        long alreadyTerminal = results.stream()
                .filter(r -> r.disposition() == ReconciliationResult.ReconciliationDisposition.ALREADY_TERMINAL)
                .count();

        assertThat(applied).isEqualTo(1);
        assertThat(alreadyTerminal).isEqualTo(1);
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.completed);
    }
}
