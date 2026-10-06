package io.terrakube.api;

import io.terrakube.api.repository.RunCascadeEdgeRepository;
import io.terrakube.api.repository.RunCascadeNodeAttemptRepository;
import io.terrakube.api.repository.RunCascadeNodeRepository;
import io.terrakube.api.repository.RunCascadeRepository;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeEdge;
import io.terrakube.api.rs.cascade.RunCascadeNode;
import io.terrakube.api.rs.cascade.RunCascadeNodeAttempt;
import io.terrakube.api.rs.cascade.RunCascadeNodeStatus;
import io.terrakube.api.rs.cascade.RunCascadeStatus;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.RunTriggerOnDestroyPolicy;
import io.terrakube.api.rs.workspace.trigger.RunTriggerSynchronizationMode;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entities and migration for the cascade schema (terrakube-io/terrakube#3629), ahead of any
 * coordinator logic: round-trips each new entity through the real schema and checks the
 * constraints the coordinator will depend on - one cascade per job, one node per
 * (cascade, workspace).
 */
class RunCascadeSchemaTest extends ServerApplicationTests {

    private static final String WORKSPACE_SOURCE = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String WORKSPACE_DESTINATION = "c20633b2-82cc-4105-9806-16e23ad0e1df";

    @Autowired
    private RunCascadeRepository runCascadeRepository;

    @Autowired
    private RunCascadeEdgeRepository runCascadeEdgeRepository;

    @Autowired
    private RunCascadeNodeRepository runCascadeNodeRepository;

    @Autowired
    private RunCascadeNodeAttemptRepository runCascadeNodeAttemptRepository;

    // Jobs stay visible to the rest of the suite's admission-queue logic unless retired - see
    // RunTriggerDispatchIntegrationTest.cleanup for the same concern.
    private final List<Integer> jobsCreated = new ArrayList<>();
    private final List<UUID> cascadesCreated = new ArrayList<>();

    @BeforeEach
    void resetTracking() {
        jobsCreated.clear();
        cascadesCreated.clear();
    }

    @AfterEach
    void cleanup() {
        // Deletes edges/nodes/attempts with it via the migration's onDelete=CASCADE.
        cascadesCreated.forEach(runCascadeRepository::deleteById);
        jobsCreated.forEach(id -> jobRepository.findById(id).ifPresent(job -> {
            job.setDeleted(true);
            jobRepository.save(job);
        }));
    }

    private Workspace workspace(String id) {
        return workspaceRepository.findById(UUID.fromString(id)).orElseThrow();
    }

    private Job job(Workspace workspace) {
        Date now = new Date();
        Job job = new Job();
        job.setWorkspace(workspace);
        job.setOrganization(workspace.getOrganization());
        job.setStatus(JobStatus.completed);
        job.setCreatedBy("test");
        job.setUpdatedBy("test");
        job.setCreatedDate(now);
        job.setUpdatedDate(now);
        Job saved = jobRepository.save(job);
        jobsCreated.add(saved.getId());
        return saved;
    }

    private RunCascade cascade(Job originJob, Workspace organizationOwner) {
        RunCascade cascade = new RunCascade();
        cascade.setId(UUID.randomUUID());
        cascade.setOriginJob(originJob);
        cascade.setOrganization(organizationOwner.getOrganization());
        RunCascade saved = runCascadeRepository.save(cascade);
        cascadesCreated.add(saved.getId());
        return saved;
    }

    @Test
    void roundTripsACascadeWithAnEdgeNodeAndAttempt() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        Job originJob = job(source);
        RunCascade cascade = cascade(originJob, source);

        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.RUNNING);

        RunCascadeEdge edge = new RunCascadeEdge();
        edge.setId(UUID.randomUUID());
        edge.setCascade(cascade);
        edge.setSourceWorkspace(source);
        edge.setDestinationWorkspace(destination);
        edge.setSynchronizationMode(RunTriggerSynchronizationMode.ALL);
        edge.setOnDestroy(RunTriggerOnDestroyPolicy.BLOCK);
        runCascadeEdgeRepository.save(edge);

        RunCascadeNode node = new RunCascadeNode();
        node.setId(UUID.randomUUID());
        node.setCascade(cascade);
        node.setWorkspace(destination);
        node.setDepth(1);
        node = runCascadeNodeRepository.save(node);

        assertThat(node.getStatus()).isEqualTo(RunCascadeNodeStatus.PENDING);

        Job attemptJob = job(destination);
        RunCascadeNodeAttempt attempt = new RunCascadeNodeAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setNode(node);
        attempt.setJob(attemptJob);
        attempt.setAttemptNumber(1);
        runCascadeNodeAttemptRepository.save(attempt);

        assertThat(runCascadeEdgeRepository.findByCascade_Id(cascade.getId())).hasSize(1);
        assertThat(runCascadeNodeRepository.findByCascade_Id(cascade.getId())).hasSize(1);
        assertThat(runCascadeNodeAttemptRepository.findByNode_IdOrderByAttemptNumberAsc(node.getId()))
                .extracting(RunCascadeNodeAttempt::getAttemptNumber)
                .containsExactly(1);

        Optional<RunCascade> byOriginJob = runCascadeRepository.findByOriginJob_Id(originJob.getId());
        assertThat(byOriginJob).map(RunCascade::getId).contains(cascade.getId());
    }

    /** A diamond's join workspace is one node per cascade - the unique constraint, not just application logic. */
    @Test
    void rejectsASecondNodeForTheSameWorkspaceInTheSameCascade() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);
        Job originJob = job(source);
        RunCascade cascade = cascade(originJob, source);

        RunCascadeNode first = new RunCascadeNode();
        first.setId(UUID.randomUUID());
        first.setCascade(cascade);
        first.setWorkspace(destination);
        first.setDepth(1);
        runCascadeNodeRepository.saveAndFlush(first);

        RunCascadeNode duplicate = new RunCascadeNode();
        duplicate.setId(UUID.randomUUID());
        duplicate.setCascade(cascade);
        duplicate.setWorkspace(destination);
        duplicate.setDepth(2);

        assertThatThrownBy(() -> runCascadeNodeRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newTriggerDefaultsToEachAndTrigger() {
        Workspace source = workspace(WORKSPACE_SOURCE);
        Workspace destination = workspace(WORKSPACE_DESTINATION);

        WorkspaceRunTrigger trigger = new WorkspaceRunTrigger();
        trigger.setSourceWorkspace(source);
        trigger.setDestinationWorkspace(destination);
        trigger.setOrganization(destination.getOrganization());

        assertThat(trigger.getSynchronizationMode()).isEqualTo(RunTriggerSynchronizationMode.EACH);
        assertThat(trigger.getOnDestroy()).isEqualTo(RunTriggerOnDestroyPolicy.TRIGGER);
    }
}
