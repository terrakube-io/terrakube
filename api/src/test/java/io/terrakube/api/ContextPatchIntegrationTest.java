package io.terrakube.api;

import io.terrakube.api.plugin.context.InMemoryContextWriteLock;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextPatchIntegrationTest extends ServerApplicationTests {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    // Built like the executor's revisions (execution epoch * 1,000,000 + seq), so a context file left
    // by an earlier run for the same (in-memory H2) job id never makes these patches stale.
    private final long revision = System.currentTimeMillis() * 1_000_000;

    // Executor tokens must name a running step of their job (ActiveStepTokenValidator).
    private final Map<Integer, UUID> runningStepByJob = new HashMap<>();

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        InMemoryContextWriteLock.stub(redisTemplate, valueOperations);
    }

    private int runningJob() {
        Organization org = organizationRepository.findById(UUID.fromString(ORG_ID)).orElseThrow();
        Workspace ws = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(ws);
        job.setStatus(JobStatus.running);
        job = jobRepository.save(job);
        Step step = new Step();
        step.setJob(job);
        step.setName("terraformPlan");
        step.setStatus(JobStatus.running);
        step.setStepNumber(100);
        runningStepByJob.put(job.getId(), stepRepository.save(step).getId());
        return job.getId();
    }

    private String executorToken(int jobId) {
        return generateSystemToken(Map.of("organizationId", ORG_ID, "workspaceId", WORKSPACE_ID,
                "jobId", String.valueOf(jobId), "stepId", runningStepByJob.get(jobId).toString()));
    }

    private io.restassured.response.ValidatableResponse patch(String token, int jobId, String body) {
        return given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/json")
                .body(body)
                .when()
                .post("/context/v1/" + jobId + "/patch")
                .then();
    }

    private io.restassured.response.ValidatableResponse read(int jobId) {
        return given()
                .headers("Authorization", "Bearer " + executorToken(jobId))
                .when()
                .get("/context/v1/" + jobId)
                .then()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void patchRequiresContextWriteAccess() {
        int jobId = runningJob();
        String body = "{\"revision\":" + revision + ",\"set\":{\"terrakubeUI/s1\":\"ui\"}}";

        patch(generatePAT("TERRAKUBE_DEVELOPERS"), jobId, body).statusCode(HttpStatus.FORBIDDEN.value());
        patch(executorToken(runningJob()), jobId, body).statusCode(HttpStatus.FORBIDDEN.value());
        patch(executorToken(jobId), jobId, body)
                .statusCode(HttpStatus.OK.value())
                .body("applied", Matchers.equalTo(1))
                .body("skipped", Matchers.equalTo(0));
    }

    @Test
    void patchesOnDifferentEntriesBothSurvive() {
        int jobId = runningJob();

        patch(executorToken(jobId), jobId, "{\"revision\":" + (revision + 2) + ",\"set\":{\"planRenderedText/s1\":\"text\"}}")
                .statusCode(HttpStatus.OK.value());
        // Older revision, but for a different entry: it still applies.
        patch(executorToken(jobId), jobId, "{\"revision\":" + (revision + 1) + ",\"set\":{\"planStructuredOutput/s1\":[{\"address\":\"a\"}]}}")
                .statusCode(HttpStatus.OK.value())
                .body("applied", Matchers.equalTo(1));

        read(jobId)
                .body("planRenderedText.s1", Matchers.equalTo("text"))
                .body("planStructuredOutput.s1[0].address", Matchers.equalTo("a"));
    }

    @Test
    void concurrentPatchesOnDifferentEntriesAllSurvive() throws Exception {
        int jobId = runningJob();
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                int step = i;
                statuses.add(pool.submit(() -> {
                    start.await();
                    return patch(executorToken(jobId), jobId, "{\"revision\":" + (revision + step) + ",\"set\":{\"terraformOutputs/s" + step
                            + "\":[{\"name\":\"out\",\"value\":" + step + ",\"sensitive\":false}]}}")
                            .extract().statusCode();
                }));
            }
            start.countDown();
            for (Future<Integer> status : statuses) {
                assertEquals(HttpStatus.OK.value(), status.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        io.restassured.response.ValidatableResponse context = read(jobId);
        for (int i = 0; i < writers; i++) {
            context.body("terraformOutputs.s" + i + "[0].value", Matchers.equalTo(i));
        }
    }

    @Test
    void staleOrDuplicatePatchIsSkipped() {
        int jobId = runningJob();
        String finalPatch = "{\"revision\":" + (revision + 5) + ",\"set\":{\"structuredOutputStatus\":{\"state\":\"COMPLETE\"},\"terrakubeUI/s1\":\"final\"}}";
        patch(executorToken(jobId), jobId, finalPatch).statusCode(HttpStatus.OK.value());

        patch(executorToken(jobId), jobId, finalPatch)
                .statusCode(HttpStatus.OK.value())
                .body("applied", Matchers.equalTo(0))
                .body("skipped", Matchers.equalTo(2));
        patch(executorToken(jobId), jobId, "{\"revision\":" + (revision + 1) + ",\"set\":{\"structuredOutputStatus\":{\"state\":\"RUNNING\"},\"terrakubeUI/s1\":\"progress\"}}")
                .statusCode(HttpStatus.OK.value())
                .body("applied", Matchers.equalTo(0))
                .body("skipped", Matchers.equalTo(2));

        read(jobId)
                .body("structuredOutputStatus.state", Matchers.equalTo("COMPLETE"))
                .body("terrakubeUI.s1", Matchers.equalTo("final"));
    }

    @Test
    void legacyPostFromAnOlderReadCannotRevertAPatchedEntry() {
        int jobId = runningJob();
        patch(executorToken(jobId), jobId, "{\"revision\":" + revision + ",\"set\":{\"terrakubeUI/s1\":\"old\"}}")
                .statusCode(HttpStatus.OK.value());
        String olderRead = read(jobId).extract().asString();

        patch(executorToken(jobId), jobId, "{\"revision\":" + (revision + 1) + ",\"set\":{\"terrakubeUI/s1\":\"new\"}}")
                .statusCode(HttpStatus.OK.value());
        // A template script writes back what it read earlier, plus its own key.
        given()
                .headers("Authorization", "Bearer " + executorToken(jobId), "Content-Type", "application/json")
                .body(olderRead.replaceFirst("\\{", "{\"custom\":\"value\","))
                .when()
                .post("/context/v1/" + jobId)
                .then()
                .statusCode(HttpStatus.OK.value());

        read(jobId)
                .body("terrakubeUI.s1", Matchers.equalTo("new"))
                .body("custom", Matchers.equalTo("value"));
    }

    @Test
    void patchForMissingJobReturnsNotFound() {
        patch(generateSystemToken(), 987654, "{\"revision\":" + revision + ",\"set\":{\"terrakubeUI/s1\":\"ui\"}}")
                .statusCode(HttpStatus.NOT_FOUND.value());
    }
}
