package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
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
 * still owns the hot-path fan-out loop and job creation; this is consulted inline per edge
 * rather than taking it over, so {@code EACH} (the only mode that existed before this) stays
 * byte-for-byte what it was - it is never gated here at all. Operator actions
 * ({@link #retryNode}, {@link #resumeNode}, {@link #cancelCascade}) are this service's own
 * concern, since nothing about them is on the hot path.
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
    private final RunTriggerJobWriter jobWriter;
    private final JobNotificationTrigger jobNotificationTrigger;
    private final ScheduleJobService scheduleJobService;
    private final RunCascadeMetrics metrics;

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
     * Marks this job's own node {@code outcome} (FAILED if it actually ran and failed, SKIPPED
     * if it was cancelled or rejected before running), if it has one, and blocks every PENDING
     * descendant that can now never become ready - an ALL join missing this parent for good, or
     * an ANY join whose every parent has now resolved unsuccessfully. Without this, such a node
     * would stay PENDING forever and the cascade would never leave RUNNING.
     */
    @Transactional
    public void resolveNodeAsUnsuccessful(Job completedJob, RunCascadeNodeStatus outcome) {
        runCascadeNodeAttemptRepository.findByJob_Id(completedJob.getId()).ifPresent(attempt -> {
            RunCascadeNode node = attempt.getNode();
            node.setStatus(outcome);
            runCascadeNodeRepository.save(node);
            propagateBlocking(node.getCascade(), node.getWorkspace());
            recomputeStatus(node.getCascade().getId());
        });
    }

    /**
     * BFS forward from a node that just resolved unsuccessfully, blocking every PENDING
     * descendant whose readiness condition can no longer be satisfied, and propagating from
     * each of those in turn - a BLOCKED node can itself strand further descendants.
     */
    private void propagateBlocking(RunCascade cascade, Workspace unsuccessfulWorkspace) {
        Deque<UUID> frontier = new ArrayDeque<>();
        frontier.push(unsuccessfulWorkspace.getId());

        while (!frontier.isEmpty()) {
            UUID sourceId = frontier.pop();
            for (RunCascadeEdge edge : runCascadeEdgeRepository
                    .findByCascade_IdAndSourceWorkspace_Id(cascade.getId(), sourceId)) {
                Workspace destination = edge.getDestinationWorkspace();
                RunCascadeNode destinationNode = runCascadeNodeRepository
                        .findByCascade_IdAndWorkspace_Id(cascade.getId(), destination.getId())
                        .orElse(null);
                if (destinationNode == null || destinationNode.getStatus() != RunCascadeNodeStatus.PENDING) {
                    continue;
                }
                if (isStranded(cascade, destination)) {
                    destinationNode.setStatus(RunCascadeNodeStatus.BLOCKED);
                    runCascadeNodeRepository.save(destinationNode);
                    frontier.push(destination.getId());
                }
            }
        }
    }

    /** True once this destination's ALL/ANY requirement can never be satisfied by what's left. */
    private boolean isStranded(RunCascade cascade, Workspace destination) {
        List<RunCascadeEdge> inbound = runCascadeEdgeRepository
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), destination.getId());

        List<RunCascadeEdge> allEdges = inbound.stream()
                .filter(e -> e.getSynchronizationMode() == RunTriggerSynchronizationMode.ALL)
                .toList();
        if (!allEdges.isEmpty()) {
            // One required parent gone for good is enough - ALL can never be satisfied again.
            return allEdges.stream().anyMatch(e -> isUnsuccessful(cascade.getId(), e.getSourceWorkspace().getId()));
        }

        List<RunCascadeEdge> anyEdges = inbound.stream()
                .filter(e -> e.getSynchronizationMode() == RunTriggerSynchronizationMode.ANY)
                .toList();
        if (!anyEdges.isEmpty()) {
            // Stranded only once every candidate has resolved unsuccessfully - one still-PENDING
            // or still-RUNNING ANY parent could yet satisfy it.
            return anyEdges.stream().allMatch(e -> isUnsuccessful(cascade.getId(), e.getSourceWorkspace().getId()));
        }
        return false;
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
        metrics.cascadeStarted();
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

    /**
     * Reduces the cascade's status from its nodes': RUNNING while any is still pending or in
     * flight; once none are, BLOCKED if anything needs an operator, else DEGRADED if something
     * failed or was skipped without blocking anything further, else COMPLETED.
     *
     * <p>Leaves a CANCELLED cascade alone - an operator's decision is terminal, and a job that
     * was already in flight when it was cancelled must not resurrect the cascade by completing
     * afterwards.
     */
    @Transactional
    public void recomputeStatus(UUID cascadeId) {
        RunCascade cascade = runCascadeRepository.findById(cascadeId).orElse(null);
        if (cascade == null || cascade.getStatus() == RunCascadeStatus.CANCELLED) {
            return;
        }
        List<RunCascadeNode> nodes = runCascadeNodeRepository.findByCascade_Id(cascadeId);
        boolean anyUnresolved = nodes.stream().anyMatch(n -> n.getStatus() == RunCascadeNodeStatus.PENDING
                || n.getStatus() == RunCascadeNodeStatus.RUNNING);
        boolean anyBlocked = nodes.stream().anyMatch(n -> n.getStatus() == RunCascadeNodeStatus.BLOCKED);
        boolean anyUnsuccessful = nodes.stream().anyMatch(n -> n.getStatus() == RunCascadeNodeStatus.FAILED
                || n.getStatus() == RunCascadeNodeStatus.SKIPPED);

        RunCascadeStatus newStatus;
        if (anyUnresolved) {
            newStatus = RunCascadeStatus.RUNNING;
        } else if (anyBlocked) {
            newStatus = RunCascadeStatus.BLOCKED;
        } else if (anyUnsuccessful) {
            newStatus = RunCascadeStatus.DEGRADED;
        } else {
            newStatus = RunCascadeStatus.COMPLETED;
        }

        if (cascade.getStatus() != newStatus) {
            cascade.setStatus(newStatus);
            runCascadeRepository.save(cascade);
            metrics.cascadeStatusChanged(newStatus);
        }
    }

    // ---------------------------------------------------------------- operator actions

    /** Re-dispatches a failed or skipped node, with a fresh attempt against the same template. */
    @Transactional
    public Job retryNode(UUID nodeId) {
        RunCascadeNode node = runCascadeNodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("No such cascade node: " + nodeId));
        if (node.getStatus() != RunCascadeNodeStatus.FAILED && node.getStatus() != RunCascadeNodeStatus.SKIPPED) {
            throw new IllegalStateException(
                    "Only a failed or skipped node can be retried, not " + node.getStatus());
        }

        RunCascade cascade = node.getCascade();
        Job createdJob = dispatchNode(cascade, node);
        recomputeStatus(cascade.getId());
        metrics.nodeRetried();
        return createdJob;
    }

    /**
     * Un-blocks a node and re-evaluates it against the current state of its parents - an
     * operator calls this after retrying whatever stranded it. A still-unsatisfied requirement
     * (another parent the operator hasn't dealt with yet) leaves it PENDING rather than failing.
     */
    @Transactional
    public Optional<Job> resumeNode(UUID nodeId) {
        RunCascadeNode node = runCascadeNodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("No such cascade node: " + nodeId));
        if (node.getStatus() != RunCascadeNodeStatus.BLOCKED) {
            throw new IllegalStateException("Only a blocked node can be resumed, not " + node.getStatus());
        }

        RunCascade cascade = node.getCascade();
        node.setStatus(RunCascadeNodeStatus.PENDING);
        runCascadeNodeRepository.save(node);

        if (!isReadyNow(cascade, node.getWorkspace())) {
            recomputeStatus(cascade.getId());
            metrics.nodeResumed(false);
            return Optional.empty();
        }

        Job createdJob = dispatchNode(cascade, node);
        recomputeStatus(cascade.getId());
        metrics.nodeResumed(true);
        return Optional.ofNullable(createdJob);
    }

    /**
     * Stops a cascade's future dispatch - every PENDING and BLOCKED node becomes CANCELLED.
     * Does not touch a RUNNING node's in-flight job; that is cancelled independently, the same
     * way any other job is. Returns the cascade so a caller (the admin endpoint) can report its
     * new status without a second lookup - {@code id}/{@code status} only, never a lazy
     * association.
     */
    @Transactional
    public RunCascade cancelCascade(UUID cascadeId) {
        RunCascade cascade = runCascadeRepository.findById(cascadeId)
                .orElseThrow(() -> new IllegalArgumentException("No such cascade: " + cascadeId));

        for (RunCascadeNode node : runCascadeNodeRepository.findByCascade_Id(cascadeId)) {
            if (node.getStatus() == RunCascadeNodeStatus.PENDING || node.getStatus() == RunCascadeNodeStatus.BLOCKED) {
                node.setStatus(RunCascadeNodeStatus.CANCELLED);
                runCascadeNodeRepository.save(node);
            }
        }

        cascade.setStatus(RunCascadeStatus.CANCELLED);
        RunCascade saved = runCascadeRepository.save(cascade);
        metrics.cascadeCancelled();
        return saved;
    }

    /** Whether every edge this destination actually depends on would currently let it through. */
    private boolean isReadyNow(RunCascade cascade, Workspace destination) {
        List<RunCascadeEdge> inbound = runCascadeEdgeRepository
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), destination.getId());

        List<RunCascadeEdge> allEdges = inbound.stream()
                .filter(e -> e.getSynchronizationMode() == RunTriggerSynchronizationMode.ALL)
                .toList();
        if (!allEdges.isEmpty()) {
            return allEdges.stream().allMatch(e -> isSucceeded(cascade.getId(), e.getSourceWorkspace().getId()));
        }
        return inbound.stream()
                .filter(e -> e.getSynchronizationMode() == RunTriggerSynchronizationMode.ANY)
                .anyMatch(e -> isSucceeded(cascade.getId(), e.getSourceWorkspace().getId()));
    }

    /**
     * Dispatches a fresh attempt for {@code node}, resolving its template the same way the
     * first attempt did. Operator-triggered, not on the hot fan-out loop's own per-dependent
     * try/catch, so a scheduling failure here is reported as an unchecked exception rather than
     * silently logged.
     */
    private Job dispatchNode(RunCascade cascade, RunCascadeNode node) {
        Workspace destination = node.getWorkspace();
        String templateReference = resolveTemplateForNode(cascade, destination);
        if (templateReference == null || templateReference.isBlank()) {
            throw new IllegalStateException(
                    "Workspace " + destination.getName() + " has no template to dispatch with");
        }

        Job createdJob = jobWriter.persist(destination, templateReference, cascade.getOriginJob(), node.getDepth());
        jobNotificationTrigger.notifyStatusChanged(createdJob);
        try {
            scheduleJobService.createJobContext(createdJob);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Job " + createdJob.getId() + " was created but could not be scheduled: " + e.getMessage(), e);
        }

        node.setStatus(RunCascadeNodeStatus.RUNNING);
        runCascadeNodeRepository.save(node);
        recordAttempt(node, createdJob);
        return createdJob;
    }

    /**
     * The inbound edge's own trigger wins (an operator-visible, explicitly-configured choice),
     * then the destination's default - same precedence {@code RunTriggerDispatchService} uses
     * for a first attempt. The live trigger can be gone (the FK is {@code SET NULL}) if it was
     * deleted since the snapshot was taken, in which case this falls through to the default.
     */
    private String resolveTemplateForNode(RunCascade cascade, Workspace destination) {
        return runCascadeEdgeRepository
                .findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), destination.getId())
                .stream()
                .map(RunCascadeEdge::getSourceTrigger)
                .filter(java.util.Objects::nonNull)
                .map(WorkspaceRunTrigger::getTemplate)
                .filter(java.util.Objects::nonNull)
                .map(template -> template.getId().toString())
                .findFirst()
                .orElseGet(destination::getDefaultTemplate);
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

    /** Resolved, but not successfully - gone for good unless an operator retries it. */
    private boolean isUnsuccessful(UUID cascadeId, UUID workspaceId) {
        return runCascadeNodeRepository.findByCascade_IdAndWorkspace_Id(cascadeId, workspaceId)
                .map(n -> n.getStatus() == RunCascadeNodeStatus.FAILED
                        || n.getStatus() == RunCascadeNodeStatus.SKIPPED
                        || n.getStatus() == RunCascadeNodeStatus.BLOCKED
                        || n.getStatus() == RunCascadeNodeStatus.CANCELLED)
                .orElse(false);
    }

    private void markRunning(RunCascadeNode node) {
        node.setStatus(RunCascadeNodeStatus.RUNNING);
        runCascadeNodeRepository.save(node);
    }
}
