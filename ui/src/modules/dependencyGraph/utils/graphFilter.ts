import { DependencyGraphData, DependencyGraphEdge, DependencyGraphWorkspace } from "../dependencyGraphService";

/**
 * BFS outward from focusWorkspaceId in both directions (what it depends on, and what depends on
 * it - a dependency graph scoped to one workspace is only useful if it shows both), up to `hops`
 * edges away. The focus workspace is always included even if it has no edges at all.
 */
export function filterToNeighborhood(
  { workspaces, edges }: DependencyGraphData,
  focusWorkspaceId: string,
  hops: number
): DependencyGraphData {
  const workspaceIds = new Set(workspaces.map((w) => w.id));
  if (!workspaceIds.has(focusWorkspaceId)) {
    return { workspaces: [], edges: [] };
  }

  const included = new Set<string>([focusWorkspaceId]);
  let frontier = [focusWorkspaceId];

  for (let hop = 0; hop < hops && frontier.length > 0; hop++) {
    const next: string[] = [];
    for (const edge of edges) {
      if (included.has(edge.sourceWorkspaceId) && !included.has(edge.destinationWorkspaceId)) {
        next.push(edge.destinationWorkspaceId);
      }
      if (included.has(edge.destinationWorkspaceId) && !included.has(edge.sourceWorkspaceId)) {
        next.push(edge.sourceWorkspaceId);
      }
    }
    next.forEach((id) => included.add(id));
    frontier = next;
  }

  const filteredWorkspaces: DependencyGraphWorkspace[] = workspaces.filter((w) => included.has(w.id));
  const filteredEdges: DependencyGraphEdge[] = edges.filter(
    (e) => included.has(e.sourceWorkspaceId) && included.has(e.destinationWorkspaceId)
  );
  return { workspaces: filteredWorkspaces, edges: filteredEdges };
}
