import { AimOutlined, ExpandOutlined, ReloadOutlined } from "@ant-design/icons";
import { Alert, Button, Drawer, List, Select, Space, Switch, Tag, Typography } from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import ReactFlow, {
  Background,
  Controls,
  Edge,
  MarkerType,
  MiniMap,
  Node,
  NodeChange,
  ReactFlowInstance,
} from "reactflow";
import "reactflow/dist/style.css";
import "@/components/display/WorkspaceGraph/WorkspaceGraph.css";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { LinkButton } from "@/components/navigation/LinkButton";
import { ORGANIZATION_NAME } from "@/config/actionTypes";
import { getErrorMessage } from "@/config/axiosConfig";
import { RunTriggerSynchronizationMode } from "@/domain/types";
import { DependencyGraphData, getOrganizationDependencyGraph } from "@/modules/dependencyGraph/dependencyGraphService";
import { layoutWithDagre } from "@/modules/dependencyGraph/utils/dagreLayout";
import {
  GraphDirection,
  GRAPH_NODE_LIMIT,
  GRAPH_EXPANDED_LIMIT,
  indexConnections,
  limitGraphNodes,
  neighborhood,
} from "@/modules/dependencyGraph/utils/graphView";
import DependencyGraphNode, { DependencyGraphNodeData } from "./DependencyGraphNode";

const COLORS: Record<RunTriggerSynchronizationMode, string> = {
  EACH: "var(--tk-accent)",
  ANY: "#1677ff",
  ALL: "#722ed1",
};
const LABELS: Record<RunTriggerSynchronizationMode, string> = {
  EACH: "Each upstream change",
  ANY: "First eligible upstream",
  ALL: "Wait for all upstreams",
};
const EMPTY: DependencyGraphData = { workspaces: [], edges: [] };

/** Organization topology and workspace neighborhoods share the same search and navigation model. */
export const DependencyGraph = () => {
  const { id: organizationId, workspaceId: focusWorkspaceId } = useParams<{ id: string; workspaceId?: string }>();
  const navigate = useNavigate();
  const [data, setData] = useState<DependencyGraphData>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [hops, setHops] = useState(2);
  const [direction, setDirection] = useState<GraphDirection>("both");
  const [includeDisabled, setIncludeDisabled] = useState(false);
  const [showUnconnected, setShowUnconnected] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [selectedId, setSelectedId] = useState<string>();
  const [dependencyId, setDependencyId] = useState<string>();
  const [positions, setPositions] = useState<Map<string, { x: number; y: number }>>(new Map());
  const request = useRef(0);
  const flow = useRef<ReactFlowInstance | null>(null);
  const organizationName = sessionStorage.getItem(ORGANIZATION_NAME) ?? "Organization";
  const nodeTypes = useMemo(() => ({ dependencyGraphNode: DependencyGraphNode }), []);
  const load = useCallback(async () => {
    if (!organizationId) return;
    const token = ++request.current;
    setLoading(true);
    try {
      const graph = await getOrganizationDependencyGraph(organizationId);
      if (token === request.current) {
        setData(graph);
        setError(undefined);
      }
    } catch (err) {
      if (token === request.current) setError(getErrorMessage(err));
    } finally {
      if (token === request.current) setLoading(false);
    }
  }, [organizationId]);
  useEffect(() => {
    void load();
    return () => {
      ++request.current;
    };
  }, [load]);
  useEffect(() => {
    setSelectedId(undefined);
    setDependencyId(undefined);
    setExpanded(false);
  }, [focusWorkspaceId]);
  const enabledEdges = useMemo(
    () => data.edges.filter((edge) => includeDisabled || edge.enabled !== false),
    [data.edges, includeDisabled]
  );
  const index = useMemo(
    () =>
      indexConnections(
        enabledEdges.map((edge) => ({ source: edge.sourceWorkspaceId, target: edge.destinationWorkspaceId }))
      ),
    [enabledEdges]
  );
  const scoped = useMemo(() => {
    const ids = focusWorkspaceId ? neighborhood(index, [focusWorkspaceId], hops, direction) : undefined;
    return data.workspaces.filter((workspace) => {
      if (ids) return ids.has(workspace.id);
      // Whole-organization view: a workspace with no dependency edges is just noise unless asked for.
      return showUnconnected || index.upstream.has(workspace.id) || index.downstream.has(workspace.id);
    });
  }, [data.workspaces, index, focusWorkspaceId, hops, direction, showUnconnected]);
  const visible = useMemo(
    () => limitGraphNodes(scoped, focusWorkspaceId, expanded),
    [scoped, focusWorkspaceId, expanded]
  );
  const ids = useMemo(() => new Set(visible.map((workspace) => workspace.id)), [visible]);
  const edges: Edge[] = useMemo(
    () =>
      enabledEdges
        .filter((edge) => ids.has(edge.sourceWorkspaceId) && ids.has(edge.destinationWorkspaceId))
        .map((edge) => ({
          id: edge.id,
          source: edge.sourceWorkspaceId,
          target: edge.destinationWorkspaceId,
          label:
            edge.enabled === false ? "Disabled" : visible.length <= 75 ? LABELS[edge.synchronizationMode] : undefined,
          markerEnd: {
            type: MarkerType.ArrowClosed,
            color: edge.enabled === false ? "#8c8c8c" : COLORS[edge.synchronizationMode],
          },
          style: {
            stroke: edge.enabled === false ? "#8c8c8c" : COLORS[edge.synchronizationMode],
            strokeDasharray: edge.enabled === false ? "5 5" : undefined,
            strokeWidth: 1.5,
          },
        })),
    [enabledEdges, ids, visible.length]
  );
  const topology = useMemo(
    () =>
      JSON.stringify([
        visible.map((workspace) => workspace.id).sort(),
        edges.map((edge) => `${edge.id}:${edge.source}:${edge.target}`).sort(),
      ]),
    [visible, edges]
  );
  const layout = useMemo(
    () =>
      new Map(
        layoutWithDagre(
          visible.map((workspace) => ({ id: workspace.id, position: { x: 0, y: 0 }, data: {} })),
          edges,
          { width: 260, height: 160 }
        ).map((node) => [node.id, node.position])
      ),
    [topology]
  ); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    const frame = requestAnimationFrame(() => flow.current?.fitView({ padding: 0.2, maxZoom: 1, duration: 200 }));
    return () => cancelAnimationFrame(frame);
  }, [topology]);
  const neighbors = selectedId ? neighborhood(index, [selectedId], 1) : undefined;
  const nodes: Node<DependencyGraphNodeData>[] = visible.map((workspace) => ({
    id: workspace.id,
    type: "dependencyGraphNode",
    position: positions.get(`${topology}:${workspace.id}`) ?? layout.get(workspace.id) ?? { x: 0, y: 0 },
    selected: selectedId === workspace.id,
    data: {
      organizationId: organizationId ?? "",
      workspaceId: workspace.id,
      workspaceName: workspace.name,
      isFocus: workspace.id === focusWorkspaceId || workspace.id === selectedId,
      dimmed: neighbors ? !neighbors.has(workspace.id) : false,
      upstreamCount: index.upstream.get(workspace.id)?.size ?? 0,
      downstreamCount: index.downstream.get(workspace.id)?.size ?? 0,
      onInspect: (id) => {
        setSelectedId(id);
        setDependencyId(undefined);
      },
      hiddenConnections: [
        ...(index.upstream.get(workspace.id) ?? []),
        ...(index.downstream.get(workspace.id) ?? []),
      ].filter((id) => !ids.has(id)).length,
    },
  }));
  const focusName = data.workspaces.find((workspace) => workspace.id === focusWorkspaceId)?.name;
  const selected = data.workspaces.find((workspace) => workspace.id === selectedId);
  const dependency = data.edges.find((edge) => edge.id === dependencyId);
  const options = useMemo(
    () =>
      data.workspaces
        .map((workspace) => ({ value: workspace.id, label: workspace.name }))
        .sort((a, b) => a.label.localeCompare(b.label)),
    [data.workspaces]
  );
  const focus = (id?: string) =>
    navigate(
      id
        ? `/organizations/${organizationId}/dependency-graph/${id}`
        : `/organizations/${organizationId}/dependency-graph`
    );
  const move = useCallback(
    (changes: NodeChange[]) => {
      if (!changes.some((change) => change.type === "position" && change.position)) return;
      setPositions((current) => {
        const next = new Map(current);
        changes.forEach((change) => {
          if (change.type === "position" && change.position) next.set(`${topology}:${change.id}`, change.position);
        });
        return next;
      });
    },
    [topology]
  );
  const name = (id: string) => data.workspaces.find((workspace) => workspace.id === id)?.name ?? id;

  return (
    <PageWrapper
      title={focusName ? `Workspace dependencies · ${focusName}` : "Workspace dependencies"}
      subTitle="Follow the workspaces a change can reach. This is the configured topology; run cascades show individual executions."
      loading={loading}
      loadingText="Loading workspace dependencies..."
      breadcrumbs={[
        { label: organizationName, path: `/organizations/${organizationId}/workspaces` },
        { label: "Workspace dependencies" },
      ]}
    >
      <div className="workspace-graph">
        <div className="workspace-graph__toolbar">
          <Select
            className="workspace-graph__search"
            aria-label="Find a workspace"
            showSearch
            allowClear
            placeholder="Find a workspace..."
            optionFilterProp="label"
            value={focusWorkspaceId}
            options={options}
            onChange={focus}
          />
          {focusWorkspaceId && (
            <>
              <Select
                aria-label="Dependency direction"
                value={direction}
                options={[
                  { value: "both", label: "Upstream and downstream" },
                  { value: "upstream", label: "Upstream dependencies" },
                  { value: "downstream", label: "Downstream dependents" },
                ]}
                onChange={setDirection}
              />
              <Select
                aria-label="Dependency distance"
                value={hops}
                options={[1, 2, 3].map((value) => ({ value, label: `${value} hop${value > 1 ? "s" : ""}` }))}
                onChange={setHops}
              />
              <LinkButton to={`/organizations/${organizationId}/dependency-graph`}>
                Expand to full organization
              </LinkButton>
            </>
          )}
          {!focusWorkspaceId && (
            <Space>
              <Switch
                checked={showUnconnected}
                onChange={setShowUnconnected}
                aria-label="Show workspaces without dependencies"
              />
              <Typography.Text>Show workspaces without dependencies</Typography.Text>
            </Space>
          )}
          <Space>
            <Switch
              checked={includeDisabled}
              onChange={setIncludeDisabled}
              aria-label="Include disabled dependencies"
            />
            <Typography.Text>Include disabled dependencies</Typography.Text>
          </Space>
          <Button
            icon={<AimOutlined />}
            onClick={() => flow.current?.fitView({ padding: 0.2, maxZoom: 1, duration: 200 })}
          >
            Fit view
          </Button>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
            Refresh
          </Button>
        </div>
        <div className="workspace-graph__summary">
          <Typography.Text type="secondary">
            Showing {visible.length} of {data.workspaces.length} workspaces · {edges.length} dependencies
          </Typography.Text>
          <Tag color="var(--tk-accent)">Each upstream change</Tag>
          <Tag color="blue">First eligible upstream</Tag>
          <Tag color="purple">Wait for all upstreams</Tag>
        </div>
        {error && (
          <Alert
            type="error"
            showIcon
            title="Could not load workspace dependencies"
            description={error}
            action={<Button onClick={() => void load()}>Try again</Button>}
          />
        )}
        {scoped.length > visible.length && (
          <Alert
            type="info"
            showIcon
            title="Choose a workspace to explore its dependencies"
            description={`This overview shows ${visible.length} workspaces. Search across the whole organization or expand this view; each card counts connections outside the view.`}
            action={
              !expanded ? (
                <Button icon={<ExpandOutlined />} onClick={() => setExpanded(true)}>
                  Show {Math.min(scoped.length, GRAPH_EXPANDED_LIMIT)} workspaces
                </Button>
              ) : undefined
            }
          />
        )}
        <div className="workspace-graph__canvas">
          <ReactFlow
            nodes={nodes}
            edges={edges.map((edge) => ({
              ...edge,
              style: {
                ...edge.style,
                opacity: selectedId && edge.source !== selectedId && edge.target !== selectedId ? 0.2 : 1,
              },
            }))}
            nodeTypes={nodeTypes}
            onInit={(instance) => {
              flow.current = instance;
              instance.fitView({ padding: 0.2, maxZoom: 1 });
            }}
            onNodesChange={move}
            onNodeClick={(_, node) => {
              setSelectedId(node.id);
              setDependencyId(undefined);
            }}
            onEdgeClick={(_, edge) => {
              setDependencyId(edge.id);
              setSelectedId(undefined);
            }}
            onPaneClick={() => {
              setSelectedId(undefined);
              setDependencyId(undefined);
            }}
            onlyRenderVisibleElements={data.workspaces.length > GRAPH_NODE_LIMIT}
            minZoom={0.001}
            maxZoom={1.8}
            nodesConnectable={false}
            edgesUpdatable={false}
            proOptions={{ hideAttribution: true }}
          >
            <Controls />
            <MiniMap pannable zoomable />
            <Background />
          </ReactFlow>
        </div>
        <div className="workspace-graph__caption">
          Arrows point from an upstream workspace to the workspace it triggers. Select a workspace to explore its
          neighbors, or a connection to inspect its policy. Dashed gray connections are disabled.
        </div>
      </div>
      <Drawer
        title={selected?.name ?? "Dependency details"}
        open={!!selected || !!dependency}
        onClose={() => {
          setSelectedId(undefined);
          setDependencyId(undefined);
        }}
        size={420}
      >
        {selected && (
          <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
            <Link to={`/organizations/${organizationId}/workspaces/${selected.id}/run-triggers`}>
              Manage run triggers
            </Link>
            <Button onClick={() => focus(selected.id)}>Focus dependencies</Button>
            <Typography.Title level={5}>
              Upstream dependencies · {index.upstream.get(selected.id)?.size ?? 0}
            </Typography.Title>
            <List
              size="small"
              dataSource={[...(index.upstream.get(selected.id) ?? [])]}
              pagination={(index.upstream.get(selected.id)?.size ?? 0) > 8 ? { pageSize: 8 } : false}
              renderItem={(id) => (
                <List.Item>
                  <Button type="link" onClick={() => focus(id)}>
                    {name(id)}
                  </Button>
                </List.Item>
              )}
            />
            <Typography.Title level={5}>
              Downstream dependents · {index.downstream.get(selected.id)?.size ?? 0}
            </Typography.Title>
            <List
              size="small"
              dataSource={[...(index.downstream.get(selected.id) ?? [])]}
              pagination={(index.downstream.get(selected.id)?.size ?? 0) > 8 ? { pageSize: 8 } : false}
              renderItem={(id) => (
                <List.Item>
                  <Button type="link" onClick={() => focus(id)}>
                    {name(id)}
                  </Button>
                </List.Item>
              )}
            />
          </Space>
        )}
        {dependency && (
          <Space orientation="vertical" size="middle">
            <Typography.Text strong>
              {name(dependency.sourceWorkspaceId)} → {name(dependency.destinationWorkspaceId)}
            </Typography.Text>
            <Tag>{dependency.enabled === false ? "Disabled" : "Enabled"}</Tag>
            <Typography.Text>{LABELS[dependency.synchronizationMode]}</Typography.Text>
            <Link to={`/organizations/${organizationId}/workspaces/${dependency.destinationWorkspaceId}/run-triggers`}>
              Manage this dependency on its destination workspace
            </Link>
          </Space>
        )}
      </Drawer>
    </PageWrapper>
  );
};
export default DependencyGraph;
