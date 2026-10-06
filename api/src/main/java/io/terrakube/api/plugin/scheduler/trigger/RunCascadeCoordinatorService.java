package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.RunCascadeEdgeRepository;
import io.terrakube.api.repository.RunCascadeNodeAttemptRepository;
import io.terrakube.api.repository.RunCascadeNodeRepository;
import io.terrakube.api.repository.RunCascadeRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.rs.cascade.RunCascade;
import io.terrakube.api.rs.cascade.RunCascadeEdge;
import io.terrakube.api.rs.cascade.RunCascadeNode;
import io.terrakube.api.rs.cascade.RunCascadeNodeAttempt;
import io.terrakube.api.rs.cascade.RunCascadeNodeStatus;
import io.terrakube.api.rs.cascade.RunCascadeStatus;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.RunTriggerSynchronizationMode;
import io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Ties a propagation across workspaces into one {@link RunCascade}, and decides whether an
 * {@code ANY}/{@code ALL} edge is actually ready to dispatch. {@link RunTriggerDispatchService}
 * still owns the fan-out loop and job creation; this is consulted inline per edge rather than
 * taking it over, so {@code EACH} (the only mode that existed before this) stays byte-for-byte
 * what it was - it is never gated here at all.
 *
 * <p>Known gap, left for a later slice (terrakube-io/terrakube#3629's "Operations" breakdown):
 * a node only ever resolves to {@code SUCCEEDED}, via {@link #resolveNode}, because that is the
 * only outcome that reaches this service today - {@code JobReconciliationService} writes a run
 * trigger event solely on a {@code completed} transition. A downstream job that fails leaves
 * its node {@code RUNNING} until that slice adds failure propagation.
 */
@Slf4j
@Service
@AllArgsConstructor
public class RunCascadeCoordinatorService {

    private final RunCascadeRepository runCascadeRepository;
    private final RunCascadeNodeRepository runCascadeNodeRepository;
    private final RunCascadeEdgeRepository runCascadeEdgeRepository;
    private final RunCascadeNodeAttemptRepository runCascadeNodeAttemptRepository;
    private final WorkspaceRunTriggerRepository workspaceRunTriggerRepository;
    private final RunTriggerProperties properties;

    /**
     * Marks this job's own node resolved, if it has one - a job outside any cascade (the common
     * case today, while most edges are still {@code EACH}) is a no-op. Called for every job
     * reaching {@link RunTriggerDispatchService#dispatchInternal}, before the state-identity
     * check: a node that produced no further change still succeeded and must not stay
     * {@code RUNNING} forever waiting for a fan-out that was never going to happen.
     */
    @Transactional
    public void resolveNode(Job completedJob) {
        runCascadeNodeAttemptRepository.findByJob_Id(completedJob.getId()).ifPresent(attempt -> {
            RunCascadeNode node = attempt.getNode();
            if (node.getStatus() != RunCascadeNodeStatus.SUCCEEDED) {
                node.setStatus(RunCascadeNodeStatus.SUCCEEDED);
                runCascadeNodeRepository.save(node);
            }
            recomputeStatus(node.getCascade().getId());
        });
    }

    /**
     * Finds the cascade this job already belongs to (a chained completion continuing one a
     * prior hop started), or starts a new one with a full snapshot of what is reachable from
     * here right now. The snapshot is taken once, up front: {@code ALL} can only ever evaluate
     * correctly if every parent a join node might wait on is already known, not discovered one
     * dispatch at a time.
     */
    @Transactional
    public RunCascade ensureCascade(Job completedJob) {
        Optional<RunCascadeNodeAttempt> existing = runCascadeNodeAttemptRepository.findByJob_Id(completedJob.getId());
        if (existing.isPresent()) {
            return existing.get().getNode().getCascade();
        }

        RunCascade cascade = new RunCascade();
        cascade.setId(UUID.randomUUID());
        cascade.setOriginJob(completedJob);
        cascade.setOrganization(completedJob.getOrganization());
        cascade.setStatus(RunCascadeStatus.RUNNING);
        cascade = runCascadeRepository.save(cascade);

        RunCascadeNode originNode = createNode(cascade, completedJob.getWorkspace(), completedJob.getCascadeDepth());
        originNode.setStatus(RunCascadeNodeStatus.SUCCEEDED);
        runCascadeNodeRepository.save(originNode);
        recordAttempt(originNode, completedJob);

        snapshotReachableGraph(cascade, completedJob.getWorkspace(), completedJob.getCascadeDepth());
        return cascade;
    }

    /**
     * {@code EACH} dispatches every time, unconditionally - today's behavior, preserved exactly.
     * {@code ANY}/{@code ALL} dispatch their destination node at most once per cascade: {@code ANY}
     * on the first parent to succeed, {@code ALL} only once every parent connected by an
     * {@code ALL} edge into that node has.
     */
    @Transactional
    public boolean shouldDispatch(RunCascade cascade, WorkspaceRunTrigger trigger) {
        if (trigger.getSynchronizationMode() == RunTriggerSynchronizationMode.EACH) {
            return true;
        }

        Workspace destination = trigger.getDestinationWorkspace();
        RunCascadeNode node = runCascadeNodeRepository
                .findByCascade_IdAndWorkspace_Id(cascade.getId(), destination.getId())
                .orElse(null);
        // Missing would mean the destination isn't part of this cascade's snapshot at all,
        // which should never happen for an edge the snapshot itself was built from.
        if (node == null || node.getStatus() != RunCascadeNodeStatus.PENDING) {
            return false;
        }

        if (trigger.getSynchronizationMode() == RunTriggerSynchronizationMode.ANY) {
            markRunning(node);
            return true;
        }

        List<RunCascadeEdge> inbound = runCascadeEdgeRepository
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), destination.getId());
        boolean everyAllParentSucceeded = inbound.stream()
                .filter(e -> e.getSynchronizationMode() == RunTriggerSynchronizationMode.ALL)
                .allMatch(e -> isSucceeded(cascade.getId(), e.getSourceWorkspace().getId()));
        if (everyAllParentSucceeded) {
            markRunning(node);
            return true;
        }
        return false;
    }

    /** Records the attempt just dispatched for this edge's destination, within the snapshot. */
    @Transactional
    public void recordDispatch(RunCascade cascade, WorkspaceRunTrigger trigger, Job createdJob) {
        runCascadeNodeRepository
                .findByCascade_IdAndWorkspace_Id(cascade.getId(), trigger.getDestinationWorkspace().getId())
                .ifPresent(node -> recordAttempt(node, createdJob));
    }

    /** Reduces the cascade's status from its nodes': RUNNING while any is unresolved, COMPLETED once none are. */
    @Transactional
    public void recomputeStatus(UUID cascadeId) {
        RunCascade cascade = runCascadeRepository.findById(cascadeId).orElse(null);
        if (cascade == null) {
            return;
        }
        List<RunCascadeNode> nodes = runCascadeNodeRepository.findByCascade_Id(cascadeId);
        boolean anyUnresolved = nodes.stream().anyMatch(n -> n.getStatus() == RunCascadeNodeStatus.PENDING
                || n.getStatus() == RunCascadeNodeStatus.RUNNING);
        RunCascadeStatus newStatus = anyUnresolved ? RunCascadeStatus.RUNNING : RunCascadeStatus.COMPLETED;
        if (cascade.getStatus() != newStatus) {
            cascade.setStatus(newStatus);
            runCascadeRepository.save(cascade);
        }
    }

    /**
     * BFS from {@code origin}, recording every reachable enabled edge and creating a
     * {@code PENDING} node for every workspace it reaches - including a diamond's join
     * workspace exactly once, which is what lets {@code ANY}/{@code ALL} evaluate it as a
     * single node rather than once per incoming edge. Never expands past the configured cascade
     * depth, matching the limit dispatch itself already enforces.
     */
    private void snapshotReachableGraph(RunCascade cascade, Workspace origin, int originDepth) {
        Set<UUID> visited = new HashSet<>();
        visited.add(origin.getId());
        Map<UUID, Integer> depthOf = new HashMap<>();
        depthOf.put(origin.getId(), originDepth);
        Deque<Workspace> frontier = new ArrayDeque<>();
        frontier.push(origin);

        while (!frontier.isEmpty()) {
            Workspace current = frontier.pop();
            int currentDepth = depthOf.get(current.getId());
            if (currentDepth >= properties.getMaxCascadeDepth()) {
                continue;
            }
            for (WorkspaceRunTrigger trigger : workspaceRunTriggerRepository.findEnabledBySourceWorkspaceId(current.getId())) {
                Workspace destination = trigger.getDestinationWorkspace();
                saveEdgeSnapshot(cascade, trigger);
                if (visited.add(destination.getId())) {
                    int childDepth = currentDepth + 1;
                    depthOf.put(destination.getId(), childDepth);
                    createNode(cascade, destination, childDepth);
                    frontier.push(destination);
                }
            }
        }
    }

    private RunCascadeNode createNode(RunCascade cascade, Workspace workspace, int depth) {
        RunCascadeNode node = new RunCascadeNode();
        node.setId(UUID.randomUUID());
        node.setCascade(cascade);
        node.setWorkspace(workspace);
        node.setDepth(depth);
        node.setStatus(RunCascadeNodeStatus.PENDING);
        return runCascadeNodeRepository.save(node);
    }

    private void saveEdgeSnapshot(RunCascade cascade, WorkspaceRunTrigger trigger) {
        RunCascadeEdge edge = new RunCascadeEdge();
        edge.setId(UUID.randomUUID());
        edge.setCascade(cascade);
        edge.setSourceWorkspace(trigger.getSourceWorkspace());
        edge.setDestinationWorkspace(trigger.getDestinationWorkspace());
        edge.setSynchronizationMode(trigger.getSynchronizationMode());
        edge.setOnDestroy(trigger.getOnDestroy());
        edge.setSourceTrigger(trigger);
        runCascadeEdgeRepository.save(edge);
    }

    private void recordAttempt(RunCascadeNode node, Job job) {
        int attemptNumber = runCascadeNodeAttemptRepository.findByNode_IdOrderByAttemptNumberAsc(node.getId()).size() + 1;
        RunCascadeNodeAttempt attempt = new RunCascadeNodeAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setNode(node);
        attempt.setJob(job);
        attempt.setAttemptNumber(attemptNumber);
        runCascadeNodeAttemptRepository.save(attempt);
    }

    private boolean isSucceeded(UUID cascadeId, UUID workspaceId) {
        return runCascadeNodeRepository.findByCascade_IdAndWorkspace_Id(cascadeId, workspaceId)
                .map(n -> n.getStatus() == RunCascadeNodeStatus.SUCCEEDED)
                .orElse(false);
    }

    private void markRunning(RunCascadeNode node) {
        node.setStatus(RunCascadeNodeStatus.RUNNING);
        runCascadeNodeRepository.save(node);
    }
}
