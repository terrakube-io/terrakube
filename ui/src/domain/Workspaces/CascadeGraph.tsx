import { AimOutlined, ExpandOutlined, ReloadOutlined } from "@ant-design/icons";
import { Alert, Button, Drawer, Empty, List, message, Select, Space, Tag, Typography } from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
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
import CascadeNodeStatusTag from "@/components/display/CascadeNodeStatusTag/CascadeNodeStatusTag";
import CascadeStatusTag from "@/components/display/CascadeStatusTag/CascadeStatusTag";
import { RunCascadeEdge, RunCascadeNodeStatus } from "@/domain/types";
import {
  getAdminErrorMessage,
  getCascadeEdges,
  getCascadeCapabilities,
  getCascade,
  getCascadeNodes,
  resumeNode,
  retryNode,
} from "@/modules/cascades/cascadeService";
import { cascadeNodeStatusColors } from "@/modules/cascades/utils/cascadeNodeStatus";
import { layoutWithDagre } from "@/modules/dependencyGraph/utils/dagreLayout";
import {
  GraphDirection,
  GRAPH_NODE_LIMIT,
  GRAPH_EXPANDED_LIMIT,
  indexConnections,
  limitGraphNodes,
  neighborhood,
} from "@/modules/dependencyGraph/utils/graphView";
import CascadeNode, { CascadeNodeData } from "./CascadeNode";

type Props = { cascadeId: string; organizationId: string };
type WorkspaceRun = Omit<CascadeNodeData, "onRetry" | "onResume" | "onInspect" | "canOperate" | "actionLoading">;
type GraphModel = {
  nodes: Node<WorkspaceRun>[];
  edges: Edge[];
  dependencies: Map<string, RunCascadeEdge>;
  status?: string;
};
const EMPTY: GraphModel = { nodes: [], edges: [], dependencies: new Map() };
const CONDITIONS: Record<string, string> = {
  EACH: "After each upstream change",
  ANY: "After the first eligible upstream",
  ALL: "After all required upstreams resolve",
};
const RESPONSES: Record<string, string> = {
  TRIGGER: "Run configured workflow",
  PLAN_ONLY: "Plan only",
  BLOCK: "Pause dependent run",
  IGNORE: "Do not start a dependent run",
};
const STATUS_OPTIONS = [
  { value: "all", label: "All statuses" },
  { value: "attention", label: "Needs attention" },
  { value: "active", label: "Waiting or running" },
  { value: "finished", label: "Finished" },
];
const ATTENTION = new Set([RunCascadeNodeStatus.Failed, RunCascadeNodeStatus.Blocked]);
const ACTIVE = new Set([RunCascadeNodeStatus.Pending, RunCascadeNodeStatus.Ready, RunCascadeNodeStatus.Running]);

/** Bounded, searchable execution views; refreshing outcomes never moves the topology or viewport. */
export const CascadeGraph = ({ cascadeId, organizationId }: Props) => {
  const [model, setModel] = useState<GraphModel>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [canOperate, setCanOperate] = useState(false);
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [focusId, setFocusId] = useState<string>();
  const [selectedId, setSelectedId] = useState<string>();
  const [dependencyId, setDependencyId] = useState<string>();
  const [hops, setHops] = useState(2);
  const [direction, setDirection] = useState<GraphDirection>("both");
  const [statusFilter, setStatusFilter] = useState("all");
  const [expanded, setExpanded] = useState(false);
  const [positions, setPositions] = useState<Map<string, { x: number; y: number }>>(new Map());
  const flow = useRef<ReactFlowInstance | null>(null);
  const request = useRef(0);
  const inFlight = useRef(false);
  const initialized = useRef<string | undefined>(undefined);
  const refresh = useRef<() => void>(() => {});
  const nodeTypes = useMemo(() => ({ cascadeNode: CascadeNode }), []);

  const load = useCallback(
    async (showLoading = true) => {
      if (inFlight.current) return;
      inFlight.current = true;
      const token = ++request.current;
      if (showLoading) setLoading(true);
      try {
        const [response, dependencies, cascade] = await Promise.all([
          getCascadeNodes(cascadeId),
          getCascadeEdges(cascadeId),
          getCascade(cascadeId),
        ]);
        if (token !== request.current) return;
        const included = new Map(response.included.map((item) => [`${item.type}:${item.id}`, item]));
        const byWorkspace = new Map(response.nodes.map((node) => [node.relationships.workspace.data.id, node.id]));
        const parents = new Map<string, string[]>();
        dependencies.forEach((edge) => {
          const destination = edge.relationships.destinationWorkspace.data.id;
          if (!parents.has(destination)) parents.set(destination, []);
          const source = edge.relationships.sourceWorkspace.data.id;
          parents
            .get(destination)!
            .push(included.get(`workspace:${source}`)?.attributes.name ?? "an upstream workspace");
        });
        const nodes: Node<WorkspaceRun>[] = response.nodes.map((node) => {
          const workspaceId = node.relationships.workspace.data.id;
          return {
            id: node.id,
            type: "cascadeNode",
            position: { x: 0, y: 0 },
            data: {
              organizationId,
              workspaceId,
              workspaceName: included.get(`workspace:${workspaceId}`)?.attributes.name ?? workspaceId,
              status: node.attributes.status,
              reason:
                node.attributes.reason ??
                (node.attributes.status === RunCascadeNodeStatus.Pending
                  ? `Waiting for ${(parents.get(workspaceId) ?? ["an upstream change"]).slice(0, 3).join(", ")}${(parents.get(workspaceId)?.length ?? 0) > 3 ? ` and ${(parents.get(workspaceId)?.length ?? 0) - 3} more` : ""}`
                  : undefined),
              attempts: (node.relationships.runCascadeNodeAttempt?.data ?? [])
                .map((reference) => {
                  const attempt = included.get(`${reference.type}:${reference.id}`);
                  return {
                    number: attempt?.attributes.attemptNumber,
                    outcome: attempt?.attributes.outcome,
                    jobId: attempt?.relationships?.job?.data?.id,
                  };
                })
                .sort((a, b) => (a.number ?? 0) - (b.number ?? 0)),
            },
          };
        });
        const nodesById = new Map(nodes.map((node) => [node.id, node]));
        const edges: Edge[] = dependencies.flatMap((edge) => {
          const source = byWorkspace.get(edge.relationships.sourceWorkspace.data.id);
          const target = byWorkspace.get(edge.relationships.destinationWorkspace.data.id);
          if (!source || !target) return [];
          const resolution = edge.attributes?.status;
          const color =
            resolution === "BLOCKED" ? "#d4380d" : resolution === "SKIPPED" ? "#8c8c8c" : "var(--tk-accent)";
          return [
            {
              id: edge.id,
              source,
              target,
              markerEnd: { type: MarkerType.ArrowClosed, color },
              label:
                resolution === "BLOCKED"
                  ? "Paused"
                  : resolution === "SKIPPED"
                    ? "No eligible change"
                    : edge.attributes?.synchronizationMode === "ALL"
                      ? "Wait for all"
                      : edge.attributes?.synchronizationMode === "ANY"
                        ? "First eligible"
                        : undefined,
              ariaLabel: `${nodesById.get(source)?.data.workspaceName} to ${nodesById.get(target)?.data.workspaceName}: ${CONDITIONS[edge.attributes?.synchronizationMode] ?? "After upstream change"}`,
              style: { stroke: color, strokeWidth: 1.5, strokeDasharray: resolution === "SKIPPED" ? "5 5" : undefined },
            },
          ];
        });
        if (initialized.current !== cascadeId) {
          initialized.current = cascadeId;
          setPositions(new Map());
          setSelectedId(undefined);
          setDependencyId(undefined);
          setStatusFilter("all");
          setExpanded(false);
          setHops(nodes.length > GRAPH_NODE_LIMIT ? 1 : 2);
          setDirection("both");
          setFocusId(
            nodes.length > GRAPH_NODE_LIMIT
              ? (
                  nodes.find((node) => ATTENTION.has(node.data.status)) ??
                  nodes.find((node) => node.data.status === RunCascadeNodeStatus.Running) ??
                  nodes.find((node) => ACTIVE.has(node.data.status)) ??
                  nodes[Math.floor(nodes.length / 2)]
                )?.id
              : undefined
          );
        }
        setModel({
          nodes,
          edges,
          dependencies: new Map(dependencies.map((edge) => [edge.id, edge])),
          status: cascade.attributes.status,
        });
        setError(undefined);
      } catch (err) {
        if (token === request.current) setError(getAdminErrorMessage(err));
      } finally {
        if (token === request.current) {
          setLoading(false);
          inFlight.current = false;
        }
      }
    },
    [cascadeId, organizationId]
  );
  refresh.current = () => {
    void load(false);
  };

  useEffect(() => {
    inFlight.current = false;
    void load();
    const timer = window.setInterval(() => {
      if (!document.hidden) void load(false);
    }, 5000);
    return () => {
      ++request.current;
      inFlight.current = false;
      window.clearInterval(timer);
    };
  }, [load]);
  useEffect(() => {
    let active = true;
    setCanOperate(false);
    getCascadeCapabilities(organizationId)
      .then(({ operate }) => {
        if (active) setCanOperate(operate);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [cascadeId, organizationId]);

  const action = useCallback(async (id: string, kind: "retry" | "resume") => {
    setBusy((current) => new Set(current).add(id));
    try {
      const result = kind === "retry" ? await retryNode(id) : await resumeNode(id);
      message.success(
        kind === "retry"
          ? "Retry dispatched"
          : result.status === RunCascadeNodeStatus.Running
            ? "Dispatched"
            : "Required dependencies are still unresolved"
      );
      refresh.current();
    } catch (err) {
      message.error(getAdminErrorMessage(err));
    } finally {
      setBusy((current) => {
        const next = new Set(current);
        next.delete(id);
        return next;
      });
    }
  }, []);
  const onRetry = useCallback(
    (id: string) => {
      void action(id, "retry");
    },
    [action]
  );
  const onResume = useCallback(
    (id: string) => {
      void action(id, "resume");
    },
    [action]
  );
  const inspect = useCallback((id: string) => {
    setSelectedId(id);
    setDependencyId(undefined);
  }, []);

  const index = useMemo(() => indexConnections(model.edges), [model.edges]);
  const scoped = useMemo(() => {
    let ids = focusId ? neighborhood(index, [focusId], hops, direction) : new Set(model.nodes.map((node) => node.id));
    if (statusFilter !== "all") {
      const matches = model.nodes.filter(
        (node) =>
          ids.has(node.id) &&
          (statusFilter === "attention"
            ? ATTENTION.has(node.data.status)
            : statusFilter === "active"
              ? ACTIVE.has(node.data.status)
              : !ACTIVE.has(node.data.status))
      );
      // Keep direct dependencies visible to explain how a failure affects its neighbors.
      const context = neighborhood(
        index,
        matches.map((node) => node.id),
        1
      );
      ids = new Set([...context].filter((id) => ids.has(id)));
    }
    return model.nodes.filter((node) => ids.has(node.id));
  }, [model.nodes, index, focusId, hops, direction, statusFilter]);
  const visible = useMemo(() => limitGraphNodes(scoped, focusId, expanded), [scoped, focusId, expanded]);
  const visibleIds = useMemo(() => new Set(visible.map((node) => node.id)), [visible]);
  const visibleEdges = useMemo(
    () => model.edges.filter((edge) => visibleIds.has(edge.source) && visibleIds.has(edge.target)),
    [model.edges, visibleIds]
  );
  const topology = useMemo(
    () =>
      JSON.stringify([
        visible.map((node) => node.id).sort(),
        visibleEdges.map((edge) => `${edge.id}:${edge.source}:${edge.target}`).sort(),
      ]),
    [visible, visibleEdges]
  );
  // The layout depends only on IDs and connections, never job outcomes from the refresh loop.
  const layout = useMemo(
    () =>
      new Map(
        layoutWithDagre(visible, visibleEdges, { width: 260, height: 160 }).map((node) => [node.id, node.position])
      ),
    [topology]
  ); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    const frame = requestAnimationFrame(() => flow.current?.fitView({ padding: 0.25, maxZoom: 1, duration: 200 }));
    return () => cancelAnimationFrame(frame);
  }, [topology]);
  const operationAllowed = canOperate && model.status !== "CANCELLED";
  const displayNodes: Node<CascadeNodeData>[] = visible.map((node) => ({
    ...node,
    position: positions.get(`${topology}:${node.id}`) ?? layout.get(node.id) ?? node.position,
    selected: selectedId === node.id,
    data: {
      ...node.data,
      canOperate: operationAllowed,
      actionLoading: busy.has(node.id),
      onRetry,
      onResume,
      onInspect: inspect,
      hiddenConnections: [...(index.upstream.get(node.id) ?? []), ...(index.downstream.get(node.id) ?? [])].filter(
        (id) => !visibleIds.has(id)
      ).length,
    },
  }));
  const onNodesChange = useCallback(
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
  const selected = model.nodes.find((node) => node.id === selectedId);
  const selectedDependency = dependencyId ? model.dependencies.get(dependencyId) : undefined;
  const options = useMemo(
    () =>
      model.nodes
        .map((node) => ({ value: node.id, label: node.data.workspaceName }))
        .sort((a, b) => a.label.localeCompare(b.label)),
    [model.nodes]
  );
  const counts = useMemo(
    () => ({
      attention: model.nodes.filter((node) => ATTENTION.has(node.data.status)).length,
      active: model.nodes.filter((node) => ACTIVE.has(node.data.status)).length,
      succeeded: model.nodes.filter((node) => node.data.status === RunCascadeNodeStatus.Succeeded).length,
    }),
    [model.nodes]
  );
  const resetView = () => {
    setFocusId(undefined);
    setStatusFilter("all");
    setExpanded(false);
  };
  const workspaceName = (id: string) =>
    model.nodes.find((node) => node.data.workspaceId === id)?.data.workspaceName ?? id;

  return (
    <div className="workspace-graph" aria-busy={loading}>
      <div className="workspace-graph__toolbar">
        <Select
          className="workspace-graph__search"
          aria-label="Find a workspace in this cascade"
          placeholder="Find a workspace..."
          showSearch
          allowClear
          optionFilterProp="label"
          value={focusId}
          options={options}
          onChange={(id) => {
            setFocusId(id);
            setStatusFilter("all");
            setExpanded(false);
          }}
        />
        <Select
          aria-label="Filter workspace statuses"
          value={statusFilter}
          options={STATUS_OPTIONS}
          onChange={(value) => {
            setStatusFilter(value);
            setExpanded(false);
          }}
        />
        {focusId && (
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
          </>
        )}
        <Button onClick={resetView}>Overview</Button>
        <Button
          icon={<AimOutlined />}
          onClick={() => flow.current?.fitView({ padding: 0.25, maxZoom: 1, duration: 200 })}
        >
          Fit view
        </Button>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
          Refresh
        </Button>
      </div>
      <div className="workspace-graph__summary">
        <CascadeStatusTag status={model.status} />
        <Button
          size="small"
          type={statusFilter === "attention" ? "primary" : "default"}
          onClick={() => {
            setFocusId(undefined);
            setStatusFilter("attention");
            setExpanded(false);
          }}
        >
          Needs attention · {counts.attention}
        </Button>
        <Tag>{counts.active} waiting or running</Tag>
        <Tag>{counts.succeeded} succeeded</Tag>
        <Typography.Text type="secondary">
          Showing {visible.length} of {model.nodes.length} workspaces · {visibleEdges.length} dependencies
        </Typography.Text>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          title="Could not refresh this cascade"
          description={error}
          action={<Button onClick={() => void load()}>Try again</Button>}
        />
      )}
      {scoped.length > visible.length && (
        <Alert
          type="info"
          showIcon
          title="This view is showing a readable subset"
          description="Choose a workspace to follow its dependencies, or expand the full view. Connections outside this view are counted on each card."
          action={
            !expanded ? (
              <Button icon={<ExpandOutlined />} onClick={() => setExpanded(true)}>
                Show {Math.min(scoped.length, GRAPH_EXPANDED_LIMIT)} workspaces
              </Button>
            ) : undefined
          }
        />
      )}
      {!loading && !visible.length ? (
        <Empty
          description={model.nodes.length ? "No workspace matches this view" : "No workspace runs in this cascade"}
        />
      ) : (
        <div className="workspace-graph__canvas">
          <ReactFlow
            nodes={displayNodes}
            edges={visibleEdges.map((edge) => ({ ...edge, label: visibleEdges.length > 75 ? undefined : edge.label }))}
            nodeTypes={nodeTypes}
            onInit={(instance) => {
              flow.current = instance;
              instance.fitView({ padding: 0.25, maxZoom: 1 });
            }}
            onNodesChange={onNodesChange}
            onNodeClick={(_, node) => inspect(node.id)}
            onEdgeClick={(_, edge) => {
              setDependencyId(edge.id);
              setSelectedId(undefined);
            }}
            onlyRenderVisibleElements={model.nodes.length > GRAPH_NODE_LIMIT}
            minZoom={0.001}
            maxZoom={1.8}
            nodesConnectable={false}
            edgesUpdatable={false}
            proOptions={{ hideAttribution: true }}
          >
            <Controls />
            <MiniMap pannable zoomable nodeColor={(node) => cascadeNodeStatusColors[node.data.status] ?? "#8c8c8c"} />
            <Background />
          </ReactFlow>
        </div>
      )}
      <div className="workspace-graph__caption">
        Changes flow left → right. Select a workspace for its runs and recovery details; select a connection for its
        start condition. Dashed connections have no eligible change; red connections are paused.
      </div>
      <Drawer
        title={selected?.data.workspaceName ?? "Dependency details"}
        open={!!selected || !!selectedDependency}
        onClose={() => {
          setSelectedId(undefined);
          setDependencyId(undefined);
        }}
        size={420}
      >
        {selected && (
          <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
            <CascadeNodeStatusTag status={selected.data.status} />
            {selected.data.reason && (
              <Alert
                showIcon
                type={ATTENTION.has(selected.data.status) ? "warning" : "info"}
                description={selected.data.reason}
              />
            )}
            <Link to={`/organizations/${organizationId}/workspaces/${selected.data.workspaceId}`}>Open workspace</Link>
            <Button
              icon={<AimOutlined />}
              onClick={() => {
                setFocusId(selected.id);
                setStatusFilter("all");
                setExpanded(false);
                setSelectedId(undefined);
              }}
            >
              Focus dependencies
            </Button>
            <Typography.Title level={5}>Upstream dependencies</Typography.Title>
            <List
              size="small"
              dataSource={[...(index.upstream.get(selected.id) ?? [])]}
              pagination={(index.upstream.get(selected.id)?.size ?? 0) > 8 ? { pageSize: 8 } : false}
              locale={{ emptyText: "This is the originating workspace" }}
              renderItem={(id) => {
                const parent = model.nodes.find((node) => node.id === id);
                return (
                  <List.Item>
                    <Button
                      type="link"
                      onClick={() => {
                        inspect(id);
                        setFocusId(id);
                      }}
                    >
                      {parent?.data.workspaceName ?? id}
                    </Button>
                    <CascadeNodeStatusTag status={parent?.data.status} />
                  </List.Item>
                );
              }}
            />
            <Typography.Title level={5}>Run attempts</Typography.Title>
            <List
              size="small"
              dataSource={selected.data.attempts ?? []}
              pagination={(selected.data.attempts?.length ?? 0) > 5 ? { pageSize: 5 } : false}
              locale={{ emptyText: "No run has been dispatched" }}
              renderItem={(attempt) => (
                <List.Item>
                  <Space>
                    Attempt {attempt.number}
                    <CascadeNodeStatusTag status={attempt.outcome} />
                    {attempt.jobId && (
                      <Link
                        to={`/organizations/${organizationId}/workspaces/${selected.data.workspaceId}/runs/${attempt.jobId}`}
                      >
                        Run #{attempt.jobId}
                      </Link>
                    )}
                  </Space>
                </List.Item>
              )}
            />
            {operationAllowed && selected.data.status === RunCascadeNodeStatus.Failed && (
              <Button type="primary" loading={busy.has(selected.id)} onClick={() => onRetry(selected.id)}>
                Retry failed run
              </Button>
            )}
            {operationAllowed && selected.data.status === RunCascadeNodeStatus.Blocked && (
              <Button type="primary" loading={busy.has(selected.id)} onClick={() => onResume(selected.id)}>
                Recheck dependencies
              </Button>
            )}
          </Space>
        )}
        {selectedDependency && (
          <Space orientation="vertical" size="middle">
            <Typography.Text strong>
              {workspaceName(selectedDependency.relationships.sourceWorkspace.data.id)} →{" "}
              {workspaceName(selectedDependency.relationships.destinationWorkspace.data.id)}
            </Typography.Text>
            <Typography.Text>{CONDITIONS[selectedDependency.attributes.synchronizationMode]}</Typography.Text>
            <Typography.Text>
              When upstream infrastructure is destroyed: {RESPONSES[selectedDependency.attributes.onDestroy]}
            </Typography.Text>
            <Typography.Text>
              Dependency resolution: {selectedDependency.attributes.status ?? "Awaiting upstream completion"}
            </Typography.Text>
            <Typography.Paragraph type="secondary">
              This configuration was captured when the cascade started.
            </Typography.Paragraph>
          </Space>
        )}
      </Drawer>
    </div>
  );
};
