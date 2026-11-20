package io.terrakube.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import io.terrakube.api.plugin.context.InMemoryContextWriteLock;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * The executor sends the final plan snapshot of a run to the context. The API counts what the plan changes on the job,
 * and when the run is a scheduled plan-only one, that is a drift check and the workspace shows the result.
 */
class DriftCheckIntegrationTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    // Built like the executor's revisions (execution epoch * 1,000,000 + seq).
    private long revision = System.currentTimeMillis() * 1_000_000;

    private Template planOnly;
    private Template planAndApply;

    private Template template(String name, String flow) {
        Template template = new Template();
        template.setName(name + "-" + UUID.randomUUID());
        template.setTcl(Base64.getEncoder().encodeToString(flow.getBytes()));
        template.setOrganization(organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow());
        return templateRepository.save(template);
    }

    @BeforeEach
    void templatesAndLock() {
        MockitoAnnotations.openMocks(this);
        InMemoryContextWriteLock.stub(redisTemplate, valueOperations);
        planOnly = template("drift-plan-only", "flow:\n- type: terraformPlan\n  step: 100\n");
        planAndApply = template("drift-plan-apply", "flow:\n- type: terraformPlan\n  step: 100\n- type: terraformApply\n  step: 200\n");
    }

    @AfterEach
    void restore() {
        Workspace workspace = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
        workspaceRepository.updateDrift(workspace.getId(), null, null, null, null);
        templateRepository.deleteById(planOnly.getId());
        templateRepository.deleteById(planAndApply.getId());
    }

    private record Run(Job job, UUID stepId) {
    }

    private Run run(String via, Template template) {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        Workspace workspace = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
        Job job = new Job();
        job.setStatus(JobStatus.running);
        job.setVia(via);
        job.setTemplateReference(template.getId().toString());
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(job);
        Step step = new Step();
        step.setJob(job);
        step.setName("terraformPlan");
        step.setStatus(JobStatus.running);
        step.setStepNumber(100);
        return new Run(job, stepRepository.save(step).getId());
    }

    private void executorSendsTheFinalPlan(Run run, String changes) {
        String stepId = run.stepId().toString();
        String body = "{\"revision\":" + (++revision) + ",\"set\":{"
                + "\"planStructuredOutput/" + stepId + "\":" + changes + ","
                + "\"jobDiagnostics/" + stepId + "\":[],"
                + "\"structuredOutputStatus\":{\"state\":\"PERSISTED\",\"stepId\":\"" + stepId
                + "\",\"phase\":\"plan\",\"finalSnapshot\":true}}}";
        String token = generateSystemToken(Map.of("organizationId", ORGANIZATION_ID, "workspaceId", WORKSPACE_ID,
                "jobId", String.valueOf(run.job().getId()), "stepId", stepId));
        given()
                .headers("Authorization", "Bearer " + token, "Content-Type", "application/json")
                .body(body)
                .when()
                .post("/context/v1/" + run.job().getId() + "/patch")
                .then()
                .statusCode(HttpStatus.OK.value());
    }

    private Workspace workspace() {
        return workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
    }

    private Job stored(Run run) {
        return jobRepository.findById(run.job().getId()).orElseThrow();
    }

    private static final String ONE_CHANGE = "[{\"address\":\"aws_a.x\",\"action\":\"update\"}]";
    private static final String NO_CHANGE = "[]";

    @Test
    void thePlanCountsAreRecordedOnTheJobForAnyRun() {
        Run run = run(JobVia.UI.getValue(), planAndApply);

        executorSendsTheFinalPlan(run, "[{\"address\":\"a\",\"action\":\"create\"},{\"address\":\"b\",\"action\":\"replace\"},"
                + "{\"address\":\"c\",\"action\":\"update\"}]");

        assertThat(stored(run).getPlanAdd()).isEqualTo(2);
        assertThat(stored(run).getPlanChange()).isEqualTo(1);
        assertThat(stored(run).getPlanDestroy()).isEqualTo(1);
        // A run of this kind says nothing about drift.
        assertThat(stored(run).getDriftResult()).isNull();
        assertThat(workspace().getDriftStatus()).isNull();
    }

    @Test
    void aScheduledPlanOnlyRunWithChangesMakesTheWorkspaceDrifted() {
        Run run = run(JobVia.SCHEDULE.getValue(), planOnly);

        executorSendsTheFinalPlan(run, ONE_CHANGE);

        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");
        assertThat(workspace().getDriftJobId()).isEqualTo(run.job().getId());
        assertThat(workspace().getDriftFirstSeen()).isNotNull();
        assertThat(stored(run).getDriftResult()).isEqualTo("drift");
    }

    @Test
    void aLaterCheckWithNoChangesMakesItCleanAgain() {
        executorSendsTheFinalPlan(run(JobVia.SCHEDULE.getValue(), planOnly), ONE_CHANGE);
        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");

        executorSendsTheFinalPlan(run(JobVia.SCHEDULE.getValue(), planOnly), NO_CHANGE);

        assertThat(workspace().getDriftStatus()).isEqualTo("clean");
        assertThat(workspace().getDriftFirstSeen()).isNull();
    }

    @Test
    void aPlanThatCanBeAppliedOrANonScheduledRunIsNotACheck() {
        executorSendsTheFinalPlan(run(JobVia.SCHEDULE.getValue(), planAndApply), ONE_CHANGE);
        executorSendsTheFinalPlan(run(JobVia.UI.getValue(), planOnly), ONE_CHANGE);

        assertThat(workspace().getDriftStatus()).isNull();
    }

    @Test
    void aRetriedWriteOfTheSamePlanChangesNothing() {
        Run run = run(JobVia.SCHEDULE.getValue(), planOnly);
        executorSendsTheFinalPlan(run, ONE_CHANGE);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(workspace().getDriftStatus()).isEqualTo("drifted"));
        java.util.Date checked = workspace().getDriftLastChecked();

        // The same revision again is skipped by the context, so the check is not recorded twice.
        revision--;
        executorSendsTheFinalPlan(run, ONE_CHANGE);

        assertThat(workspace().getDriftLastChecked()).isEqualTo(checked);
    }
}
