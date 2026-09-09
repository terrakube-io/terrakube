package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.reconciliation.JobReconciliationService;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The dispatch path end to end, against the real database and the real reconciliation routine.
 *
 * <p>No executor is involved: {@code reconcile} is the routine that performs the transition to
 * completed, so calling it directly exercises everything the engine depends on - the entity
 * graph on the dispatch query, the TCL flow lookup, the foreign keys of the created job - in
 * milliseconds and with no Kubernetes or HTTP anywhere.
 *
 * <p>The unit tests cover the branches. This one covers the wiring, which is what mocks cannot
 * tell you: that the query really loads what the engine reads, and that the row it writes is
 * actually accepted by the schema.
 */
public class RunTriggerDispatchIntegrationTest extends ServerApplicationTests {

    private static final String WORKSPACE_SOURCE = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String WORKSPACE_DESTINATION = "c20633b2-82cc-4105-9806-16e23ad0e1df";

    /** "Plan and Apply" from simple.xml: terraformPlan at step 100, terraformApply at step 200. */
    private static final String TEMPLATE_PLAN_APPLY = "2db36f7c-f549-4341-a789-315d47eb061d";
    private static final String TCL_PLAN_APPLY =
            "ZmxvdzoKICAtIHR5cGU6ICJ0ZXJyYWZvcm1QbGFuIgogICAgc3RlcDogMTAwCiAgICBuYW1lOiAiUGxhbiIKIC"
            + "AtIHR5cGU6ICJ0ZXJyYWZvcm1BcHBseSIKICAgIHN0ZXA6IDIwMAogICAgbmFtZTogIkFwcGx5Ig==";

    @Autowired
    private JobReconciliationService jobReconciliationService;

    @Autowired
    private WorkspaceRunTriggerRepository triggerRepository;

    private Set<Integer> jobsBefore;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        jobsBefore = jobRepository.findAll().stream().map(Job::getId).collect(Collectors.toSet());
    }

    /**
     * Jobs created here are visible to every other test in the suite - ScheduleJob's admission
     * query looks at a workspace's other jobs - so nothing may outlive this class. Only what
     * appeared during the test is retired: a blanket deleteAll would also take jobs another
     * test class created and still expects to find.
     *
     * <p>Soft deleted rather than deleted, which is what the product itself does for job
     * pruning. A hard delete races the notification outbox: notifyStatusChanged writes a row
     * referencing the job on another thread, and if it lands after the delete the foreign key
     * rejects it. The entity's SQLRestriction makes a soft-deleted job invisible to every
     * query anyway, which is all this cleanup needs.
     */
    @AfterEach
    public void cleanup() {
        triggerRepository.deleteAll();
        jobRepository.findAll().stream()
                .filter(job -> !jobsBefore.contains(job.getId()))
                .forEach(job -> {
                    job.setDeleted(true);
                    jobRepository.save(job);
                });
    }

    private Workspace workspace(String id) {
        return workspaceRepository.findById(UUID.fromString(id)).orElseThrow();
    }

    private WorkspaceRunTrigger trigger(Workspace source, Workspace destination) {
        WorkspaceRunTrigger trigger = new WorkspaceRunTrigger();
        trigger.setSourceWorkspace(source);
        trigger.setDestinationWorkspace(destination);
        trigger.setOrganization(destination.getOrganization());
        trigger.setEnabled(true);
        return triggerRepository.save(trigger);
    }

    /** A run of the source workspace, one step short of terminal, ready to be reconciled. */
    private Job runningJob(Workspace source, JobStatus applyStepStatus) {
        Date now = new Date(System.currentTimeMillis());
        Job job = new Job();
        job.setWorkspace(source);
        job.setOrganization(source.getOrganization());
        job.setTemplateReference(TEMPLATE_PLAN_APPLY);
        job.setTcl(TCL_PLAN_APPLY);
        job.setStatus(JobStatus.running);
        job.setCreatedBy("test");
        job.setUpdatedBy("test");
        job.setCreatedDate(now);
        job.setUpdatedDate(now);
        Job saved = jobRepository.save(job);

        step(saved, 100, JobStatus.completed);
        step(saved, 200, applyStepStatus);
        return saved;
    }

    private void step(Job job, int stepNumber, JobStatus status) {
        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(stepNumber);
        step.setName("step " + stepNumber);
        step.setStatus(status);
        stepRepository.save(step);
    }

    private List<Job> triggeredJobsOn(Workspace destination) {
        return jobRepository.findAll().stream()
                .filter(j -> j.getWorkspace() != null
                        && j.getWorkspace().getId().equals(destination.getId()))
                .filter(j -> JobVia.RUN_TRIGGER.getValue().equals(j.getVia()))
                .toList();
    }

    @Test
    void completingAnApplyCreatesTheDownstreamJob() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        destination.setDefaultTemplate(TEMPLATE_PLAN_APPLY);
        workspaceRepository.save(destination);
        trigger(source, destination);

        Job upstream = runningJob(source, JobStatus.completed);

        jobReconciliationService.reconcile(upstream.getId(), false);

        List<Job> triggered = triggeredJobsOn(destination);
        assertThat(triggered).hasSize(1);

        Job downstream = triggered.get(0);
        assertThat(downstream.getTriggeredByJobId()).isEqualTo(upstream.getId());
        assertThat(downstream.getCascadeDepth()).isEqualTo(1);
        assertThat(downstream.getTemplateReference()).isEqualTo(TEMPLATE_PLAN_APPLY);
        assertThat(downstream.getOrganization().getId()).isEqualTo(destination.getOrganization().getId());
        // Status is not asserted: the Quartz trigger fires at once and the scheduler owns it
        // from here. Provenance is what this test is about, and it never changes.
    }

    /**
     * The same run, with the apply never executed. It still reconciles to completed, so this is
     * what separates reading step outcomes from reading job status - on real data rather than
     * on a stubbed TclService.
     */
    @Test
    void completingWithoutRunningTheApplyCreatesNothing() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        destination.setDefaultTemplate(TEMPLATE_PLAN_APPLY);
        workspaceRepository.save(destination);
        trigger(source, destination);

        Job upstream = runningJob(source, JobStatus.notExecuted);

        jobReconciliationService.reconcile(upstream.getId(), false);

        assertThat(triggeredJobsOn(destination)).isEmpty();
    }

    /** A source nobody depends on must not produce anything. */
    @Test
    void completingAnApplyWithNoTriggerCreatesNothing() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);

        Job upstream = runningJob(source, JobStatus.completed);

        jobReconciliationService.reconcile(upstream.getId(), false);

        assertThat(triggeredJobsOn(destination)).isEmpty();
    }
}
