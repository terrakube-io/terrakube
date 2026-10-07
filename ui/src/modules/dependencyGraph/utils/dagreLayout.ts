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
export function layoutWithDagre(nodes: Node[], edges: Edge[]): Node[] {
  const graph = new dagre.graphlib.Graph();
  graph.setGraph({ rankdir: "LR", nodesep: 40, ranksep: 120 });
  graph.setDefaultEdgeLabel(() => ({}));

  nodes.forEach((node) => graph.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT }));
  edges.forEach((edge) => graph.setEdge(edge.source, edge.target));

  dagre.layout(graph);

  return nodes.map((node) => {
    const position = graph.node(node.id);
    return {
      ...node,
      // dagre centers on the node; reactflow positions by top-left corner.
      position: { x: position.x - NODE_WIDTH / 2, y: position.y - NODE_HEIGHT / 2 },
    };
  });
}
