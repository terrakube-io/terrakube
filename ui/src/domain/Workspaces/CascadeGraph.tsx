import { message } from "antd";
import { useCallback, useEffect, useMemo, useState } from "react";
import ReactFlow, {
  Background,
  Controls,
  Edge,
  EdgeChange,
  MarkerType,
  Node,
  NodeChange,
  applyEdgeChanges,
  applyNodeChanges,
} from "reactflow";
import "reactflow/dist/style.css";
import { RunCascadeNodeStatus } from "@/domain/types";
import {
  getAdminErrorMessage,
  getCascadeEdges,
  getCascadeNodes,
  resumeNode,
  retryNode,
} from "@/modules/cascades/cascadeService";
import CascadeNode, { CascadeNodeData } from "./CascadeNode";

type Props = {
  cascadeId: string;
  organizationId: string;
};

type IncludedIndex = Record<string, { attributes: { name: string } }>;

const indexIncluded = (included: { type: string; id: string; attributes: { name: string } }[]): IncludedIndex => {
  const index: IncludedIndex = {};
  included.forEach((item) => {
    index[`${item.type}:${item.id}`] = item;
  });
  return index;
};

const setNodeLoading = (nodes: Node<CascadeNodeData>[], nodeId: string, actionLoading: boolean) =>
  nodes.map((node) => (node.id === nodeId ? { ...node, data: { ...node.data, actionLoading } } : node));

export const CascadeGraph = ({ cascadeId, organizationId }: Props) => {
  const [nodes, setNodes] = useState<Node<CascadeNodeData>[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);
  const [loading, setLoading] = useState(true);
  const onNodesChange = useCallback((changes: NodeChange[]) => setNodes((ns) => applyNodeChanges(changes, ns)), []);
  const onEdgesChange = useCallback((changes: EdgeChange[]) => setEdges((es) => applyEdgeChanges(changes, es)), []);

  // Declared before handleRetry/handleResume, which close over it - their own useCallback deps
  // need a stable `load` reference to stay stable themselves across re-renders.
  const load = useCallback(() => {
    setLoading(true);
    Promise.all([getCascadeNodes(cascadeId), getCascadeEdges(cascadeId)])
      .then(([nodeResponse, cascadeEdges]) => {
        const workspaceNames = indexIncluded(nodeResponse.included);
        const nodesByWorkspaceId = new Map<string, string>();
        const rowByDepth = new Map<number, number>();

        const flowNodes: Node<CascadeNodeData>[] = nodeResponse.nodes.map((node) => {
          const workspaceId = node.relationships.workspace.data.id;
          nodesByWorkspaceId.set(workspaceId, node.id);
          const depth = node.attributes.depth;
          const row = rowByDepth.get(depth) ?? 0;
          rowByDepth.set(depth, row + 1);

          return {
            id: node.id,
            type: "cascadeNode",
            position: { x: depth * 300, y: row * 150 },
            data: {
              organizationId,
              workspaceId,
              workspaceName: workspaceNames[`workspace:${workspaceId}`]?.attributes?.name ?? workspaceId,
              status: node.attributes.status,
              actionLoading: false,
              onRetry: handleRetry,
              onResume: handleResume,
            },
          };
        });

        const flowEdges: Edge[] = cascadeEdges
          .map((edge): Edge | null => {
            const sourceNodeId = nodesByWorkspaceId.get(edge.relationships.sourceWorkspace.data.id);
            const targetNodeId = nodesByWorkspaceId.get(edge.relationships.destinationWorkspace.data.id);
            if (!sourceNodeId || !targetNodeId) {
              return null;
            }
            return {
              id: edge.id,
              source: sourceNodeId,
              target: targetNodeId,
              animated: true,
              markerEnd: { type: MarkerType.Arrow },
              style: { stroke: "var(--tk-accent)" },
            };
          })
          .filter((edge): edge is Edge => edge !== null);

        setNodes(flowNodes);
        setEdges(flowEdges);
      })
      .catch((err) => message.error(getAdminErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [cascadeId, organizationId]);

  const handleRetry = useCallback(
    (nodeId: string) => {
      setNodes((ns) => setNodeLoading(ns, nodeId, true));
      retryNode(nodeId)
        .then(() => {
          message.success("Retry dispatched");
          load();
        })
        .catch((err) => {
          message.error(getAdminErrorMessage(err));
          setNodes((ns) => setNodeLoading(ns, nodeId, false));
        });
    },
    [load]
  );

  const handleResume = useCallback(
    (nodeId: string) => {
      setNodes((ns) => setNodeLoading(ns, nodeId, true));
      resumeNode(nodeId)
        .then((result) => {
          message.success(
            result.status === RunCascadeNodeStatus.Running
              ? "Dispatched"
              : "Still waiting on another dependency - left pending"
          );
          load();
        })
        .catch((err) => {
          message.error(getAdminErrorMessage(err));
          setNodes((ns) => setNodeLoading(ns, nodeId, false));
        });
    },
    [load]
  );

  useEffect(() => {
    load();
  }, [load]);

  const nodeTypes = useMemo(() => ({ cascadeNode: CascadeNode }), []);

  return (
    <div style={{ height: 500 }} aria-busy={loading}>
      <ReactFlow
        zoomOnScroll={false}
        nodeTypes={nodeTypes}
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        proOptions={{ hideAttribution: true }}
      >
        <Controls />
        <Background />
      </ReactFlow>
    </div>
  );
};
