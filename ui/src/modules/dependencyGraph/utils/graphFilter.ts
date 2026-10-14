import { indexConnections, neighborhood } from "./graphView";
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

  const included = neighborhood(
    indexConnections(edges.map((edge) => ({ source: edge.sourceWorkspaceId, target: edge.destinationWorkspaceId }))),
    [focusWorkspaceId],
    hops
  );

  const filteredWorkspaces: DependencyGraphWorkspace[] = workspaces.filter((w) => included.has(w.id));
  const filteredEdges: DependencyGraphEdge[] = edges.filter(
    (e) => included.has(e.sourceWorkspaceId) && included.has(e.destinationWorkspaceId)
  );
  return { workspaces: filteredWorkspaces, edges: filteredEdges };
}
