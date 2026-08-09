package io.terrakube.api.plugin.scheduler.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
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

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * Proves the exact recovery this feature exists for: a job whose Quartz trigger is gone while
 * its DB status is still active gets a trigger recreated by the sweep and resumes.
 *
 * Deliberately not @Transactional: the sweep runs on its own Quartz thread with its own
 * transaction, so it can only see this test's job once that job's insert has actually
 * committed - a rollback-per-test wrapper would make it invisible the whole time. The
 * Testcontainers Postgres is thrown away after this class anyway, so no cleanup is needed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class JobReconciliationSweepIntegrationTest {

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
        registry.add("io.terrakube.api.plugin.scheduler.instanceName", () -> "jobReconciliationSweepIT");
    }

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private Scheduler scheduler;

    private Organization organization;

    @BeforeEach
    void setup() {
        organization = new Organization();
        organization.setName("org-" + UUID.randomUUID().toString().substring(0, 8));
        organization = organizationRepository.save(organization);
    }

    @Test
    void aJobWithNoQuartzTriggerGetsOneRecreatedByTheSweep() throws Exception {
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
        job.setStatus(JobStatus.pending);
        job = jobRepository.save(job);

        JobKey key = new JobKey(ScheduleJobService.PREFIX_JOB_CONTEXT + job.getId());
        assertThat(scheduler.checkExists(key)).isFalse();

        // Wait past one full sweep tick (registered at 30s intervals, plus the sweep's own
        // immediate run-once-at-startup already happened before this job existed).
        long deadline = System.currentTimeMillis() + 35_000;
        boolean recreated = false;
        while (System.currentTimeMillis() < deadline) {
            if (scheduler.checkExists(key)) {
                recreated = true;
                break;
            }
            Thread.sleep(500);
        }

        assertThat(recreated).isTrue();
    }
}
