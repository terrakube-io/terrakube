package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.RunCascadeEdgeRepository;
import io.terrakube.api.repository.RunCascadeNodeAttemptRepository;
import io.terrakube.api.repository.RunCascadeNodeRepository;
import io.terrakube.api.repository.RunCascadeRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeEdge;
import io.terrakube.api.rs.cascade.RunCascadeNode;
import io.terrakube.api.rs.cascade.RunCascadeNodeAttempt;
import io.terrakube.api.rs.cascade.RunCascadeNodeStatus;
import io.terrakube.api.rs.cascade.RunCascadeStatus;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.RunTriggerOnDestroyPolicy;
import io.terrakube.api.rs.workspace.trigger.RunTriggerSynchronizationMode;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The coordinator's own decisions in isolation: EACH never gated, ANY/ALL gated exactly once per
 * cascade, a diamond's join evaluated as a single node. {@link RunTriggerDispatchServiceTest}
 * covers the fan-out loop that calls this with a transparent (always-allow) stub; this is where
 * the actual gating logic is proven.
 */
class RunCascadeCoordinatorServiceTest {

    RunCascadeRepository runCascadeRepository;
    RunCascadeNodeRepository runCascadeNodeRepository;
    RunCascadeEdgeRepository runCascadeEdgeRepository;
    RunCascadeNodeAttemptRepository runCascadeNodeAttemptRepository;
    WorkspaceRunTriggerRepository workspaceRunTriggerRepository;
    RunTriggerProperties properties;
    RunCascadeCoordinatorService subject;

    private int nextJobId;

    @BeforeEach
    void setup() {
        nextJobId = 100;
        runCascadeRepository = mock(RunCascadeRepository.class);
        runCascadeNodeRepository = mock(RunCascadeNodeRepository.class);
        runCascadeEdgeRepository = mock(RunCascadeEdgeRepository.class);
        runCascadeNodeAttemptRepository = mock(RunCascadeNodeAttemptRepository.class);
        workspaceRunTriggerRepository = mock(WorkspaceRunTriggerRepository.class);
        properties = new RunTriggerProperties();

        // Identity save: these tests assert on the entities themselves, not on round-tripping
        // through a real database.
        lenientIdentitySave(runCascadeRepository);
        lenientIdentitySave(runCascadeNodeRepository);
        lenientIdentitySave(runCascadeEdgeRepository);
        lenientIdentitySave(runCascadeNodeAttemptRepository);

        subject = new RunCascadeCoordinatorService(runCascadeRepository, runCascadeNodeRepository,
                runCascadeEdgeRepository, runCascadeNodeAttemptRepository, workspaceRunTriggerRepository,
                properties);
    }

    @SuppressWarnings("unchecked")
    private <T, ID> void lenientIdentitySave(org.springframework.data.repository.CrudRepository<T, ID> repository) {
        doAnswer(i -> i.getArgument(0)).when(repository).save(any());
    }

    private Workspace workspace(String name) {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName(name);
        workspace.setOrganization(organization);
        return workspace;
    }

    private WorkspaceRunTrigger trigger(Workspace source, Workspace destination, RunTriggerSynchronizationMode mode) {
        WorkspaceRunTrigger trigger = new WorkspaceRunTrigger();
        trigger.setId(UUID.randomUUID());
        trigger.setSourceWorkspace(source);
        trigger.setDestinationWorkspace(destination);
        trigger.setSynchronizationMode(mode);
        trigger.setOnDestroy(RunTriggerOnDestroyPolicy.TRIGGER);
        trigger.setEnabled(true);
        return trigger;
    }

    private RunCascade cascade() {
        RunCascade cascade = new RunCascade();
        cascade.setId(UUID.randomUUID());
        cascade.setStatus(RunCascadeStatus.RUNNING);
        return cascade;
    }

    private RunCascadeNode pendingNode(RunCascade cascade, Workspace workspace) {
        RunCascadeNode node = new RunCascadeNode();
        node.setId(UUID.randomUUID());
        node.setCascade(cascade);
        node.setWorkspace(workspace);
        node.setStatus(RunCascadeNodeStatus.PENDING);
        doReturn(Optional.of(node)).when(runCascadeNodeRepository)
                .findByCascade_IdAndWorkspace_Id(cascade.getId(), workspace.getId());
        return node;
    }

    // ---------------------------------------------------------------- EACH

    @Test
    void eachAlwaysDispatchesRegardlessOfNodeState() {
        RunCascade cascade = cascade();
        Workspace destination = workspace("destination");
        WorkspaceRunTrigger trigger = trigger(workspace("source"), destination, RunTriggerSynchronizationMode.EACH);

        // No node stub at all - EACH must not even look one up.
        assertThat(subject.shouldDispatch(cascade, trigger)).isTrue();
        assertThat(subject.shouldDispatch(cascade, trigger)).isTrue();
        verify(runCascadeNodeRepository, never()).findByCascade_IdAndWorkspace_Id(any(), any());
    }

    // ---------------------------------------------------------------- ANY

    @Test
    void anyDispatchesOnTheFirstSucceedingParentOnly() {
        RunCascade cascade = cascade();
        Workspace destination = workspace("join");
        RunCascadeNode node = pendingNode(cascade, destination);
        WorkspaceRunTrigger trigger = trigger(workspace("parent"), destination, RunTriggerSynchronizationMode.ANY);

        assertThat(subject.shouldDispatch(cascade, trigger)).isTrue();
        assertThat(node.getStatus()).isEqualTo(RunCascadeNodeStatus.RUNNING);

        // A second ANY parent (or the same one re-evaluated) must not fire again.
        assertThat(subject.shouldDispatch(cascade, trigger)).isFalse();
    }

    // ---------------------------------------------------------------- ALL

    @Test
    void allWaitsUntilEveryAllModeParentHasSucceeded() {
        RunCascade cascade = cascade();
        Workspace left = workspace("left");
        Workspace right = workspace("right");
        Workspace join = workspace("join");
        RunCascadeNode joinNode = pendingNode(cascade, join);

        WorkspaceRunTrigger leftToJoin = trigger(left, join, RunTriggerSynchronizationMode.ALL);
        WorkspaceRunTrigger rightToJoin = trigger(right, join, RunTriggerSynchronizationMode.ALL);

        RunCascadeEdge leftEdge = edge(cascade, leftToJoin);
        RunCascadeEdge rightEdge = edge(cascade, rightToJoin);
        doReturn(List.of(leftEdge, rightEdge)).when(runCascadeEdgeRepository)
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), join.getId());

        // Neither parent has succeeded yet.
        nodeWithStatus(cascade, left, RunCascadeNodeStatus.RUNNING);
        nodeWithStatus(cascade, right, RunCascadeNodeStatus.PENDING);
        assertThat(subject.shouldDispatch(cascade, leftToJoin)).isFalse();

        // Left succeeds; right has not, so the join still waits.
        nodeWithStatus(cascade, left, RunCascadeNodeStatus.SUCCEEDED);
        assertThat(subject.shouldDispatch(cascade, leftToJoin)).isFalse();
        assertThat(joinNode.getStatus()).isEqualTo(RunCascadeNodeStatus.PENDING);

        // Right succeeds too - every ALL parent has now, so the join dispatches.
        nodeWithStatus(cascade, right, RunCascadeNodeStatus.SUCCEEDED);
        assertThat(subject.shouldDispatch(cascade, rightToJoin)).isTrue();
        assertThat(joinNode.getStatus()).isEqualTo(RunCascadeNodeStatus.RUNNING);
    }

    private RunCascadeEdge edge(RunCascade cascade, WorkspaceRunTrigger trigger) {
        RunCascadeEdge edge = new RunCascadeEdge();
        edge.setId(UUID.randomUUID());
        edge.setCascade(cascade);
        edge.setSourceWorkspace(trigger.getSourceWorkspace());
        edge.setDestinationWorkspace(trigger.getDestinationWorkspace());
        edge.setSynchronizationMode(trigger.getSynchronizationMode());
        return edge;
    }

    private void nodeWithStatus(RunCascade cascade, Workspace workspace, RunCascadeNodeStatus status) {
        RunCascadeNode node = new RunCascadeNode();
        node.setId(UUID.randomUUID());
        node.setCascade(cascade);
        node.setWorkspace(workspace);
        node.setStatus(status);
        doReturn(Optional.of(node)).when(runCascadeNodeRepository)
                .findByCascade_IdAndWorkspace_Id(cascade.getId(), workspace.getId());
    }

    // ---------------------------------------------------------------- diamond dedup

    /** The join workspace must become exactly one node, not one per incoming edge. */
    @Test
    void snapshotCreatesOneNodeForADiamondsJoinWorkspace() {
        Workspace root = workspace("root");
        Workspace left = workspace("left");
        Workspace right = workspace("right");
        Workspace join = workspace("join");
        Job originJob = job(root, 0);

        doReturn(Optional.empty()).when(runCascadeNodeAttemptRepository).findByJob_Id(originJob.getId());
        doReturn(List.of(trigger(root, left, RunTriggerSynchronizationMode.EACH),
                trigger(root, right, RunTriggerSynchronizationMode.EACH)))
                .when(workspaceRunTriggerRepository).findEnabledBySourceWorkspaceId(root.getId());
        doReturn(List.of(trigger(left, join, RunTriggerSynchronizationMode.ALL)))
                .when(workspaceRunTriggerRepository).findEnabledBySourceWorkspaceId(left.getId());
        doReturn(List.of(trigger(right, join, RunTriggerSynchronizationMode.ALL)))
                .when(workspaceRunTriggerRepository).findEnabledBySourceWorkspaceId(right.getId());
        doReturn(List.of()).when(workspaceRunTriggerRepository).findEnabledBySourceWorkspaceId(join.getId());

        subject.ensureCascade(originJob);

        // One createNode call for join, not two - the second edge into it must see it already visited.
        verify(runCascadeNodeRepository).save(argThatNodeFor(join.getId()));
    }

    private RunCascadeNode argThatNodeFor(UUID workspaceId) {
        return org.mockito.ArgumentMatchers.argThat(node -> node != null
                && node.getWorkspace() != null
                && node.getWorkspace().getId().equals(workspaceId));
    }

    // ---------------------------------------------------------------- cascade identity

    @Test
    void ensureCascadeReusesAnExistingCascadeForAJobAlreadyPartOfOne() {
        Job job = job(workspace("w"), 1);
        RunCascade existingCascade = cascade();
        RunCascadeNode existingNode = new RunCascadeNode();
        existingNode.setCascade(existingCascade);
        RunCascadeNodeAttempt attempt = new RunCascadeNodeAttempt();
        attempt.setNode(existingNode);
        doReturn(Optional.of(attempt)).when(runCascadeNodeAttemptRepository).findByJob_Id(job.getId());

        RunCascade result = subject.ensureCascade(job);

        assertThat(result).isSameAs(existingCascade);
        verify(runCascadeRepository, never()).save(any());
    }

    @Test
    void resolveNodeMarksAnExistingNodeSucceededAndIsANoOpOtherwise() {
        Job unrelatedJob = job(workspace("w"), 0);
        doReturn(Optional.empty()).when(runCascadeNodeAttemptRepository).findByJob_Id(unrelatedJob.getId());
        subject.resolveNode(unrelatedJob); // no node, no cascade - must not throw
        verify(runCascadeNodeRepository, never()).save(any());

        Job ownJob = job(workspace("w2"), 0);
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());
        RunCascadeNode node = pendingNode(cascade, ownJob.getWorkspace());
        RunCascadeNodeAttempt attempt = new RunCascadeNodeAttempt();
        attempt.setNode(node);
        doReturn(Optional.of(attempt)).when(runCascadeNodeAttemptRepository).findByJob_Id(ownJob.getId());
        doReturn(List.of(node)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        subject.resolveNode(ownJob);

        assertThat(node.getStatus()).isEqualTo(RunCascadeNodeStatus.SUCCEEDED);
    }

    // ---------------------------------------------------------------- status reduction

    @Test
    void recomputeStatusIsRunningWhileAnyNodeIsUnresolvedAndCompletedOnceNoneAre() {
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());

        RunCascadeNode pending = new RunCascadeNode();
        pending.setStatus(RunCascadeNodeStatus.PENDING);
        RunCascadeNode succeeded = new RunCascadeNode();
        succeeded.setStatus(RunCascadeNodeStatus.SUCCEEDED);

        doReturn(List.of(pending, succeeded)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());
        subject.recomputeStatus(cascade.getId());
        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.RUNNING);

        doReturn(List.of(succeeded)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());
        subject.recomputeStatus(cascade.getId());
        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.COMPLETED);
    }

    private Job job(Workspace workspace, int cascadeDepth) {
        Job job = new Job();
        job.setId(nextJobId++);
        job.setWorkspace(workspace);
        job.setOrganization(workspace.getOrganization());
        job.setCascadeDepth(cascadeDepth);
        return job;
    }
}
