package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

// Job cannot be paged through Elide, so the workspace page limits what it reads of a workspace's jobs with a window on
// createdDate in the filter. This runs that filter through the real endpoint, where a wrong one comes back as an error.
class JobWindowQueryTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    private static final String QUERY = """
            query ($orgId: String!, $filter: String) {
              organization(ids: [$orgId]) { edges { node {
                job(sort: "-createdDate", filter: $filter) { edges { node { id } } }
              } } }
            }""";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Integer> jobIds = new ArrayList<>();

    @AfterEach
    void removeJobs() {
        for (Integer id : jobIds) {
            jdbcTemplate.update("delete from job where id = ?", id);
        }
    }

    private int job(Instant createdAt, String driftResult) {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        Workspace workspace = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
        Job job = new Job();
        job.setStatus(JobStatus.completed);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(job);
        jdbcTemplate.update("update job set created_date = ?, drift_result = ? where id = ?",
                java.sql.Timestamp.from(createdAt), driftResult, job.getId());
        jobIds.add(job.getId());
        return job.getId();
    }

    private io.restassured.response.ValidatableResponse jobs(String filter) {
        return given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .contentType("application/json")
                .body(Map.of("query", QUERY, "variables", Map.of("orgId", ORGANIZATION_ID, "filter", filter)))
                .when()
                .post("/graphql/api/v1")
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200)
                .body("$", not(hasKey("errors")));
    }

    @Test
    void aWindowOnCreatedDateKeepsOnlyTheRecentJobsOfAWorkspaceNewestFirst() {
        int old = job(Instant.now().minus(200, ChronoUnit.DAYS), null);
        int recentA = job(Instant.now().minus(3, ChronoUnit.DAYS), null);
        int recentB = job(Instant.now().minus(1, ChronoUnit.DAYS), null);
        String since = Instant.now().minus(90, ChronoUnit.DAYS).toString();

        // Other tests leave recent jobs on this workspace too, so only the ones made here are looked at.
        jobs("workspace.id=='" + WORKSPACE_ID + "';createdDate=ge=" + since)
                .body("data.organization.edges[0].node.job.edges.node.id",
                        hasItems(String.valueOf(recentB), String.valueOf(recentA)))
                .body("data.organization.edges[0].node.job.edges.node.id",
                        containsInRelativeOrder(String.valueOf(recentB), String.valueOf(recentA)))
                .body("data.organization.edges[0].node.job.edges.node.id", not(hasItem(String.valueOf(old))));
    }

    @Test
    void theWindowCombinesWithTheDriftCheckFilter() {
        int oldDrift = job(Instant.now().minus(200, ChronoUnit.DAYS), "drift");
        int recentDrift = job(Instant.now().minus(2, ChronoUnit.DAYS), "drift");
        int notADriftCheck = job(Instant.now().minus(1, ChronoUnit.DAYS), null);
        String since = Instant.now().minus(120, ChronoUnit.DAYS).toString();

        jobs("workspace.id=='" + WORKSPACE_ID + "';driftResult=isnull=false;createdDate=ge=" + since)
                .body("data.organization.edges[0].node.job.edges.node.id", hasItem(String.valueOf(recentDrift)))
                .body("data.organization.edges[0].node.job.edges.node.id", not(hasItem(String.valueOf(oldDrift))))
                .body("data.organization.edges[0].node.job.edges.node.id", not(hasItem(String.valueOf(notADriftCheck))));
    }
}
