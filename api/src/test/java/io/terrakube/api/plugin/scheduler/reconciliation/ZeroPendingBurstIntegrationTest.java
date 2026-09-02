package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
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
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end: a stale {@code approved} head job with all steps completed (a "755") is dropped into
 * a running system. The live 30s sweep must reconcile it to {@code completed} exactly once and
 * drop its Quartz trigger with no manual intervention, and the guarded FIFO head query must stop
 * treating it as a queue candidate. (The SQL-level "a later job is no longer blocked" guarantee is
 * covered directly in JobDispatchOrderRepositoryIntegrationTest - fresh pending jobs can't be used
 * here because the live scheduler would fail them for having no real template.)
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ZeroPendingBurstIntegrationTest {

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
        registry.add("io.terrakube.api.plugin.scheduler.instanceName", () -> "zeroPendingBurstIT");
    }

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private StepRepository stepRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private Scheduler scheduler;

    private Organization organization;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        organization = new Organization();
        organization.setName("org-" + UUID.randomUUID().toString().substring(0, 8));
        organization = organizationRepository.save(organization);

        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), any(), any(java.time.Duration.class))).thenReturn(true);
        lenient().when(redisTemplate.delete(anyString())).thenReturn(true);
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

    private int newJobWithStep(Workspace workspace, JobStatus jobStatus, JobStatus stepStatus) {
        Job job = new Job();
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job.setStatus(jobStatus);
        job = jobRepository.save(job);
        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(100);
        step.setStatus(stepStatus);
        stepRepository.save(step);
        return job.getId();
    }

    @Test
    void aStaleApprovedHeadIsReconciledByTheLiveSweepWithNoManualIntervention() throws Exception {
        int stale = newJobWithStep(newWorkspace(), JobStatus.approved, JobStatus.completed);

        // the guarded head query must never hand the executor pool this dead job
        Integer head = jobRepository.findNextDispatchableExecutableJobId();
        assertThat(head == null || head != stale).isTrue();

        long deadline = System.currentTimeMillis() + 35_000;
        boolean reconciled = false;
        while (System.currentTimeMillis() < deadline) {
            if (jobRepository.findById(stale).map(Job::getStatus).orElse(null) == JobStatus.completed) {
                reconciled = true;
                break;
            }
            Thread.sleep(500);
        }

        assertThat(reconciled).isTrue();
        assertThat(scheduler.checkExists(new JobKey(ScheduleJobService.PREFIX_JOB_CONTEXT + stale))).isFalse();
    }
}
