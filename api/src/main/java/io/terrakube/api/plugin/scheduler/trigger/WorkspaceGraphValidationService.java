package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.WorkspaceRunTriggerRepository;
import io.terrakube.api.repository.WorkspaceRunTriggerRepository.TriggerEdge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps an organization's run trigger graph acyclic.
 *
 * A cycle would mean an apply on any of its workspaces re-triggering itself indefinitely.
 * The runtime cascade limit bounds the damage, but a graph that cannot loop in the first
 * place is better than one that stops looping after ten hops.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceGraphValidationService {

    private final WorkspaceRunTriggerRepository workspaceRunTriggerRepository;

    /**
     * Rejects an edge that would close a loop.
     *
     * @param organizationId owner of the graph
     * @param triggerId      the edge being written, excluded from the graph so an update is
     *                       validated against its new shape rather than its old one
     * @param sourceId       upstream workspace
     * @param destinationId  downstream workspace
     */
    public void validateAcyclic(UUID organizationId, UUID triggerId, UUID sourceId, UUID destinationId) {
        if (organizationId == null || sourceId == null || destinationId == null) {
            return;
        }

        // Caught earlier by the security check and by a database constraint; repeated here so
        // the service is correct on its own rather than by arrangement with its callers.
        if (sourceId.equals(destinationId)) {
            throw new CyclicDependencyException(
                    "A workspace cannot trigger itself.");
        }

        Map<UUID, Set<UUID>> adjacency = loadGraphExcluding(organizationId, triggerId);

        // The new edge points source -> destination. It closes a loop only if the graph
        // already lets you walk from destination back to source.
        List<UUID> loop = findPath(adjacency, destinationId, sourceId);
        if (loop != null) {
            String path = describe(loop, destinationId);
            log.warn("Rejecting run trigger in organization {}: it would create the cycle {}",
                    organizationId, path);
            throw new CyclicDependencyException(
                    "This run trigger would create a circular dependency: " + path);
        }
    }

    private Map<UUID, Set<UUID>> loadGraphExcluding(UUID organizationId, UUID excludedTriggerId) {
        List<TriggerEdge> edges = workspaceRunTriggerRepository.findEdgesByOrganizationId(organizationId);
        Map<UUID, Set<UUID>> adjacency = new HashMap<>();
        for (TriggerEdge edge : edges) {
            // On update the row is already flushed, so the edge under validation has to be
            // left out or it would be mistaken for a pre-existing dependency.
            if (excludedTriggerId != null && excludedTriggerId.equals(edge.getId())) {
                continue;
            }
            adjacency.computeIfAbsent(edge.getSourceId(), k -> new HashSet<>())
                    .add(edge.getDestinationId());
        }
        return adjacency;
    }

    /**
     * Iterative depth-first search from {@code from} to {@code target}, returning the path
     * when one exists. Iterative rather than recursive so a deep chain cannot overflow the
     * stack, and visited-guarded so an existing cycle elsewhere in the graph cannot hang it.
     */
    private List<UUID> findPath(Map<UUID, Set<UUID>> adjacency, UUID from, UUID target) {
        Set<UUID> visited = new HashSet<>();
        Map<UUID, UUID> cameFrom = new HashMap<>();
        Deque<UUID> stack = new ArrayDeque<>();

        stack.push(from);
        visited.add(from);

        while (!stack.isEmpty()) {
            UUID current = stack.pop();
            if (current.equals(target)) {
                return reconstruct(cameFrom, from, target);
            }
            for (UUID next : adjacency.getOrDefault(current, Set.of())) {
                if (visited.add(next)) {
                    cameFrom.put(next, current);
                    stack.push(next);
                }
            }
        }
        return null;
    }

    private List<UUID> reconstruct(Map<UUID, UUID> cameFrom, UUID from, UUID target) {
        List<UUID> path = new ArrayList<>();
        UUID current = target;
        path.add(current);
        while (!current.equals(from)) {
            current = cameFrom.get(current);
            if (current == null) {
                break;
            }
            path.add(current);
        }
        return path.reversed();
    }

    /** Renders the loop as source -> ... -> source so the error names the workspaces involved. */
    private String describe(List<UUID> pathFromDestinationToSource, UUID destinationId) {
        StringBuilder sb = new StringBuilder();
        for (UUID node : pathFromDestinationToSource) {
            sb.append(node).append(" -> ");
        }
        sb.append(destinationId);
        return sb.toString();
    }
}
