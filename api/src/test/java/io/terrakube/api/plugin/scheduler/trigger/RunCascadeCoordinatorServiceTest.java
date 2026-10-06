package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
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
    RunTriggerJobWriter jobWriter;
    JobNotificationTrigger jobNotificationTrigger;
    ScheduleJobService scheduleJobService;
    RunCascadeMetrics metrics;
    RunCascadeCoordinatorService subject;

    private int nextJobId;

    @BeforeEach
    void setup() throws Exception {
        nextJobId = 100;
        runCascadeRepository = mock(RunCascadeRepository.class);
        runCascadeNodeRepository = mock(RunCascadeNodeRepository.class);
        runCascadeEdgeRepository = mock(RunCascadeEdgeRepository.class);
        runCascadeNodeAttemptRepository = mock(RunCascadeNodeAttemptRepository.class);
        workspaceRunTriggerRepository = mock(WorkspaceRunTriggerRepository.class);
        properties = new RunTriggerProperties();
        jobWriter = mock(RunTriggerJobWriter.class);
        jobNotificationTrigger = mock(JobNotificationTrigger.class);
        scheduleJobService = mock(ScheduleJobService.class);
        metrics = mock(RunCascadeMetrics.class);

        // Identity save: these tests assert on the entities themselves, not on round-tripping
        // through a real database.
        lenientIdentitySave(runCascadeRepository);
        lenientIdentitySave(runCascadeNodeRepository);
        lenientIdentitySave(runCascadeEdgeRepository);
        lenientIdentitySave(runCascadeNodeAttemptRepository);

        subject = new RunCascadeCoordinatorService(runCascadeRepository, runCascadeNodeRepository,
                runCascadeEdgeRepository, runCascadeNodeAttemptRepository, workspaceRunTriggerRepository,
                properties, jobWriter, jobNotificationTrigger, scheduleJobService, metrics);
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
        verify(metrics).cascadeStarted();
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

    // ---------------------------------------------------------------- failure propagation

    private RunCascadeNodeAttempt attachAttempt(RunCascadeNode node, Job job) {
        RunCascadeNodeAttempt attempt = new RunCascadeNodeAttempt();
        attempt.setNode(node);
        doReturn(Optional.of(attempt)).when(runCascadeNodeAttemptRepository).findByJob_Id(job.getId());
        return attempt;
    }

    @Test
    void resolveNodeAsUnsuccessfulMarksTheOutcomeAndRecomputesStatus() {
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());
        Workspace workspace = workspace("w");
        Job failedJob = job(workspace, 0);
        RunCascadeNode node = pendingNode(cascade, workspace);
        node.setStatus(RunCascadeNodeStatus.RUNNING);
        attachAttempt(node, failedJob);
        doReturn(List.of()).when(runCascadeEdgeRepository)
                .findByCascade_IdAndSourceWorkspace_Id(cascade.getId(), workspace.getId());
        doReturn(List.of(node)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        subject.resolveNodeAsUnsuccessful(failedJob, RunCascadeNodeStatus.FAILED);

        assertThat(node.getStatus()).isEqualTo(RunCascadeNodeStatus.FAILED);
        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.DEGRADED);
    }

    @Test
    void allJoinIsBlockedOnceARequiredParentResolvesUnsuccessfully() {
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());
        Workspace parent = workspace("parent");
        Workspace join = workspace("join");
        Job failedJob = job(parent, 0);

        RunCascadeNode parentNode = pendingNode(cascade, parent);
        parentNode.setStatus(RunCascadeNodeStatus.RUNNING);
        attachAttempt(parentNode, failedJob);
        RunCascadeNode joinNode = pendingNode(cascade, join);

        WorkspaceRunTrigger parentToJoin = trigger(parent, join, RunTriggerSynchronizationMode.ALL);
        RunCascadeEdge edge = edge(cascade, parentToJoin);
        doReturn(List.of(edge)).when(runCascadeEdgeRepository)
                .findByCascade_IdAndSourceWorkspace_Id(cascade.getId(), parent.getId());
        doReturn(List.of(edge)).when(runCascadeEdgeRepository)
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), join.getId());
        doReturn(List.of()).when(runCascadeEdgeRepository)
                .findByCascade_IdAndSourceWorkspace_Id(cascade.getId(), join.getId());
        doReturn(List.of(parentNode, joinNode)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        subject.resolveNodeAsUnsuccessful(failedJob, RunCascadeNodeStatus.FAILED);

        assertThat(joinNode.getStatus()).isEqualTo(RunCascadeNodeStatus.BLOCKED);
        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.BLOCKED);
    }

    @Test
    void anyJoinStaysPendingWhileAnotherCandidateCouldStillSucceed() {
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());
        Workspace left = workspace("left");
        Workspace right = workspace("right");
        Workspace join = workspace("join");
        Job leftFailedJob = job(left, 0);

        RunCascadeNode leftNode = pendingNode(cascade, left);
        leftNode.setStatus(RunCascadeNodeStatus.RUNNING);
        attachAttempt(leftNode, leftFailedJob);
        nodeWithStatus(cascade, right, RunCascadeNodeStatus.PENDING);
        RunCascadeNode joinNode = pendingNode(cascade, join);

        WorkspaceRunTrigger leftToJoin = trigger(left, join, RunTriggerSynchronizationMode.ANY);
        WorkspaceRunTrigger rightToJoin = trigger(right, join, RunTriggerSynchronizationMode.ANY);
        doReturn(List.of(edge(cascade, leftToJoin), edge(cascade, rightToJoin))).when(runCascadeEdgeRepository)
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), join.getId());
        doReturn(List.of(edge(cascade, leftToJoin))).when(runCascadeEdgeRepository)
                .findByCascade_IdAndSourceWorkspace_Id(cascade.getId(), left.getId());
        doReturn(List.of(leftNode, joinNode)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        subject.resolveNodeAsUnsuccessful(leftFailedJob, RunCascadeNodeStatus.FAILED);

        // Right hasn't resolved yet, so the join is not yet stranded.
        assertThat(joinNode.getStatus()).isEqualTo(RunCascadeNodeStatus.PENDING);
    }

    @Test
    void anyJoinIsBlockedOnceEveryCandidateHasResolvedUnsuccessfully() {
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());
        Workspace left = workspace("left");
        Workspace right = workspace("right");
        Workspace join = workspace("join");
        Job rightFailedJob = job(right, 0);

        nodeWithStatus(cascade, left, RunCascadeNodeStatus.FAILED);
        RunCascadeNode rightNode = pendingNode(cascade, right);
        rightNode.setStatus(RunCascadeNodeStatus.RUNNING);
        attachAttempt(rightNode, rightFailedJob);
        RunCascadeNode joinNode = pendingNode(cascade, join);

        WorkspaceRunTrigger leftToJoin = trigger(left, join, RunTriggerSynchronizationMode.ANY);
        WorkspaceRunTrigger rightToJoin = trigger(right, join, RunTriggerSynchronizationMode.ANY);
        doReturn(List.of(edge(cascade, leftToJoin), edge(cascade, rightToJoin))).when(runCascadeEdgeRepository)
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), join.getId());
        doReturn(List.of(edge(cascade, rightToJoin))).when(runCascadeEdgeRepository)
                .findByCascade_IdAndSourceWorkspace_Id(cascade.getId(), right.getId());
        doReturn(List.of(joinNode)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        subject.resolveNodeAsUnsuccessful(rightFailedJob, RunCascadeNodeStatus.SKIPPED);

        assertThat(joinNode.getStatus()).isEqualTo(RunCascadeNodeStatus.BLOCKED);
    }

    @Test
    void blockingPropagatesTransitivelyToAGrandchildJoin() {
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());
        Workspace a = workspace("a");
        Workspace b = workspace("b");
        Workspace c = workspace("c");
        Job aFailedJob = job(a, 0);

        RunCascadeNode nodeA = pendingNode(cascade, a);
        nodeA.setStatus(RunCascadeNodeStatus.RUNNING);
        attachAttempt(nodeA, aFailedJob);
        RunCascadeNode nodeB = pendingNode(cascade, b);
        RunCascadeNode nodeC = pendingNode(cascade, c);

        WorkspaceRunTrigger aToB = trigger(a, b, RunTriggerSynchronizationMode.ALL);
        WorkspaceRunTrigger bToC = trigger(b, c, RunTriggerSynchronizationMode.ALL);
        RunCascadeEdge edgeAB = edge(cascade, aToB);
        RunCascadeEdge edgeBC = edge(cascade, bToC);

        doReturn(List.of(edgeAB)).when(runCascadeEdgeRepository)
                .findByCascade_IdAndSourceWorkspace_Id(cascade.getId(), a.getId());
        doReturn(List.of(edgeBC)).when(runCascadeEdgeRepository)
                .findByCascade_IdAndSourceWorkspace_Id(cascade.getId(), b.getId());
        doReturn(List.of()).when(runCascadeEdgeRepository)
                .findByCascade_IdAndSourceWorkspace_Id(cascade.getId(), c.getId());
        doReturn(List.of(edgeAB)).when(runCascadeEdgeRepository)
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), b.getId());
        doReturn(List.of(edgeBC)).when(runCascadeEdgeRepository)
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), c.getId());
        doReturn(List.of(nodeA, nodeB, nodeC)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        subject.resolveNodeAsUnsuccessful(aFailedJob, RunCascadeNodeStatus.FAILED);

        assertThat(nodeB.getStatus()).isEqualTo(RunCascadeNodeStatus.BLOCKED);
        assertThat(nodeC.getStatus()).isEqualTo(RunCascadeNodeStatus.BLOCKED);
    }

    // ---------------------------------------------------------------- status reduction (blocked/degraded)

    @Test
    void recomputeStatusIsBlockedWhenAnythingIsBlockedEvenAlongsideAFailure() {
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());

        RunCascadeNode failed = new RunCascadeNode();
        failed.setStatus(RunCascadeNodeStatus.FAILED);
        RunCascadeNode blocked = new RunCascadeNode();
        blocked.setStatus(RunCascadeNodeStatus.BLOCKED);

        doReturn(List.of(failed, blocked)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());
        subject.recomputeStatus(cascade.getId());

        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.BLOCKED);
    }

    @Test
    void recomputeStatusIsDegradedWhenNothingIsBlockedButSomethingFailed() {
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());

        RunCascadeNode succeeded = new RunCascadeNode();
        succeeded.setStatus(RunCascadeNodeStatus.SUCCEEDED);
        RunCascadeNode skipped = new RunCascadeNode();
        skipped.setStatus(RunCascadeNodeStatus.SKIPPED);

        doReturn(List.of(succeeded, skipped)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());
        subject.recomputeStatus(cascade.getId());

        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.DEGRADED);
    }

    @Test
    void recomputeStatusLeavesACancelledCascadeAlone() {
        RunCascade cascade = cascade();
        cascade.setStatus(RunCascadeStatus.CANCELLED);
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());

        subject.recomputeStatus(cascade.getId());

        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.CANCELLED);
        verify(runCascadeNodeRepository, never()).findByCascade_Id(any());
    }

    // ---------------------------------------------------------------- operator actions

    private Workspace destinationWorkspaceWithTemplate(String name) {
        Workspace ws = workspace(name);
        ws.setDefaultTemplate("default-template-id");
        return ws;
    }

    @Test
    void retryNodeDispatchesAFreshAttemptAndClearsBackToRunning() throws Exception {
        RunCascade cascade = cascade();
        cascade.setOriginJob(job(workspace("origin"), 0));
        Workspace destination = destinationWorkspaceWithTemplate("destination");
        RunCascadeNode node = pendingNode(cascade, destination);
        node.setStatus(RunCascadeNodeStatus.FAILED);
        doReturn(Optional.of(node)).when(runCascadeNodeRepository).findById(node.getId());
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());
        doReturn(List.of()).when(runCascadeEdgeRepository)
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), destination.getId());
        doReturn(List.of(node)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        Job retryJob = job(destination, 1);
        doReturn(retryJob).when(jobWriter).persist(destination, "default-template-id", cascade.getOriginJob(), node.getDepth());

        Job result = subject.retryNode(node.getId());

        assertThat(result).isSameAs(retryJob);
        assertThat(node.getStatus()).isEqualTo(RunCascadeNodeStatus.RUNNING);
        verify(scheduleJobService).createJobContext(retryJob);
        verify(jobNotificationTrigger).notifyStatusChanged(retryJob);
        verify(metrics).nodeRetried();
    }

    @Test
    void retryNodeRejectsANodeThatIsNotFailedOrSkipped() {
        RunCascade cascade = cascade();
        RunCascadeNode node = pendingNode(cascade, workspace("w"));
        node.setStatus(RunCascadeNodeStatus.RUNNING);
        doReturn(Optional.of(node)).when(runCascadeNodeRepository).findById(node.getId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> subject.retryNode(node.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resumeNodeDispatchesWhenItsRequirementIsNowSatisfied() throws Exception {
        RunCascade cascade = cascade();
        cascade.setOriginJob(job(workspace("origin"), 0));
        Workspace parent = workspace("parent");
        Workspace destination = destinationWorkspaceWithTemplate("destination");
        RunCascadeNode node = pendingNode(cascade, destination);
        node.setStatus(RunCascadeNodeStatus.BLOCKED);
        doReturn(Optional.of(node)).when(runCascadeNodeRepository).findById(node.getId());
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());

        nodeWithStatus(cascade, parent, RunCascadeNodeStatus.SUCCEEDED);
        WorkspaceRunTrigger parentToDestination = trigger(parent, destination, RunTriggerSynchronizationMode.ALL);
        doReturn(List.of(edge(cascade, parentToDestination))).when(runCascadeEdgeRepository)
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), destination.getId());
        doReturn(List.of(node)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        Job resumedJob = job(destination, 1);
        doReturn(resumedJob).when(jobWriter).persist(destination, "default-template-id", cascade.getOriginJob(), node.getDepth());

        Optional<Job> result = subject.resumeNode(node.getId());

        assertThat(result).containsSame(resumedJob);
        assertThat(node.getStatus()).isEqualTo(RunCascadeNodeStatus.RUNNING);
        verify(metrics).nodeResumed(true);
    }

    @Test
    void resumeNodeStaysPendingWhenStillNotReady() {
        RunCascade cascade = cascade();
        Workspace parent = workspace("parent");
        Workspace destination = workspace("destination");
        RunCascadeNode node = pendingNode(cascade, destination);
        node.setStatus(RunCascadeNodeStatus.BLOCKED);
        doReturn(Optional.of(node)).when(runCascadeNodeRepository).findById(node.getId());
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());

        nodeWithStatus(cascade, parent, RunCascadeNodeStatus.PENDING);
        WorkspaceRunTrigger parentToDestination = trigger(parent, destination, RunTriggerSynchronizationMode.ALL);
        doReturn(List.of(edge(cascade, parentToDestination))).when(runCascadeEdgeRepository)
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), destination.getId());
        doReturn(List.of(node)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        Optional<Job> result = subject.resumeNode(node.getId());

        assertThat(result).isEmpty();
        assertThat(node.getStatus()).isEqualTo(RunCascadeNodeStatus.PENDING);
        verify(jobWriter, never()).persist(any(), any(), any(), eq(node.getDepth()));
        verify(metrics).nodeResumed(false);
    }

    @Test
    void resumeNodeRejectsANodeThatIsNotBlocked() {
        RunCascade cascade = cascade();
        RunCascadeNode node = pendingNode(cascade, workspace("w"));
        node.setStatus(RunCascadeNodeStatus.PENDING);
        doReturn(Optional.of(node)).when(runCascadeNodeRepository).findById(node.getId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> subject.resumeNode(node.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelCascadeCancelsPendingAndBlockedNodesButLeavesRunningAlone() {
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());

        RunCascadeNode pending = new RunCascadeNode();
        pending.setStatus(RunCascadeNodeStatus.PENDING);
        RunCascadeNode blocked = new RunCascadeNode();
        blocked.setStatus(RunCascadeNodeStatus.BLOCKED);
        RunCascadeNode running = new RunCascadeNode();
        running.setStatus(RunCascadeNodeStatus.RUNNING);
        doReturn(List.of(pending, blocked, running)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        subject.cancelCascade(cascade.getId());

        assertThat(pending.getStatus()).isEqualTo(RunCascadeNodeStatus.CANCELLED);
        assertThat(blocked.getStatus()).isEqualTo(RunCascadeNodeStatus.CANCELLED);
        assertThat(running.getStatus()).isEqualTo(RunCascadeNodeStatus.RUNNING);
        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.CANCELLED);
        verify(metrics).cascadeCancelled();
    }

    @Test
    void recomputeStatusReportsEachTransitionOnceNotOnEveryUnchangedCall() {
        RunCascade cascade = cascade();
        doReturn(Optional.of(cascade)).when(runCascadeRepository).findById(cascade.getId());

        RunCascadeNode succeeded = new RunCascadeNode();
        succeeded.setStatus(RunCascadeNodeStatus.SUCCEEDED);
        doReturn(List.of(succeeded)).when(runCascadeNodeRepository).findByCascade_Id(cascade.getId());

        subject.recomputeStatus(cascade.getId());
        subject.recomputeStatus(cascade.getId());

        assertThat(cascade.getStatus()).isEqualTo(RunCascadeStatus.COMPLETED);
        verify(metrics, org.mockito.Mockito.times(1)).cascadeStatusChanged(RunCascadeStatus.COMPLETED);
    }
}
