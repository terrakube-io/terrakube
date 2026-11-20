package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

// The workspace page includes every job, and Elide reads each job's step, address and policyEvaluation linkage.
// Unbatched, that is one statement per job per collection, so the number of statements must not grow with the jobs.
class WorkspaceJobsQueryCountTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final List<Workspace> workspaces = new ArrayList<>();

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void cleanup() {
        for (Workspace workspace : workspaces) {
            for (Job job : jobRepository.findAllByWorkspaceAndStatusInOrderByIdDesc(workspace, List.of(JobStatus.completed))) {
                stepRepository.deleteAll(stepRepository.findByJobId(job.getId()));
                jobRepository.delete(job);
            }
            workspaceRepository.delete(workspace);
        }
    }

    private Workspace seedWorkspace(int jobs) {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        Workspace workspace = new Workspace();
        workspace.setName("batch-fetch-" + jobs + "-" + UUID.randomUUID());
        workspace.setSource("empty");
        workspace.setBranch("remote-content");
        workspace.setTerraformVersion("1.14.0");
        workspace.setOrganization(organization);
        workspace = workspaceRepository.save(workspace);
        workspaces.add(workspace);
        for (int i = 0; i < jobs; i++) {
            Job job = new Job();
            job.setOrganization(organization);
            job.setWorkspace(workspace);
            // Finished, so the scheduler leaves it alone while statements are counted.
            job.setStatus(JobStatus.completed);
            job = jobRepository.save(job);
            Step step = new Step();
            step.setJob(job);
            step.setName("terraformPlan");
            step.setStatus(JobStatus.completed);
            step.setStepNumber(100);
            stepRepository.save(step);
        }
        return workspace;
    }

    private long statements(Workspace workspace, int jobs) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                    .when()
                    .get("/api/v1/organization/" + ORGANIZATION_ID + "/workspace/" + workspace.getId() + "?include=job")
                    .then()
                    .log()
                    .ifValidationFails()
                    .statusCode(HttpStatus.OK.value())
                    .body("included", hasSize(jobs));
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void workspaceWithJobsDoesNotGrowWithJobCount() {
        long five = statements(seedWorkspace(5), 5);
        long twenty = statements(seedWorkspace(20), 20);

        assertTrue(twenty - five <= 2, "5 jobs ran " + five + " statements, 20 jobs ran " + twenty);
    }

    @Test
    void batchFetchSizeDefaultsToOneHundred() {
        int size = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getSessionFactoryOptions()
                .getDefaultBatchFetchSize();

        assertEquals(100, size);
    }
}
