import { CloseCircleOutlined } from "@ant-design/icons";
import { Alert, Input, message, Select, Space, Typography } from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import ReactFlow, { Background, Controls, Edge, MarkerType, MiniMap, Node, ReactFlowInstance } from "reactflow";
import "reactflow/dist/style.css";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { LinkButton } from "@/components/navigation/LinkButton";
import { ORGANIZATION_NAME } from "@/config/actionTypes";
import { getErrorMessage } from "@/config/axiosConfig";
import { RunTriggerSynchronizationMode } from "@/domain/types";
import { DependencyGraphData, getOrganizationDependencyGraph } from "@/modules/dependencyGraph/dependencyGraphService";
import { layoutWithDagre } from "@/modules/dependencyGraph/utils/dagreLayout";
import { filterToNeighborhood } from "@/modules/dependencyGraph/utils/graphFilter";
import DependencyGraphNode, { DependencyGraphNodeData } from "./DependencyGraphNode";

const SYNC_MODE_EDGE_COLOR: Record<RunTriggerSynchronizationMode, string> = {
  [RunTriggerSynchronizationMode.Each]: "var(--tk-accent)",
  [RunTriggerSynchronizationMode.Any]: "#1677ff",
  [RunTriggerSynchronizationMode.All]: "#722ed1",
};

const EMPTY_GRAPH: DependencyGraphData = { workspaces: [], edges: [] };

const HOP_OPTIONS = [1, 2, 3].map((n) => ({ label: `${n} hop${n > 1 ? "s" : ""}`, value: n }));

// Past this many nodes, reactflow's DOM-per-node rendering starts to feel sluggish and the
// layout gets genuinely hard to read regardless of fit/zoom - a soft warning, not a hard block,
// since the graph still works, just steers towards the scoped view or a narrower search.
const LARGE_GRAPH_WARNING_THRESHOLD = 150;

export const DependencyGraph = () => {
  const { id: organizationId, workspaceId: focusWorkspaceId } = useParams<{ id: string; workspaceId?: string }>();
  const [rawData, setRawData] = useState<DependencyGraphData>(EMPTY_GRAPH);
  const [loading, setLoading] = useState(true);
  const [hops, setHops] = useState(2);
  const [searchTerm, setSearchTerm] = useState("");
  const [selectedNodeId, setSelectedNodeId] = useState<string>();
  const reactFlowRef = useRef<ReactFlowInstance | null>(null);
  const organizationName = sessionStorage.getItem(ORGANIZATION_NAME) ?? "Organization";

  const nodeTypes = useMemo(() => ({ dependencyGraphNode: DependencyGraphNode }), []);

  const load = useCallback(() => {
    if (!organizationId) {
      return;
    }
    setLoading(true);
    getOrganizationDependencyGraph(organizationId)
      .then(setRawData)
      .catch((err) => message.error(getErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [organizationId]);

  useEffect(() => {
    load();
  }, [load]);

  // Scoping happens entirely client-side over the already-fetched org-wide graph: cheap, and it
  // means changing the hop count or clearing focus never costs another round trip.
  const scopedData = useMemo(
    () => (focusWorkspaceId ? filterToNeighborhood(rawData, focusWorkspaceId, hops) : rawData),
    [rawData, focusWorkspaceId, hops]
  );

  const focusWorkspaceName = useMemo(
    () => rawData.workspaces.find((w) => w.id === focusWorkspaceId)?.name,
    [rawData, focusWorkspaceId]
  );

  const searchMatch = useMemo(() => {
    const term = searchTerm.trim().toLowerCase();
    if (!term) {
      return undefined;
    }
    return scopedData.workspaces.find((w) => w.name.toLowerCase().includes(term));
  }, [scopedData, searchTerm]);

  const baseEdges: Edge[] = useMemo(
    () =>
      scopedData.edges.map((edge) => ({
        id: edge.id,
        source: edge.sourceWorkspaceId,
        target: edge.destinationWorkspaceId,
        label: edge.synchronizationMode,
        markerEnd: { type: MarkerType.Arrow },
        style: { stroke: SYNC_MODE_EDGE_COLOR[edge.synchronizationMode] },
      })),
    [scopedData]
  );

  // Positions only ever depend on which nodes/edges exist, not on selection or search - so
  // layout is kept separate from the dim/highlight pass below, which must not re-run dagre.
  const layoutedNodes = useMemo(() => {
    if (!organizationId) {
      return [];
    }
    const flowNodes: Node<DependencyGraphNodeData>[] = scopedData.workspaces.map((workspace) => ({
      id: workspace.id,
      type: "dependencyGraphNode",
      position: { x: 0, y: 0 },
      data: { organizationId, workspaceId: workspace.id, workspaceName: workspace.name },
    }));
    return layoutWithDagre(flowNodes, baseEdges);
  }, [organizationId, scopedData, baseEdges]);

  // A clicked node's own direct neighbors stay fully visible; everything else fades out rather
  // than disappearing, so the operator never loses the surrounding context entirely.
  const neighborIds = useMemo(() => {
    if (!selectedNodeId) {
      return new Set<string>();
    }
    const ids = new Set<string>();
    scopedData.edges.forEach((edge) => {
      if (edge.sourceWorkspaceId === selectedNodeId) {
        ids.add(edge.destinationWorkspaceId);
      }
      if (edge.destinationWorkspaceId === selectedNodeId) {
        ids.add(edge.sourceWorkspaceId);
      }
    });
    return ids;
  }, [scopedData, selectedNodeId]);

  const displayNodes: Node<DependencyGraphNodeData>[] = useMemo(
    () =>
      layoutedNodes.map((node) => {
        const isFocus = node.id === focusWorkspaceId || node.id === searchMatch?.id;
        const dimmed =
          !!selectedNodeId && node.id !== selectedNodeId && !neighborIds.has(node.id) && node.id !== searchMatch?.id;
        return { ...node, data: { ...node.data, isFocus, dimmed } };
      }),
    [layoutedNodes, focusWorkspaceId, searchMatch, selectedNodeId, neighborIds]
  );

  const displayEdges: Edge[] = useMemo(
    () =>
      baseEdges.map((edge) => {
        const touchesSelection = !selectedNodeId || edge.source === selectedNodeId || edge.target === selectedNodeId;
        return touchesSelection ? edge : { ...edge, style: { ...edge.style, opacity: 0.2 } };
      }),
    [baseEdges, selectedNodeId]
  );

  // Re-fit whenever the node set actually changes (first load, hop count, focus) - not on every
  // selection/search change, which only dims things in place without moving anything. Deferred
  // to the next animation frame: reactflow measures each node's real rendered size via
  // ResizeObserver asynchronously, so fitting in the same tick the nodes are set would compute
  // bounds against their not-yet-measured (zero) size and produce a bogus, unzoomed fit.
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      reactFlowRef.current?.fitView({ padding: 0.2, duration: 200 });
    });
    return () => cancelAnimationFrame(frame);
  }, [layoutedNodes]);

  useEffect(() => {
    if (!searchMatch) {
      return;
    }
    const matchNode = layoutedNodes.find((node) => node.id === searchMatch.id);
    if (matchNode) {
      reactFlowRef.current?.setCenter(matchNode.position.x + 100, matchNode.position.y + 40, {
        zoom: 1,
        duration: 300,
      });
    }
  }, [searchMatch, layoutedNodes]);

  const breadcrumbs = [
    { label: organizationName, path: `/organizations/${organizationId}/workspaces` },
    ...(focusWorkspaceId
      ? [
          {
            label: focusWorkspaceName ?? "Workspace",
            path: `/organizations/${organizationId}/workspaces/${focusWorkspaceId}/run-triggers`,
          },
        ]
      : []),
    { label: "Dependency Graph" },
  ];

  return (
    <PageWrapper
      title={focusWorkspaceName ? `Dependency Graph — ${focusWorkspaceName}` : "Dependency Graph"}
      subTitle={
        focusWorkspaceId
          ? `Workspaces within ${hops} hop${hops > 1 ? "s" : ""} of ${focusWorkspaceName ?? "this workspace"}, in either direction.`
          : "Every workspace in this organization and every run trigger between them - the static dependency configuration, not a particular cascade's run."
      }
      loading={loading}
      loadingText="Loading dependency graph..."
      breadcrumbs={breadcrumbs}
      actions={
        focusWorkspaceId ? (
          <Space>
            <Select value={hops} onChange={setHops} options={HOP_OPTIONS} style={{ width: 120 }} />
            <LinkButton to={`/organizations/${organizationId}/dependency-graph`}>
              Expand to full organization
            </LinkButton>
          </Space>
        ) : (
          <Input
            placeholder="Find a workspace..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            allowClear={{ clearIcon: <CloseCircleOutlined /> }}
            style={{ width: 240 }}
          />
        )
      }
    >
      {!focusWorkspaceId && scopedData.workspaces.length > LARGE_GRAPH_WARNING_THRESHOLD && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={`${scopedData.workspaces.length} workspaces in this view - that's a lot to scan at once.`}
          description="Search for a workspace above, or open its own scoped dependency graph from its Run Triggers page, for a much smaller, more readable view."
        />
      )}
      <Typography.Paragraph type="secondary" style={{ marginTop: -8, marginBottom: 12 }}>
        Click a workspace to manage its triggers
        {!focusWorkspaceId && ", or click a node to highlight its direct neighbors"}.
      </Typography.Paragraph>
      <div style={{ height: "70vh", minHeight: 480 }}>
        <ReactFlow
          nodeTypes={nodeTypes}
          nodes={displayNodes}
          edges={displayEdges}
          onInit={(instance) => {
            reactFlowRef.current = instance;
            requestAnimationFrame(() => instance.fitView({ padding: 0.2 }));
          }}
          onNodeClick={(_, node) => setSelectedNodeId((current) => (current === node.id ? undefined : node.id))}
          onPaneClick={() => setSelectedNodeId(undefined)}
          proOptions={{ hideAttribution: true }}
        >
          <Controls />
          <MiniMap pannable zoomable />
          <Background />
        </ReactFlow>
      </div>
    </PageWrapper>
  );
};

export default DependencyGraph;
