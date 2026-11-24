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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import io.terrakube.api.plugin.context.InMemoryContextWriteLock;
import io.terrakube.api.plugin.scheduler.drift.DriftCheckService;
import io.terrakube.api.plugin.workspace.WorkspaceJobStatusService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * The executor sends the final plan snapshot of a run to the context. The API counts what the plan changes on the job,
 * and when the run is a scheduled plain plan that then finishes well, that is a drift check and the workspace shows the
 * result. A run that fails, or plans a destroy, says nothing.
 */
class DriftCheckIntegrationTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    // Built like the executor's revisions (execution epoch * 1,000,000 + seq).
    private long revision = System.currentTimeMillis() * 1_000_000;

    @Autowired
    private WorkspaceJobStatusService workspaceJobStatusService;
    @Autowired
    private DriftCheckService driftCheckService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Template planOnly;
    private Template planAndApply;
    private Template planDestroy;

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
        planDestroy = template("drift-plan-destroy", "flow:\n- type: terraformPlanDestroy\n  step: 100\n");
    }

    @AfterEach
    void restore() {
        // The drift columns are not written by an entity save, so they are cleared with the statement that owns them.
        jdbcTemplate.update("update workspace set drift_status = null, drift_first_seen = null, "
                + "drift_last_checked = null, drift_job_id = null where id = ?", WORKSPACE_ID);
        templateRepository.deleteById(planOnly.getId());
        templateRepository.deleteById(planAndApply.getId());
        templateRepository.deleteById(planDestroy.getId());
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
        // What the run executes is the flow stored on it when it started, not the template as it is later.
        job.setTcl(template.getTcl());
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

    /** The run ends, as the scheduler ends it: the status is saved and the workspace's status is refreshed from it. */
    private void finishes(Run run, JobStatus status) {
        Job job = stored(run);
        job.setStatus(status);
        jobRepository.saveAndFlush(job);
        workspaceJobStatusService.refresh(job);
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
        // The plan alone is not a result: the run can still fail.
        assertThat(workspace().getDriftStatus()).isNull();
        finishes(run, JobStatus.completed);

        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");
        assertThat(workspace().getDriftJobId()).isEqualTo(run.job().getId());
        assertThat(workspace().getDriftFirstSeen()).isNotNull();
        assertThat(stored(run).getDriftResult()).isEqualTo("drift");
    }

    @Test
    void aLaterCheckWithNoChangesMakesItCleanAgain() {
        Run first = run(JobVia.SCHEDULE.getValue(), planOnly);
        executorSendsTheFinalPlan(first, ONE_CHANGE);
        finishes(first, JobStatus.completed);
        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");

        Run second = run(JobVia.SCHEDULE.getValue(), planOnly);
        executorSendsTheFinalPlan(second, NO_CHANGE);
        finishes(second, JobStatus.noChanges);

        assertThat(workspace().getDriftStatus()).isEqualTo("clean");
        assertThat(workspace().getDriftFirstSeen()).isNull();
    }

    @Test
    void anOlderCheckArrivingLateDoesNotReplaceANewerResult() {
        Run older = run(JobVia.SCHEDULE.getValue(), planOnly);
        Run newer = run(JobVia.SCHEDULE.getValue(), planOnly);
        executorSendsTheFinalPlan(newer, ONE_CHANGE);
        finishes(newer, JobStatus.completed);
        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");

        executorSendsTheFinalPlan(older, NO_CHANGE);
        finishes(older, JobStatus.noChanges);

        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");
        assertThat(workspace().getDriftJobId()).isEqualTo(newer.job().getId());
        // The older run still keeps its own result.
        assertThat(stored(older).getDriftResult()).isEqualTo("clean");
    }

    @Test
    void aRefreshOnlyPlanIsNotACheckBecauseItListsNoResourceChanges() {
        Run run = run(JobVia.SCHEDULE.getValue(), planOnly);
        Job refreshOnly = stored(run);
        refreshOnly.setRefreshOnly(true);
        jobRepository.save(refreshOnly);

        executorSendsTheFinalPlan(run, NO_CHANGE);
        finishes(run, JobStatus.noChanges);

        assertThat(workspace().getDriftStatus()).isNull();
        assertThat(stored(run).getDriftResult()).isNull();
    }

    @Test
    void recordingACheckDoesNotRewriteTheRestOfTheJobOrTheWorkspace() {
        Run run = run(JobVia.SCHEDULE.getValue(), planOnly);
        java.util.Date workspaceBefore = workspace().getUpdatedDate();
        java.util.Date jobBefore = stored(run).getUpdatedDate();

        executorSendsTheFinalPlan(run, ONE_CHANGE);
        jdbcTemplate.update("update job set status = 'completed' where id = ?", run.job().getId());
        driftCheckService.record(run.job().getId());

        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");
        // A flush of the whole row would have audited an update and moved these.
        assertThat(workspace().getUpdatedDate()).isEqualTo(workspaceBefore);
        assertThat(stored(run).getUpdatedDate()).isEqualTo(jobBefore);
    }

    @Test
    void aPlanThatCanBeAppliedOrANonScheduledRunIsNotACheck() {
        Run canApply = run(JobVia.SCHEDULE.getValue(), planAndApply);
        executorSendsTheFinalPlan(canApply, ONE_CHANGE);
        finishes(canApply, JobStatus.completed);
        Run byHand = run(JobVia.UI.getValue(), planOnly);
        executorSendsTheFinalPlan(byHand, ONE_CHANGE);
        finishes(byHand, JobStatus.completed);

        assertThat(workspace().getDriftStatus()).isNull();
    }

    @Test
    void aRetriedWriteOfTheSamePlanChangesNothing() {
        Run run = run(JobVia.SCHEDULE.getValue(), planOnly);
        executorSendsTheFinalPlan(run, ONE_CHANGE);
        finishes(run, JobStatus.completed);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(workspace().getDriftStatus()).isEqualTo("drifted"));
        java.util.Date checked = workspace().getDriftLastChecked();

        // The scheduler looks at a finished run again every tick. The result is recorded once.
        finishes(run, JobStatus.completed);
        finishes(run, JobStatus.completed);

        assertThat(workspace().getDriftLastChecked()).isEqualTo(checked);
    }

    @Test
    void aScheduledPlanOfADestroyIsNotACheckAndLeavesACleanWorkspaceClean() {
        Run clean = run(JobVia.SCHEDULE.getValue(), planOnly);
        executorSendsTheFinalPlan(clean, NO_CHANGE);
        finishes(clean, JobStatus.noChanges);
        assertThat(workspace().getDriftStatus()).isEqualTo("clean");

        Run destroy = run(JobVia.SCHEDULE.getValue(), planDestroy);
        executorSendsTheFinalPlan(destroy, "[{\"address\":\"a\",\"action\":\"delete\"},{\"address\":\"b\",\"action\":\"delete\"}]");
        finishes(destroy, JobStatus.completed);

        assertThat(workspace().getDriftStatus()).isEqualTo("clean");
        assertThat(workspace().getDriftJobId()).isEqualTo(clean.job().getId());
        assertThat(stored(destroy).getDriftResult()).isNull();
    }

    @Test
    void aCheckThatFailedAfterStreamingAPlanLeavesADriftedWorkspaceDrifted() {
        Run drifted = run(JobVia.SCHEDULE.getValue(), planOnly);
        executorSendsTheFinalPlan(drifted, ONE_CHANGE);
        finishes(drifted, JobStatus.completed);
        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");

        // The stream broke before any change was sent, so the final snapshot is empty, and then the run fails.
        Run failed = run(JobVia.SCHEDULE.getValue(), planOnly);
        executorSendsTheFinalPlan(failed, NO_CHANGE);
        finishes(failed, JobStatus.failed);

        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");
        assertThat(workspace().getDriftJobId()).isEqualTo(drifted.job().getId());
        assertThat(stored(failed).getDriftResult()).isNull();
    }

    @Test
    void aWorkspaceSavedAfterACheckWasRecordedKeepsTheResult() {
        Workspace loadedByTheScheduler = workspace();

        Run run = run(JobVia.SCHEDULE.getValue(), planOnly);
        executorSendsTheFinalPlan(run, ONE_CHANGE);
        finishes(run, JobStatus.completed);
        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");

        loadedByTheScheduler.setLastJobStatus(JobStatus.completed);
        workspaceRepository.saveAndFlush(loadedByTheScheduler);

        assertThat(workspace().getDriftStatus()).isEqualTo("drifted");
        assertThat(workspace().getDriftJobId()).isEqualTo(run.job().getId());
        assertThat(workspace().getDriftFirstSeen()).isNotNull();
    }
}
