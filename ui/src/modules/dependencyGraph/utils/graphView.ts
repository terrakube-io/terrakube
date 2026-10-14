export type GraphConnection = { source: string; target: string };
export type GraphDirection = "both" | "upstream" | "downstream";

/** Build once per topology; exploring a neighborhood visits adjacency lists, not all edges per hop. */
export function indexConnections(edges: GraphConnection[]) {
  const upstream = new Map<string, Set<string>>();
  const downstream = new Map<string, Set<string>>();
  for (const { source, target } of edges) {
    if (!upstream.has(target)) upstream.set(target, new Set());
    if (!downstream.has(source)) downstream.set(source, new Set());
    upstream.get(target)!.add(source);
    downstream.get(source)!.add(target);
  }
  return { upstream, downstream };
}

export function neighborhood(
  index: ReturnType<typeof indexConnections>,
  seeds: Iterable<string>,
  hops: number,
  direction: GraphDirection = "both"
): Set<string> {
  const included = new Set(seeds);
  let frontier = [...included];
  for (let hop = 0; hop < hops && frontier.length; hop++) {
    const next: string[] = [];
    for (const id of frontier) {
      const neighbors = [
        ...(direction !== "downstream" ? (index.upstream.get(id) ?? []) : []),
        ...(direction !== "upstream" ? (index.downstream.get(id) ?? []) : []),
      ];
      for (const neighbor of neighbors) {
        if (!included.has(neighbor)) {
          included.add(neighbor);
          next.push(neighbor);
        }
      }
    }
    frontier = next;
  }
  return included;
}

export const GRAPH_NODE_LIMIT = 150;
export const GRAPH_EXPANDED_LIMIT = 1000;

/** Keep the active focus first when a very broad fan-out exceeds the readable view budget. */
export function limitGraphNodes<T extends { id: string }>(nodes: T[], focusId?: string, expanded = false): T[] {
  const limit = expanded ? GRAPH_EXPANDED_LIMIT : GRAPH_NODE_LIMIT;
  if (nodes.length <= limit) return nodes;
  const focus = nodes.find((node) => node.id === focusId);
  return focus ? [focus, ...nodes.filter((node) => node.id !== focusId).slice(0, limit - 1)] : nodes.slice(0, limit);
}
