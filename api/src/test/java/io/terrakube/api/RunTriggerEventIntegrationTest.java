package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.reconciliation.JobReconciliationService;
import io.terrakube.api.plugin.scheduler.trigger.RunTriggerEventDispatchService;
import io.terrakube.api.plugin.scheduler.trigger.RunTriggerEventTransactions;
import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The durable event path end to end: write (inside reconcile's own transaction), claim, process,
 * and crash recovery. {@link RunTriggerDispatchIntegrationTest} already covers the dispatch
 * decision itself; this is about the durability layer sitting in front of it.
 */
public class RunTriggerEventIntegrationTest extends ServerApplicationTests {

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

    @Autowired
    private RunTriggerEventRepository runTriggerEventRepository;

    @Autowired
    private RunTriggerEventDispatchService runTriggerEventDispatchService;

    @Autowired
    private RunTriggerEventTransactions runTriggerEventTransactions;

    private Set<Integer> jobsBefore;
    private Set<UUID> triggersBefore;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        jobsBefore = jobRepository.findAll().stream().map(Job::getId).collect(Collectors.toSet());
        triggersBefore = triggerRepository.findAll().stream()
                .map(WorkspaceRunTrigger::getId).collect(Collectors.toSet());
    }

    // See RunTriggerDispatchIntegrationTest.cleanup for why soft-delete, not delete, and why
    // only rows created during this test are touched.
    @AfterEach
    public void cleanup() {
        triggerRepository.findAll().stream()
                .filter(trigger -> !triggersBefore.contains(trigger.getId()))
                .forEach(triggerRepository::delete);
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
    void reconcilingAQualifyingJobWritesAPendingEventInTheSameTransaction() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        destination.setDefaultTemplate(TEMPLATE_PLAN_APPLY);
        workspaceRepository.save(destination);
        trigger(source, destination);

        Job upstream = runningJob(source, JobStatus.completed);
        jobReconciliationService.reconcile(upstream.getId(), false);

        Optional<RunTriggerEvent> event = runTriggerEventRepository.findByJob_Id(upstream.getId());
        assertThat(event).isPresent();
        assertThat(event.get().getStatus()).isEqualTo(RunTriggerEventStatus.PENDING);
        assertThat(event.get().getAttemptCount()).isZero();

        // Nothing dispatches synchronously any more - reconcile() only writes the row.
        assertThat(triggeredJobsOn(destination)).isEmpty();
    }

    @Test
    void reconcilingAJobWithNoOutboundEdgesWritesNoEvent() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Job upstream = runningJob(source, JobStatus.completed);

        jobReconciliationService.reconcile(upstream.getId(), false);

        assertThat(runTriggerEventRepository.findByJob_Id(upstream.getId())).isEmpty();
    }

    @Test
    void reconcilingJobWithNonStateChangingStepStillWritesEventForProcessTimeQualification() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        destination.setDefaultTemplate(TEMPLATE_PLAN_APPLY);
        workspaceRepository.save(destination);
        trigger(source, destination);

        // The apply step never ran - nothing for a dependent to react to.
        Job upstream = runningJob(source, JobStatus.notExecuted);

        jobReconciliationService.reconcile(upstream.getId(), false);

        // Still written - qualification by state identity happens at process time, not write time.
        assertThat(runTriggerEventRepository.findByJob_Id(upstream.getId())).isPresent();
    }

    @Test
    void processingThePendingEventCreatesTheDownstreamJob() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        destination.setDefaultTemplate(TEMPLATE_PLAN_APPLY);
        workspaceRepository.save(destination);
        trigger(source, destination);

        Job upstream = runningJob(source, JobStatus.completed);
        jobReconciliationService.reconcile(upstream.getId(), false);
        RunTriggerEvent event = runTriggerEventRepository.findByJob_Id(upstream.getId()).orElseThrow();

        runTriggerEventDispatchService.process(event.getId());

        RunTriggerEvent reloaded = runTriggerEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RunTriggerEventStatus.PROCESSED);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(triggeredJobsOn(destination)).hasSize(1);
    }

    @Test
    void processingAnAlreadyClaimedEventIsANoOp() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        destination.setDefaultTemplate(TEMPLATE_PLAN_APPLY);
        workspaceRepository.save(destination);
        trigger(source, destination);

        Job upstream = runningJob(source, JobStatus.completed);
        jobReconciliationService.reconcile(upstream.getId(), false);
        RunTriggerEvent event = runTriggerEventRepository.findByJob_Id(upstream.getId()).orElseThrow();

        // Simulates a second replica racing the first: already PROCESSING by the time we claim.
        event.setStatus(RunTriggerEventStatus.PROCESSING);
        runTriggerEventRepository.save(event);

        runTriggerEventDispatchService.process(event.getId());

        assertThat(triggeredJobsOn(destination)).isEmpty();
    }

    /** The scenario #3628 exists for: a replica claims the event and dies before recording a result. */
    @Test
    void aRowStuckInProcessingIsReclaimedByTheSweepAndThenSucceeds() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        destination.setDefaultTemplate(TEMPLATE_PLAN_APPLY);
        workspaceRepository.save(destination);
        trigger(source, destination);

        Job upstream = runningJob(source, JobStatus.completed);
        jobReconciliationService.reconcile(upstream.getId(), false);
        RunTriggerEvent event = runTriggerEventRepository.findByJob_Id(upstream.getId()).orElseThrow();

        // A claim that never got recorded, touched long enough ago to count as abandoned.
        event.setStatus(RunTriggerEventStatus.PROCESSING);
        event.setAttemptCount(1);
        event.setLastAttemptAt(new Date(System.currentTimeMillis() - 120_000));
        runTriggerEventRepository.save(event);

        runTriggerEventTransactions.sweepStuckProcessingRows(new Date(System.currentTimeMillis() - 60_000), 10);

        RunTriggerEvent reclaimed = runTriggerEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reclaimed.getStatus()).isEqualTo(RunTriggerEventStatus.PENDING);

        runTriggerEventDispatchService.process(event.getId());

        RunTriggerEvent finished = runTriggerEventRepository.findById(event.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(RunTriggerEventStatus.PROCESSED);
        assertThat(finished.getAttemptCount()).isEqualTo(2);
        assertThat(triggeredJobsOn(destination)).hasSize(1);
    }

    /** A row stuck in PROCESSING that has already exhausted its attempts is failed, not reclaimed. */
    @Test
    void aStuckRowAtTheAttemptLimitIsFailedNotReclaimed() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        destination.setDefaultTemplate(TEMPLATE_PLAN_APPLY);
        workspaceRepository.save(destination);
        trigger(source, destination);

        Job upstream = runningJob(source, JobStatus.completed);
        jobReconciliationService.reconcile(upstream.getId(), false);
        RunTriggerEvent event = runTriggerEventRepository.findByJob_Id(upstream.getId()).orElseThrow();

        event.setStatus(RunTriggerEventStatus.PROCESSING);
        event.setAttemptCount(10);
        event.setLastAttemptAt(new Date(System.currentTimeMillis() - 120_000));
        runTriggerEventRepository.save(event);

        runTriggerEventTransactions.sweepStuckProcessingRows(new Date(System.currentTimeMillis() - 60_000), 10);

        RunTriggerEvent failed = runTriggerEventRepository.findById(event.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RunTriggerEventStatus.FAILED);
    }

    /**
     * The scenario the fan-out idempotency check exists for, distinct from
     * {@link #aRowStuckInProcessingIsReclaimedByTheSweepAndThenSucceeds}: there the reclaim
     * happens before any dispatch work ran, so re-processing is trivially safe. Here the first
     * attempt actually ran to completion - the downstream job was created and scheduled - and
     * only then is the row forced back to PENDING, as a stuck-row sweep would if recordResult
     * itself failed after a successful dispatch. Reprocessing must not create a second job.
     */
    @Test
    void reclaimAfterACompletedDispatchDoesNotDuplicateTheDownstreamJob() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        destination.setDefaultTemplate(TEMPLATE_PLAN_APPLY);
        workspaceRepository.save(destination);
        trigger(source, destination);

        Job upstream = runningJob(source, JobStatus.completed);
        jobReconciliationService.reconcile(upstream.getId(), false);
        RunTriggerEvent event = runTriggerEventRepository.findByJob_Id(upstream.getId()).orElseThrow();

        runTriggerEventDispatchService.process(event.getId());
        assertThat(triggeredJobsOn(destination)).hasSize(1);

        // Force the already-PROCESSED row back to PENDING, as the stuck-row sweep would if this
        // replica died (or recordResult itself failed) immediately after a successful dispatch.
        RunTriggerEvent reloaded = runTriggerEventRepository.findById(event.getId()).orElseThrow();
        reloaded.setStatus(RunTriggerEventStatus.PENDING);
        runTriggerEventRepository.save(reloaded);

        runTriggerEventDispatchService.process(event.getId());

        assertThat(triggeredJobsOn(destination)).hasSize(1);
    }

    @Test
    void enqueueingTwiceForTheSameJobProducesOnlyOneEvent() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        destination.setDefaultTemplate(TEMPLATE_PLAN_APPLY);
        workspaceRepository.save(destination);
        trigger(source, destination);

        Job upstream = runningJob(source, JobStatus.completed);
        jobReconciliationService.reconcile(upstream.getId(), false);
        // A second reconcile of an already-terminal job is a no-op before reaching the event writer.
        jobReconciliationService.reconcile(upstream.getId(), false);

        long count = runTriggerEventRepository.findAll().stream()
                .filter(e -> e.getJob().getId() == upstream.getId())
                .count();
        assertThat(count).isEqualTo(1);
    }
}
