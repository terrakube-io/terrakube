package io.terrakube.api;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class JobExecutionDurationApiTest extends ServerApplicationTests {
    private static final String ORGANIZATION = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private Job job;
    private Step step;

    @BeforeEach
    void createRun() {
        Job run = new Job();
        run.setOrganization(organizationRepository.findById(UUID.fromString(ORGANIZATION)).orElseThrow());
        run.setWorkspace(workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow());
        run.setStatus(JobStatus.completed);
        job = jobRepository.saveAndFlush(run);
        Step phase = new Step();
        phase.setJob(job);
        phase.setStepNumber(100);
        phase.setName("Custom preview");
        phase.setStatus(JobStatus.failed);
        step = stepRepository.saveAndFlush(phase);
    }

    @Test
    void executorTimingRoundTripsAndRunSummaryIsReadable() {
        given().headers("Authorization", "Bearer " + generateExecutorToken(),
                        "Content-Type", "application/vnd.api+json")
                .body("{\"data\":{\"type\":\"step\",\"id\":\"" + step.getId()
                        + "\",\"attributes\":{\"executionPhase\":\"plan\",\"executionDurationMs\":30000}}}")
                .patch("/api/v1/organization/" + ORGANIZATION + "/job/" + job.getId() + "/step/" + step.getId())
                .then().statusCode(204);

        given().header("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .get("/api/v1/organization/" + ORGANIZATION + "/job/" + job.getId() + "?include=step")
                .then().statusCode(200)
                .body("data.attributes.planDurationMs", equalTo(30000))
                .body("data.attributes.applyDurationMs", nullValue())
                .body("included.find { it.id == '" + step.getId() + "' }.attributes.executionDurationMs", equalTo(30000));
    }

    @Test
    void historicalRunHasNoInferredTiming() {
        given().header("Authorization", "Bearer " + generateExecutorToken())
                .get("/api/v1/organization/" + ORGANIZATION + "/job/" + job.getId())
                .then().statusCode(200)
                .body("data.attributes.planDurationMs", nullValue())
                .body("data.attributes.applyDurationMs", nullValue());
    }
}
