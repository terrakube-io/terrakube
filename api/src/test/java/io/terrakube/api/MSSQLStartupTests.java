package io.terrakube.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

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
import org.testcontainers.containers.MSSQLServerContainer;
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

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class MSSQLStartupTests {

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockitoBean
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @Container
    private static final MSSQLServerContainer<?> mssqlServerContainer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();

    @DynamicPropertySource
    static void registerMSSQLProperties(DynamicPropertyRegistry registry) {
        String host = mssqlServerContainer.getHost();
        Integer port = mssqlServerContainer.getMappedPort(1433);
        String user = mssqlServerContainer.getUsername();
        String password = mssqlServerContainer.getPassword();
        String databaseName = "master";

        registry.add("io.terrakube.api.plugin.datasource.type", () -> "SQL_AZURE");
        registry.add("io.terrakube.api.plugin.datasource.hostname", () -> host);
        registry.add("io.terrakube.api.plugin.datasource.databasePort", () -> port.toString());
        registry.add("io.terrakube.api.plugin.datasource.databaseUser", () -> user);
        registry.add("io.terrakube.api.plugin.datasource.databaseName", () -> databaseName);
        registry.add("io.terrakube.api.plugin.datasource.databasePassword", () -> password);
        registry.add("io.terrakube.api.plugin.datasource.trustCertificate", () -> "true");
        registry.add("io.terrakube.api.plugin.datasource.databaseSchema", () -> "dbo");
        registry.add("spring.liquibase.default-schema", () -> "dbo");
        registry.add("spring.liquibase.liquibase-schema", () -> "dbo");
        registry.add("io.terrakube.api.plugin.scheduler.instanceName", () -> "mssqlJobDispatchOrderIT");
    }

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private StepRepository stepRepository;

    @Test
    void contextLoads() {
        assertTrue(mssqlServerContainer.isRunning());
    }

    @Test
    void fifoDispatchQueriesExecuteSuccessfullyOnSqlServer() {
        Organization organization = new Organization();
        organization.setName("org-" + UUID.randomUUID().toString().substring(0, 8));
        organization = organizationRepository.save(organization);

        Workspace workspaceA = new Workspace();
        workspaceA.setName("ws-" + UUID.randomUUID().toString().substring(0, 8));
        workspaceA.setSource("https://github.com/example/repo.git");
        workspaceA.setBranch("main");
        workspaceA.setTerraformVersion("1.6.0");
        workspaceA.setOrganization(organization);
        workspaceA = workspaceRepository.save(workspaceA);

        Workspace workspaceB = new Workspace();
        workspaceB.setName("ws-" + UUID.randomUUID().toString().substring(0, 8));
        workspaceB.setSource("https://github.com/example/repo.git");
        workspaceB.setBranch("main");
        workspaceB.setTerraformVersion("1.6.0");
        workspaceB.setOrganization(organization);
        workspaceB = workspaceRepository.save(workspaceB);

        Job older = new Job();
        older.setOrganization(organization);
        older.setWorkspace(workspaceA);
        older.setStatus(JobStatus.pending);
        older = jobRepository.save(older);

        Step olderStep = new Step();
        olderStep.setJob(older);
        olderStep.setStepNumber(100);
        olderStep.setStatus(JobStatus.pending);
        stepRepository.save(olderStep);

        Job newer = new Job();
        newer.setOrganization(organization);
        newer.setWorkspace(workspaceB);
        newer.setStatus(JobStatus.pending);
        newer = jobRepository.save(newer);

        Step newerStep = new Step();
        newerStep.setJob(newer);
        newerStep.setStepNumber(100);
        newerStep.setStatus(JobStatus.pending);
        stepRepository.save(newerStep);

        // Verify unguarded and guarded queries on older job
        assertThat(jobRepository.isJobNextInDispatchOrder(older.getId())).isTrue();
        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(older.getId())).isTrue();

        // Verify newer job is blocked behind older job
        assertThat(jobRepository.isJobNextInDispatchOrder(newer.getId())).isFalse();
        assertThat(jobRepository.isJobNextInDispatchOrderExecutable(newer.getId())).isFalse();

        // Verify findNext queries return the older job
        assertThat(jobRepository.findNextDispatchableJobId()).isEqualTo(older.getId());
        assertThat(jobRepository.findNextDispatchableExecutableJobId()).isEqualTo(older.getId());

        // Verify queue depth count
        assertThat(jobRepository.countDispatchEligibleJobs()).isEqualTo(2);
    }
}

