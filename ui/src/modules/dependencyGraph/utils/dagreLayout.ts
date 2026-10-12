import dagre from "@dagrejs/dagre";
import { Edge, Node } from "reactflow";

const NODE_WIDTH = 220;
const NODE_HEIGHT = 80;

/**
 * Positions nodes left-to-right by dependency order, same direction CascadeGraph's manual
 * depth-based layout already uses. Unlike that layout, an organization's dependency graph can
 * have several disconnected clusters (unrelated workspaces with no edges to each other) and
 * denser fan-out, so a real layout algorithm earns its keep here - dagre lays out every
 * connected component independently and packs them without overlap.
 */
export function layoutWithDagre<T>(
  nodes: Node<T>[],
  edges: Edge[],
  options: { width?: number; height?: number } = {}
): Node<T>[] {
  if (!nodes.length) return nodes;
  const width = options.width ?? NODE_WIDTH;
  const height = options.height ?? NODE_HEIGHT;
  const graph = new dagre.graphlib.Graph();
  graph.setGraph({ rankdir: "LR", nodesep: 40, ranksep: 120 });
  graph.setDefaultEdgeLabel(() => ({}));

  nodes.forEach((node) => graph.setNode(node.id, { width, height }));
  edges.forEach((edge) => graph.setEdge(edge.source, edge.target));

  dagre.layout(graph);

  return nodes.map((node) => {
    const position = graph.node(node.id);
    return {
      ...node,
      // dagre centers on the node; reactflow positions by top-left corner.
      position: { x: position.x - width / 2, y: position.y - height / 2 },
    };
  });
}
