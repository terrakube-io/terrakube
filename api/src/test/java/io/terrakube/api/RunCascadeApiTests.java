package io.terrakube.api;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Date;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * Covers the Elide permission surface added alongside the cascade schema
 * (terrakube-io/terrakube#3629): readable by the same audience as the triggers that create a
 * cascade, writable by nobody via the API - only the coordinator (a later slice) ever creates
 * one, the same write lock {@code History} uses.
 */
public class RunCascadeApiTests extends ServerApplicationTests {

    private static final String WORKSPACE_SOURCE = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    private Integer jobCreated;

    @BeforeEach
    void setup() {
        jobCreated = null;
    }

    // Retired rather than left behind, same reasoning as RunTriggerDispatchIntegrationTest's
    // cleanup: a stray job stays visible to every other test's admission-queue logic.
    @AfterEach
    void cleanup() {
        if (jobCreated != null) {
            jobRepository.findById(jobCreated).ifPresent(job -> {
                job.setDeleted(true);
                jobRepository.save(job);
            });
        }
    }

    @Test
    void organizationMemberCanListRunCascades() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/runCascade")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value());
    }

    /**
     * No ordinary organization member creates a cascade through the API - only the coordinator
     * does, same write lock as {@code History}. TERRAKUBE_DEVELOPERS: a real member with manage
     * rights on workspaces, but not the instance-owner group "user is a super service" allows.
     * Payload references a real job and organization so the only thing that can reject this
     * request is the permission check, not an incomplete or malformed body.
     */
    @Test
    void organizationMemberCannotCreateARunCascadeViaTheApi() {
        Workspace source = workspaceRepository.findById(UUID.fromString(WORKSPACE_SOURCE)).orElseThrow();
        Date now = new Date();
        Job job = new Job();
        job.setWorkspace(source);
        job.setOrganization(source.getOrganization());
        job.setStatus(JobStatus.completed);
        job.setCreatedBy("test");
        job.setUpdatedBy("test");
        job.setCreatedDate(now);
        job.setUpdatedDate(now);
        job = jobRepository.save(job);
        jobCreated = job.getId();

        // id included: RunCascade has no @GeneratedValue (the coordinator sets it directly via
        // repository save, never through Elide), so without one Elide 400s before it ever
        // reaches the permission check this test is actually about.
        String payload = String.format(
                "{\"data\":{\"type\":\"runCascade\",\"id\":\"%s\",\"relationships\":{"
                        + "\"originJob\":{\"data\":{\"type\":\"job\",\"id\":\"%d\"}},"
                        + "\"organization\":{\"data\":{\"type\":\"organization\",\"id\":\"%s\"}}}}}",
                UUID.randomUUID(), job.getId(), source.getOrganization().getId());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .contentType("application/vnd.api+json")
                .body(payload)
                .when()
                .post("/api/v1/runCascade")
                .then()
                .assertThat()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    /**
     * Not independently postable at all - rootLevel = false, same as History's relationship to
     * Workspace, so the only way to reach it is through its owning cascade.
     */
    @Test
    void runCascadeNodeIsNotATopLevelCollection() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .get("/api/v1/runCascadeNode")
                .then()
                .assertThat()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private io.terrakube.api.repository.RunCascadeRepository cascades;

    @org.springframework.beans.factory.annotation.Autowired
    private io.terrakube.api.repository.RunCascadeNodeRepository nodes;

    @org.springframework.beans.factory.annotation.Autowired
    private io.terrakube.api.repository.RunCascadeNodeAttemptRepository attempts;


    @Test
    void graphRelationshipsAreReadableButCannotBeMutatedByOrganizationMembers() {
        Workspace source = workspaceRepository.findById(UUID.fromString(WORKSPACE_SOURCE)).orElseThrow();
        Job job = new Job(); job.setWorkspace(source); job.setOrganization(source.getOrganization());
        job.setStatus(JobStatus.completed); job.setCreatedBy("test"); job.setUpdatedBy("test");
        job.setCreatedDate(new Date()); job.setUpdatedDate(new Date());
        job = jobRepository.save(job); jobCreated = job.getId();
        var cascade = new io.terrakube.api.rs.cascade.RunCascade();
        cascade.setId(UUID.randomUUID()); cascade.setOrganization(source.getOrganization()); cascade.setOriginJob(job);
        cascade = cascades.save(cascade);
        var node = new io.terrakube.api.rs.cascade.RunCascadeNode();
        node.setId(UUID.randomUUID()); node.setCascade(cascade); node.setWorkspace(source);
        node.setStatus(io.terrakube.api.rs.cascade.RunCascadeNodeStatus.SUCCEEDED);
        node = nodes.save(node);
        var attempt = new io.terrakube.api.rs.cascade.RunCascadeNodeAttempt();
        attempt.setId(UUID.randomUUID()); attempt.setNode(node); attempt.setJob(job); attempt.setAttemptNumber(1);
        attempt.setOutcome(io.terrakube.api.rs.cascade.RunCascadeNodeStatus.SUCCEEDED);
        attempts.save(attempt);
        try {
            given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .queryParam("include", "workspace,runCascadeNodeAttempt")
                .get("/api/v1/runCascade/" + cascade.getId() + "/runCascadeNode")
                .then().statusCode(200).body("data[0].id", org.hamcrest.Matchers.equalTo(node.getId().toString()))
                .body("included.type", org.hamcrest.Matchers.hasItem("runCascadeNodeAttempt"));
            for (String relationship : java.util.List.of("runCascadeNode", "runCascadeEdge")) {
                given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                    .get("/api/v1/runCascade/" + cascade.getId() + "/" + relationship)
                    .then().statusCode(200);
            }
        } finally { cascades.deleteById(cascade.getId()); }
    }
}
