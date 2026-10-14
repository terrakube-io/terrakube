package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.cascade.*;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.trigger.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.ObjectAlreadyExistsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * Serializes execution transitions on the cascade row. A job and its attempt are one durable
 * dispatch intent: both commit before Quartz is called. Event replay and the existing job
 * reconciliation sweep recover scheduling failures without losing or duplicating that intent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
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
    private final RunCascadeNotificationTrigger cascadeNotificationTrigger;
    private final JobRepository jobRepository;
    private final TemplateRepository templateRepository;
    private final TclService tclService;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<RunCascade> findCascade(int jobId) {
        return runCascadeNodeAttemptRepository.findByJob_Id(jobId).map(a -> a.getNode().getCascade());
    }

    @Transactional
    public RunCascade ensureCascade(Job completedJob) {
        // Lock before the existence check: overlapping event leases must share one snapshot.
        jobRepository.lockForUpdate(completedJob.getId());
        Optional<RunCascadeNodeAttempt> existing = runCascadeNodeAttemptRepository.findByJob_Id(completedJob.getId());
        if (existing.isPresent()) return existing.get().getNode().getCascade();
        Optional<RunCascade> origin = runCascadeRepository.findByOriginJob_Id(completedJob.getId());
        if (origin.isPresent()) return origin.get();
        RunCascade cascade = new RunCascade();
        cascade.setId(UUID.randomUUID());
        cascade.setOriginJob(completedJob);
        cascade.setOrganization(completedJob.getOrganization());
        cascade = runCascadeRepository.save(cascade);
        RunCascadeNode root = createNode(cascade, completedJob.getWorkspace(), completedJob.getCascadeDepth());
        root.setStatus(RunCascadeNodeStatus.SUCCEEDED);
        recordAttempt(root, completedJob, RunCascadeNodeStatus.SUCCEEDED);
        snapshotReachableGraph(cascade, completedJob.getWorkspace(), completedJob.getCascadeDepth());
        metrics.cascadeStarted();
        return cascade;
    }

    /** Materialize frozen configuration while the Hibernate session is open. */
    @Transactional(readOnly = true)
    public List<WorkspaceRunTrigger> snapshotTriggers(UUID cascadeId, UUID sourceId) {
        return runCascadeEdgeRepository.findByCascade_IdAndSourceWorkspace_Id(cascadeId, sourceId).stream()
                .map(edge -> {
                    WorkspaceRunTrigger trigger = new WorkspaceRunTrigger();
                    trigger.setId(edge.getId()); // Snapshot identity, never a live trigger lookup.
                    trigger.setSourceWorkspace(edge.getSourceWorkspace());
                    trigger.setDestinationWorkspace(edge.getDestinationWorkspace());
                    trigger.getDestinationWorkspace().getOrganization().getId();
                    trigger.setSynchronizationMode(edge.getSynchronizationMode());
                    trigger.setOnDestroy(edge.getOnDestroy());
                    trigger.setTemplate(frozenTemplate(edge.getTemplateReference(), edge.getTemplateTcl()));
                    trigger.setOnDestroyPlanTemplate(frozenTemplate(edge.getPlanTemplateReference(), edge.getPlanTemplateTcl()));
                    return trigger;
                }).toList();
    }

    private Template frozenTemplate(String reference, String tcl) {
        if (reference == null || tcl == null) return null;
        Template template = new Template();
        template.setId(UUID.fromString(reference));
        template.setTcl(tcl);
        return template;
    }

    /**
     * Resolve one dependency and, when ready, atomically create its job and attempt. A no-change
     * or ignored dependency is resolved without dispatching. ALL waits for every parent to
     * resolve successfully and runs when at least one contributes an eligible change.
     */
    @Transactional
    public Job enqueue(RunCascade detached, WorkspaceRunTrigger snapshot, Job parent, int depth,
                       boolean wasDestroy, boolean changedState) {
        RunCascade cascade = lockCascade(detached.getId());
        if (cascade.getStatus() == RunCascadeStatus.CANCELLED) return null;
        RunCascadeEdge edge = runCascadeEdgeRepository.findById(snapshot.getId()).orElseThrow();
        RunCascadeNode node = node(cascade, edge.getDestinationWorkspace());
        // Replay may arrive after downstream completion. Return the committed job so the caller
        // can repair missing Quartz scheduling; do not append a second attempt or change outcome.
        Optional<Job> prior = attempts(node).stream().map(RunCascadeNodeAttempt::getJob)
                .filter(job -> Objects.equals(job.getTriggeredByJobId(), parent.getId())).findFirst();
        if (prior.isPresent()) return prior.get();

        boolean eligible = changedState && !(wasDestroy && edge.getOnDestroy() == RunTriggerOnDestroyPolicy.IGNORE);
        if (!eligible) {
            edge.setSkipRequested(true);
            RunCascadeNode source = node(cascade, edge.getSourceWorkspace());
            if (hasFutureInputs(cascade, source)) return null;
            if (edge.getStatus() == RunCascadeEdgeStatus.PENDING) edge.setStatus(RunCascadeEdgeStatus.SKIPPED);
            settleWaiting(cascade);
            if (edge.getSynchronizationMode() != RunTriggerSynchronizationMode.ALL) {
                recomputeLocked(cascade);
                return null;
            }
        }
        if (eligible && wasDestroy && edge.getOnDestroy() == RunTriggerOnDestroyPolicy.BLOCK) {
            edge.setStatus(RunCascadeEdgeStatus.BLOCKED);
            if (attempts(node).isEmpty()) {
                node.setStatus(RunCascadeNodeStatus.BLOCKED);
                node.setReason("Upstream infrastructure was destroyed; dependent run is paused");
                runCascadeNodeRepository.save(node);
            }
            settleWaiting(cascade);
            recomputeLocked(cascade);
            metrics.nodeBlocked();
            return null;
        }
        if (eligible) {
            edge.setSkipRequested(false);
            edge.setStatus(RunCascadeEdgeStatus.SATISFIED);
            edge.setPlanOnly(wasDestroy && edge.getOnDestroy() == RunTriggerOnDestroyPolicy.PLAN_ONLY);
            runCascadeEdgeRepository.save(edge);
        }
        if (node.getStatus() == RunCascadeNodeStatus.BLOCKED || node.getStatus() == RunCascadeNodeStatus.CANCELLED
                || !isReadyNow(cascade, node.getWorkspace())) {
            recomputeLocked(cascade);
            return null;
        }
        if (edge.getSynchronizationMode() != RunTriggerSynchronizationMode.EACH && !attempts(node).isEmpty()) return null;
        boolean planOnly = edge.getSynchronizationMode() == RunTriggerSynchronizationMode.ALL
                ? incoming(cascade, node.getWorkspace()).stream().anyMatch(RunCascadeEdge::isPlanOnly) : edge.isPlanOnly();
        String reference = planOnly ? edge.getPlanTemplateReference() : edge.getTemplateReference();
        String tcl = planOnly ? edge.getPlanTemplateTcl() : edge.getTemplateTcl();
        if (reference == null || tcl == null) {
            node.setStatus(RunCascadeNodeStatus.BLOCKED);
            node.setReason("No workflow was configured when this cascade started");
            runCascadeNodeRepository.save(node);
            settleWaiting(cascade);
            recomputeLocked(cascade);
            return null;
        }
        if (planOnly && !tclService.isTclPlanOnly(tcl)) {
            node.setStatus(RunCascadeNodeStatus.BLOCKED);
            node.setReason("The destroy response requires a workflow containing only plan steps");
            runCascadeNodeRepository.save(node);
            settleWaiting(cascade);
            recomputeLocked(cascade);
            return null;
        }
        Job created = persistDispatch(cascade, node, reference, tcl, parent, depth);
        recomputeLocked(cascade);
        return created;
    }

    @Transactional
    public void resolveNode(Job completedJob) {
        resolveAttempt(completedJob, RunCascadeNodeStatus.SUCCEEDED, true);
    }

    @Transactional
    public void resolveNode(Job completedJob, boolean changedState) {
        resolveAttempt(completedJob, RunCascadeNodeStatus.SUCCEEDED, changedState);
    }

    @Transactional
    public void resolveNodeAsUnsuccessful(Job completedJob, RunCascadeNodeStatus outcome) {
        resolveAttempt(completedJob, outcome, false);
    }

    private void resolveAttempt(Job job, RunCascadeNodeStatus outcome, boolean changedState) {
        var found = runCascadeNodeAttemptRepository.findByJob_Id(job.getId());
        if (found.isEmpty()) return;
        RunCascade cascade = lockCascade(found.get().getNode().getCascade().getId());
        RunCascadeNodeAttempt attempt = runCascadeNodeAttemptRepository.findByJob_Id(job.getId()).orElseThrow();
        entityManager.refresh(attempt);
        entityManager.refresh(attempt.getNode());
        if (attempt.getOutcome() != RunCascadeNodeStatus.RUNNING) return; // Terminal attempt events are immutable.
        attempt.setOutcome(outcome);
        attempt.setStateChanged(changedState);
        runCascadeNodeAttemptRepository.save(attempt);
        RunCascadeNode node = attempt.getNode();
        List<RunCascadeNodeAttempt> all = attempts(node);
        if (all.stream().anyMatch(a -> a.getOutcome() == RunCascadeNodeStatus.RUNNING)) {
            node.setStatus(RunCascadeNodeStatus.RUNNING);
        } else {
            // Operator retries supersede earlier failed attempts; EACH has multiple independent
            // upstream events, whose failures remain visible unless a later retry succeeds.
            node.setStatus(all.stream().anyMatch(a -> !a.isSuperseded() && a.getOutcome() == RunCascadeNodeStatus.FAILED)
                    ? RunCascadeNodeStatus.FAILED : all.getLast().getOutcome());
        }
        runCascadeNodeRepository.save(node);
        if (outcome != RunCascadeNodeStatus.SUCCEEDED && node.getStatus() != RunCascadeNodeStatus.RUNNING) {
            for (RunCascadeEdge edge : outgoing(cascade, node.getWorkspace())) {
                if (edge.getStatus() == RunCascadeEdgeStatus.PENDING) edge.setStatus(RunCascadeEdgeStatus.BLOCKED);
            }
        }
        settleWaiting(cascade);
        recomputeLocked(cascade);
    }

    /** Terminal dependency resolutions propagate until no untouched workspace can be stranded. */
    private void settleWaiting(RunCascade cascade) {
        boolean changed;
        do {
            changed = false;
            for (RunCascadeNode node : runCascadeNodeRepository.findByCascade_Id(cascade.getId())) {
                List<RunCascadeNodeAttempt> nodeAttempts = attempts(node);
                if (!hasFutureInputs(cascade, node)) {
                    for (RunCascadeEdge edge : outgoing(cascade, node.getWorkspace())) {
                        if (edge.getStatus() == RunCascadeEdgeStatus.PENDING && edge.isSkipRequested()) {
                            edge.setStatus(RunCascadeEdgeStatus.SKIPPED);
                            runCascadeEdgeRepository.save(edge);
                            changed = true;
                        }
                    }
                }
                if (node.getStatus() == RunCascadeNodeStatus.SUCCEEDED && !hasFutureInputs(cascade, node)
                        && !nodeAttempts.isEmpty() && nodeAttempts.stream().allMatch(a -> Boolean.FALSE.equals(a.getStateChanged()))) {
                    for (RunCascadeEdge edge : outgoing(cascade, node.getWorkspace())) {
                        if (edge.getStatus() == RunCascadeEdgeStatus.PENDING) {
                            edge.setStatus(RunCascadeEdgeStatus.SKIPPED);
                            runCascadeEdgeRepository.save(edge);
                            changed = true;
                        }
                    }
                }
                if (node.getStatus() == RunCascadeNodeStatus.BLOCKED || node.getStatus() == RunCascadeNodeStatus.SKIPPED) {
                    for (RunCascadeEdge edge : outgoing(cascade, node.getWorkspace())) {
                        if (edge.getStatus() == RunCascadeEdgeStatus.PENDING) {
                            edge.setStatus(node.getStatus() == RunCascadeNodeStatus.BLOCKED ? RunCascadeEdgeStatus.BLOCKED : RunCascadeEdgeStatus.SKIPPED);
                            runCascadeEdgeRepository.save(edge);
                            changed = true;
                        }
                    }
                }
                if (node.getStatus() != RunCascadeNodeStatus.PENDING || !attempts(node).isEmpty()) continue;
                List<RunCascadeEdge> inbound = incoming(cascade, node.getWorkspace());
                if (inbound.isEmpty()) continue;
                boolean all = inbound.getFirst().getSynchronizationMode() == RunTriggerSynchronizationMode.ALL;
                boolean blocked = all
                        ? inbound.stream().anyMatch(e -> e.getStatus() == RunCascadeEdgeStatus.BLOCKED)
                        : inbound.stream().noneMatch(e -> e.getStatus() == RunCascadeEdgeStatus.PENDING
                                || e.getStatus() == RunCascadeEdgeStatus.SATISFIED)
                            && inbound.stream().anyMatch(e -> e.getStatus() == RunCascadeEdgeStatus.BLOCKED);
                boolean skipped = inbound.stream().allMatch(e -> e.getStatus() == RunCascadeEdgeStatus.SKIPPED);
                if (!blocked && !skipped) continue;
                node.setStatus(blocked ? RunCascadeNodeStatus.BLOCKED : RunCascadeNodeStatus.SKIPPED);
                node.setReason(blocked ? "A required upstream run failed or was paused" : "No eligible upstream change");
                runCascadeNodeRepository.save(node);
                for (RunCascadeEdge edge : outgoing(cascade, node.getWorkspace())) {
                    if (edge.getStatus() == RunCascadeEdgeStatus.PENDING) {
                        edge.setStatus(blocked ? RunCascadeEdgeStatus.BLOCKED : RunCascadeEdgeStatus.SKIPPED);
                        runCascadeEdgeRepository.save(edge);
                    }
                }
                changed = true;
            }
        } while (changed);
    }

    @Transactional
    public void recomputeStatus(UUID cascadeId) {
        recomputeLocked(lockCascade(cascadeId));
    }

    private void recomputeLocked(RunCascade cascade) {
        if (cascade.getStatus() == RunCascadeStatus.CANCELLED) return;
        List<RunCascadeNode> nodes = runCascadeNodeRepository.findByCascade_Id(cascade.getId());
        boolean unresolved = nodes.stream().anyMatch(n -> n.getStatus() == RunCascadeNodeStatus.PENDING
                || n.getStatus() == RunCascadeNodeStatus.RUNNING);
        // A successful workspace's event may not yet have evaluated its outgoing dependencies.
        unresolved |= runCascadeEdgeRepository.findByCascade_Id(cascade.getId()).stream()
                .anyMatch(e -> e.getStatus() == RunCascadeEdgeStatus.PENDING);
        RunCascadeStatus status = unresolved ? RunCascadeStatus.RUNNING
                : nodes.stream().anyMatch(n -> n.getStatus() == RunCascadeNodeStatus.BLOCKED) ? RunCascadeStatus.BLOCKED
                : nodes.stream().anyMatch(n -> n.getStatus() == RunCascadeNodeStatus.FAILED) ? RunCascadeStatus.DEGRADED
                : RunCascadeStatus.COMPLETED;
        if (cascade.getStatus() != status) {
            cascade.setStatus(status);
            runCascadeRepository.save(cascade);
            metrics.cascadeStatusChanged(status);
            if (status != RunCascadeStatus.RUNNING) cascadeNotificationTrigger.notifyCascadeStatusChanged(cascade);
        }
    }

    @Transactional
    public Job retryNode(UUID nodeId) {
        RunCascadeNode node = lockNodeCascade(nodeId);
        assertActive(node.getCascade());
        if (node.getStatus() != RunCascadeNodeStatus.FAILED) {
            throw new IllegalStateException("Only a failed workspace run can be retried, not " + node.getStatus());
        }
        RunCascadeNodeAttempt last = attempts(node).stream()
                .filter(a -> !a.isSuperseded() && a.getOutcome() == RunCascadeNodeStatus.FAILED)
                .reduce((a, b) -> b).orElseThrow();
        Job prior = last.getJob();
        last.setSuperseded(true);
        runCascadeNodeAttemptRepository.save(last);
        Job created = persistDispatch(node.getCascade(), node, prior.getTemplateReference(), prior.getTcl(),
                node.getCascade().getOriginJob(), node.getDepth());
        scheduleAfterCommit(created);
        recomputeLocked(node.getCascade());
        metrics.nodeRetried();
        return created;
    }

    @Transactional
    public Optional<Job> resumeNode(UUID nodeId) {
        RunCascadeNode node = lockNodeCascade(nodeId);
        RunCascade cascade = node.getCascade();
        assertActive(cascade);
        if (node.getStatus() != RunCascadeNodeStatus.BLOCKED) {
            throw new IllegalStateException("Only a paused workspace run can be rechecked, not " + node.getStatus());
        }
        // A recovered parent can satisfy the dependency that originally blocked this workspace.
        for (RunCascadeEdge edge : incoming(cascade, node.getWorkspace())) {
            if (edge.getStatus() == RunCascadeEdgeStatus.BLOCKED) {
                RunCascadeNode source = node(cascade, edge.getSourceWorkspace());
                if (source.getStatus() == RunCascadeNodeStatus.SUCCEEDED && edge.getOnDestroy() != RunTriggerOnDestroyPolicy.BLOCK) {
                    edge.setStatus(RunCascadeEdgeStatus.SATISFIED);
                }
            }
        }
        if (!isReadyNow(cascade, node.getWorkspace())) {
            node.setReason("Required dependencies are still unresolved; recheck after recovery");
            metrics.nodeResumed(false);
            return Optional.empty();
        }
        RunCascadeEdge edge = incoming(cascade, node.getWorkspace()).stream()
                .filter(e -> e.getStatus() == RunCascadeEdgeStatus.SATISFIED)
                .sorted(Comparator.comparing(e -> e.getId().toString())).findFirst().orElseThrow();
        boolean planOnly = incoming(cascade, node.getWorkspace()).stream().anyMatch(RunCascadeEdge::isPlanOnly);
        String reference = planOnly ? edge.getPlanTemplateReference() : edge.getTemplateReference();
        String tcl = planOnly ? edge.getPlanTemplateTcl() : edge.getTemplateTcl();
        if (planOnly && !tclService.isTclPlanOnly(tcl)) {
            throw new IllegalStateException("This frozen workflow does not satisfy its plan-only destroy response");
        }
        Job created = persistDispatch(cascade, node, reference, tcl, cascade.getOriginJob(), node.getDepth());
        scheduleAfterCommit(created);
        recomputeLocked(cascade);
        metrics.nodeResumed(true);
        return Optional.of(created);
    }

    @Transactional
    public RunCascade cancelCascade(UUID cascadeId) {
        RunCascade cascade = lockCascade(cascadeId);
        if (cascade.getStatus() == RunCascadeStatus.CANCELLED) return cascade;
        for (RunCascadeNode node : runCascadeNodeRepository.findByCascade_Id(cascadeId)) {
            if (node.getStatus() == RunCascadeNodeStatus.PENDING || node.getStatus() == RunCascadeNodeStatus.BLOCKED) {
                node.setStatus(RunCascadeNodeStatus.CANCELLED);
                node.setReason("Future runs stopped by an operator");
            }
        }
        cascade.setStatus(RunCascadeStatus.CANCELLED);
        runCascadeRepository.save(cascade);
        metrics.cascadeCancelled();
        cascadeNotificationTrigger.notifyCascadeStatusChanged(cascade);
        return cascade;
    }

    private void assertActive(RunCascade cascade) {
        if (cascade.getStatus() == RunCascadeStatus.CANCELLED) throw new IllegalStateException("This run cascade was stopped");
    }

    private RunCascade lockCascade(UUID id) {
        RunCascade cascade = runCascadeRepository.lockForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("No such run cascade: " + id));
        entityManager.refresh(cascade);
        return cascade;
    }

    private RunCascadeNode lockNodeCascade(UUID nodeId) {
        RunCascadeNode found = runCascadeNodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("No such workspace run: " + nodeId));
        lockCascade(found.getCascade().getId());
        // Refresh the entity after acquiring the lock: an operator may have changed it meanwhile.
        RunCascadeNode locked = runCascadeNodeRepository.lockByCascade_IdAndWorkspace_IdForUpdate(found.getCascade().getId(), found.getWorkspace().getId())
                .orElseThrow();
        entityManager.refresh(locked);
        return locked;
    }

    private boolean hasFutureInputs(RunCascade cascade, RunCascadeNode node) {
        return attempts(node).stream().anyMatch(a -> a.getOutcome() == RunCascadeNodeStatus.RUNNING)
                || incoming(cascade, node.getWorkspace()).stream().anyMatch(e -> e.getStatus() == RunCascadeEdgeStatus.PENDING);
    }

    private boolean isReadyNow(RunCascade cascade, Workspace destination) {
        List<RunCascadeEdge> edges = incoming(cascade, destination);
        if (edges.isEmpty()) return false;
        if (edges.getFirst().getSynchronizationMode() == RunTriggerSynchronizationMode.ALL) {
            return edges.stream().allMatch(e -> e.getStatus() == RunCascadeEdgeStatus.SATISFIED || e.getStatus() == RunCascadeEdgeStatus.SKIPPED)
                    && edges.stream().anyMatch(e -> e.getStatus() == RunCascadeEdgeStatus.SATISFIED);
        }
        return edges.stream().anyMatch(e -> e.getStatus() == RunCascadeEdgeStatus.SATISFIED);
    }

    private Job persistDispatch(RunCascade cascade, RunCascadeNode node, String reference, String tcl, Job parent, int depth) {
        if (reference == null || tcl == null) throw new IllegalStateException("No frozen workflow exists for this workspace run");
        Job job = jobWriter.persistInCascade(node.getWorkspace(), reference, parent, depth, tcl);
        recordAttempt(node, job, RunCascadeNodeStatus.RUNNING);
        node.setStatus(RunCascadeNodeStatus.RUNNING);
        node.setReason(null);
        runCascadeNodeRepository.save(node);
        return job;
    }

    private void scheduleAfterCommit(Job job) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    jobNotificationTrigger.notifyStatusChanged(job);
                    scheduleJobService.createJobContext(job);
                } catch (ObjectAlreadyExistsException ignored) {
                    // Another scheduler/reconciliation worker already recovered this durable job.
                } catch (Exception e) {
                    log.error("Committed cascade job {} awaits scheduler reconciliation: {}", job.getId(), e.getMessage(), e);
                }
            }
        });
    }

    private List<RunCascadeNodeAttempt> attempts(RunCascadeNode node) {
        return runCascadeNodeAttemptRepository.findByNode_IdOrderByAttemptNumberAsc(node.getId());
    }

    private void recordAttempt(RunCascadeNode node, Job job, RunCascadeNodeStatus outcome) {
        if (runCascadeNodeAttemptRepository.findByJob_Id(job.getId()).isPresent()) return;
        RunCascadeNodeAttempt attempt = new RunCascadeNodeAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setNode(node);
        attempt.setJob(job);
        attempt.setAttemptNumber(attempts(node).stream().mapToInt(RunCascadeNodeAttempt::getAttemptNumber).max().orElse(0) + 1);
        attempt.setOutcome(outcome);
        attempt.setStateChanged(outcome == RunCascadeNodeStatus.SUCCEEDED ? Boolean.TRUE : null);
        runCascadeNodeAttemptRepository.save(attempt);
    }

    private List<RunCascadeEdge> incoming(RunCascade cascade, Workspace workspace) {
        return runCascadeEdgeRepository.findByCascade_IdAndDestinationWorkspace_Id(cascade.getId(), workspace.getId());
    }

    private List<RunCascadeEdge> outgoing(RunCascade cascade, Workspace workspace) {
        return runCascadeEdgeRepository.findByCascade_IdAndSourceWorkspace_Id(cascade.getId(), workspace.getId());
    }

    private RunCascadeNode node(RunCascade cascade, Workspace workspace) {
        return runCascadeNodeRepository.findByCascade_IdAndWorkspace_Id(cascade.getId(), workspace.getId()).orElseThrow();
    }

    private RunCascadeNode createNode(RunCascade cascade, Workspace workspace, int depth) {
        RunCascadeNode node = new RunCascadeNode();
        node.setId(UUID.randomUUID());
        node.setCascade(cascade);
        node.setWorkspace(workspace);
        node.setDepth(depth);
        return runCascadeNodeRepository.save(node);
    }

    /** Real BFS; depth is the shortest reachable path and total graph size is bounded. */
    private void snapshotReachableGraph(RunCascade cascade, Workspace origin, int originDepth) {
        Set<UUID> visited = new HashSet<>();
        visited.add(origin.getId());
        Map<UUID, Integer> depths = new HashMap<>();
        depths.put(origin.getId(), originDepth);
        Deque<Workspace> queue = new ArrayDeque<>();
        queue.addLast(origin);
        while (!queue.isEmpty()) {
            Workspace source = queue.removeFirst();
            int depth = depths.get(source.getId());
            if (depth >= properties.getMaxCascadeDepth()) continue;
            for (WorkspaceRunTrigger trigger : workspaceRunTriggerRepository.findEnabledBySourceWorkspaceId(source.getId())) {
                Workspace destination = trigger.getDestinationWorkspace();
                RunCascadeEdge edge = new RunCascadeEdge();
                edge.setId(UUID.randomUUID());
                edge.setCascade(cascade);
                edge.setSourceWorkspace(source);
                edge.setDestinationWorkspace(destination);
                edge.setSynchronizationMode(trigger.getSynchronizationMode());
                edge.setOnDestroy(trigger.getOnDestroy());
                edge.setSourceTrigger(trigger);
                Template template = trigger.getTemplate();
                if (template == null && destination.getDefaultTemplate() != null) {
                    template = templateRepository.findById(UUID.fromString(destination.getDefaultTemplate())).orElse(null);
                }
                if (template != null) {
                    edge.setTemplateReference(template.getId().toString());
                    edge.setTemplateTcl(template.getTcl());
                }
                Template plan = trigger.getOnDestroyPlanTemplate();
                if (plan != null) {
                    edge.setPlanTemplateReference(plan.getId().toString());
                    edge.setPlanTemplateTcl(plan.getTcl());
                }
                runCascadeEdgeRepository.save(edge);
                if (visited.add(destination.getId())) {
                    if (visited.size() > Math.max(1, properties.getMaxCascadeNodes())) {
                        throw new IllegalStateException("Workspace dependency graph exceeds the configured cascade node limit");
                    }
                    depths.put(destination.getId(), depth + 1);
                    createNode(cascade, destination, depth + 1);
                    queue.addLast(destination);
                }
            }
        }
        // Fail closed for older configurations with conflicting join/action settings.
        for (RunCascadeNode node : runCascadeNodeRepository.findByCascade_Id(cascade.getId())) {
            List<RunCascadeEdge> inbound = incoming(cascade, node.getWorkspace());
            if (inbound.stream().map(RunCascadeEdge::getSynchronizationMode).distinct().count() > 1) {
                throw new IllegalStateException("All incoming dependencies for a workspace must use the same start condition");
            }
            if (!inbound.isEmpty() && inbound.getFirst().getSynchronizationMode() != RunTriggerSynchronizationMode.EACH
                    && (inbound.stream().map(RunCascadeEdge::getTemplateReference).distinct().count() > 1
                        || inbound.stream().map(RunCascadeEdge::getOnDestroy).distinct().count() > 1
                        || inbound.stream().map(RunCascadeEdge::getPlanTemplateReference).distinct().count() > 1)) {
                throw new IllegalStateException("Joined dependencies must use the same workflow and destroy response");
            }
        }
    }
}
