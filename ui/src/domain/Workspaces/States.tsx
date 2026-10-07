import { ArrowLeftOutlined, LeftOutlined, RightOutlined, RollbackOutlined, UserOutlined } from "@ant-design/icons";
import { Alert, Avatar, Button, Empty, Skeleton, Space, Tabs, Typography, message } from "antd";
import { useCallback, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
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
import JsonViewer from "@/components/display/JsonViewer";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { ORGANIZATION_ARCHIVE } from "../../config/actionTypes";
import axiosInstance, { axiosClient, getErrorMessage } from "../../config/axiosConfig";
import { ErrorResource, FlatJobHistory, Resource, StateOutput, StateOutputResource, Workspace } from "../types";
import { ResourceDrawer } from "../Workspaces/ResourceDrawer";
import { DownloadState } from "./DownloadState";
import NodeResource from "./NodeResource";
import { StateChanges } from "./StateChanges";
import { diffState } from "./stateDiff";

type Props = {
  history: FlatJobHistory[];
  setStateDetailsVisible: (val: boolean) => void;
  stateDetailsVisible: boolean;
  workspace: Workspace;
  onRollback: any;
  manageState: boolean;
};

/** A fetched JSON document, why it could not be loaded, or that the storage does not offer it; undefined while loading. */
type Loaded = { data?: unknown; error?: string; unavailable?: boolean } | undefined;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const isApiUrl = (url: string) => url.includes(new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).hostname);

// State files on the Terrakube API need the session token; other storage URLs are fetched without it.
function fetchJson(url: string): Promise<NonNullable<Loaded>> {
  return (isApiUrl(url) ? axiosInstance : axiosClient).get(url).then(
    (response) => ({ data: response.data }),
    (error) => ({ error: getErrorMessage(error) })
  );
}

// Only the Terrakube API serves the raw file next to the state; a presigned or SAS storage URL would break.
const rawUrl = (output: string) => output.replace(/\.json(\?|$)/, ".raw.json$1");

const UNAVAILABLE: NonNullable<Loaded> = { unavailable: true };
const UNAVAILABLE_TEXT = "Raw state and version comparison are not available for this storage.";

function VersionMeta({
  item,
  organizationId,
  workspaceId,
}: {
  item: FlatJobHistory;
  organizationId: string;
  workspaceId: string;
}) {
  return (
    <p className="state-version-meta">
      <span>
        <code>#{item.id}</code>
      </span>
      <span>
        <strong>{item.createdBy}</strong> triggered {item.relativeDate}
      </span>
      {UUID.test(item.jobReference) ? (
        <span>
          Rollback to <code>#{item.jobReference}</code>
        </span>
      ) : (
        <Link to={`/organizations/${organizationId}/workspaces/${workspaceId}/runs/${item.jobReference}`}>
          Run #{item.jobReference}
        </Link>
      )}
    </p>
  );
}

export const States = ({
  history,
  setStateDetailsVisible,
  stateDetailsVisible,
  workspace,
  onRollback,
  manageState,
}: Props) => {
  const { orgid } = useParams();
  const organizationId = orgid ?? sessionStorage.getItem(ORGANIZATION_ARCHIVE) ?? "";
  const [selectedId, setSelectedId] = useState<string>();
  const [stateJson, setStateJson] = useState<Loaded>();
  const [rawState, setRawState] = useState<Loaded>();
  const [previousRawState, setPreviousRawState] = useState<Loaded>();
  const [activeTab, setActiveTab] = useState("diagram");
  const [rollbackOpen, setRollbackOpen] = useState(false);
  const [rollingBack, setRollingBack] = useState(false);
  const [nodes, setNodes] = useState<Node<Resource | ErrorResource>[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);
  const [open, setOpen] = useState(false);
  const [resource, setResource] = useState<Resource>();
  const onNodesChange = useCallback((changes: NodeChange[]) => setNodes((ns) => applyNodeChanges(changes, ns)), []);
  const onEdgesChange = useCallback((changes: EdgeChange[]) => setEdges((es) => applyEdgeChanges(changes, es)), []);
  // The version whose files the latest select() asked for; slower responses for other versions are dropped.
  const requested = useRef<string>(undefined);

  const versions = useMemo(
    () => [...history].sort((a, b) => new Date(b.createdDate).getTime() - new Date(a.createdDate).getTime()),
    [history]
  );
  const index = versions.findIndex((v) => v.id === selectedId);
  const current = index >= 0 ? versions[index] : undefined;
  const previous = index >= 0 ? versions[index + 1] : undefined;

  const showDrawer = (record: Resource) => {
    setOpen(true);
    setResource(record);
  };

  function pushNode(
    nodes: Node<Resource | ErrorResource>[],
    dependencies: number,
    element: StateOutputResource,
    xmap: any,
    y: number
  ) {
    nodes.push({
      id: element.address,
      type: "resourceNode",
      data: {
        name: element.name,
        provider: element.provider_name,
        type: element.type,
        values: element.values,
        depends_on: element.depends_on,
        showDrawer: showDrawer,
      },
      position: { x: xmap.get(dependencies), y: y + dependencies * 130 },
    });

    return nodes;
  }

  function pushEdge(edges: Edge[], dependencies: number, element: StateOutputResource, elementDependsOn: any[]) {
    if (dependencies > 0)
      elementDependsOn.forEach((dep) => {
        edges.push({
          id: element.address + "-" + dep,
          source: element.address,
          target: dep,
          className: "normal-edge",
          animated: true,
          markerEnd: {
            type: MarkerType.Arrow,
          },
          markerStart: {
            type: MarkerType.Arrow,
          },
          style: { stroke: "var(--tk-accent)" },
        });
      });

    return edges;
  }

  function loadData(resp: StateOutput) {
    let nodes: Node<Resource | ErrorResource>[] = [];
    let edges: Edge[] = [];
    const x = new Map();
    const y = 100;

    if (resp != null && resp.values != null && resp.values.root_module != null) {
      try {
        if (resp.values.root_module.resources != null) {
          resp.values.root_module.resources.forEach((element) => {
            let dependencies = 0;
            if (element.depends_on != null) dependencies = element.depends_on.length;
            x.set(dependencies, (x.get(dependencies) ? x.get(dependencies) : 0) + 350);

            nodes = pushNode(nodes, dependencies, element, x, y);
            edges = pushEdge(edges, dependencies, element, element.depends_on);
          });
        }

        if (resp.values.root_module.child_modules != null) {
          resp.values.root_module.child_modules.forEach((child) => {
            if (child.resources != null)
              child.resources.forEach((element: StateOutputResource) => {
                let dependencies = 0;
                if (element.depends_on != null) dependencies = element.depends_on.length;
                x.set(dependencies, (x.get(dependencies) ? x.get(dependencies) : 0) + 350);

                nodes = pushNode(nodes, dependencies, element, x, y);
                edges = pushEdge(edges, dependencies, element, element.depends_on);
              });
          });
        }
      } catch (error) {
        console.error(`Failed to build diagram: ${error}`);
        nodes = [];
        nodes.push({
          id: "1",
          data: {
            name: "Error Building Diagram",
            provider: "azurerm",
            type: "unknown",
          },
          position: { x: 0, y: 130 },
        });
      }
    }

    setNodes(nodes);
    setEdges(edges);
  }

  const select = (item: FlatJobHistory) => {
    const older = versions[versions.findIndex((v) => v.id === item.id) + 1];
    requested.current = item.id;
    setSelectedId(item.id);
    setStateDetailsVisible(true);
    setStateJson(undefined);
    setRawState(undefined);
    setPreviousRawState(undefined);
    setNodes([]);
    setEdges([]);
    setOpen(false);

    const latest =
      <T,>(apply: (value: T) => void) =>
      (value: T) => {
        if (requested.current === item.id) apply(value);
      };
    fetchJson(item.output).then(
      latest((loaded) => {
        setStateJson(loaded);
        if (loaded.data) loadData(loaded.data as StateOutput);
      })
    );
    if (isApiUrl(item.output)) fetchJson(rawUrl(item.output)).then(latest(setRawState));
    else setRawState(UNAVAILABLE);
    if (older && isApiUrl(older.output)) fetchJson(rawUrl(older.output)).then(latest(setPreviousRawState));
    else if (older) setPreviousRawState(UNAVAILABLE);
  };

  const nodeTypes = useMemo(
    () => ({
      resourceNode: NodeResource,
    }),
    []
  );

  const changes = useMemo(
    () =>
      previousRawState?.data !== undefined && rawState?.data !== undefined
        ? diffState(previousRawState.data, rawState.data)
        : undefined,
    [previousRawState, rawState]
  );

  const handleRollback = () => {
    setRollingBack(true);
    axiosInstance
      .put(current!.output.replace("/state/", "/rollback/"))
      .then(() => {
        message.success(
          `Rolled back to state version #${current!.id}. Check that the workspace's version is compatible with this state.`
        );
        setRollbackOpen(false);
        onRollback(false, false);
        setStateDetailsVisible(false);
      })
      .catch((error) => message.error(`Failed to roll back the state: ${getErrorMessage(error)}`))
      .finally(() => setRollingBack(false));
  };

  const renderDocument = (loaded: Loaded, label: string) => {
    if (!manageState) return <Typography.Text type="secondary">No access to state</Typography.Text>;
    if (!loaded) return <Skeleton active paragraph={{ rows: 6 }} />;
    if (loaded.unavailable) return <Typography.Text type="secondary">{UNAVAILABLE_TEXT}</Typography.Text>;
    if (loaded.error !== undefined)
      return (
        <Alert type="error" showIcon title={`Could not load the ${label.toLowerCase()}`} description={loaded.error} />
      );
    return <JsonViewer value={loaded.data} label={label} />;
  };

  const changesContent = () => {
    if (!manageState) return <Typography.Text type="secondary">No access to state</Typography.Text>;
    if (rawState?.unavailable || previousRawState?.unavailable)
      return <Typography.Text type="secondary">{UNAVAILABLE_TEXT}</Typography.Text>;
    const failed = rawState?.error ?? previousRawState?.error;
    if (failed !== undefined)
      return <Alert type="error" showIcon title="Could not compare the two versions" description={failed} />;
    if (!rawState || !previousRawState) return <Skeleton active paragraph={{ rows: 3 }} />;
    if (!changes) return <Typography.Text type="secondary">No changes from the previous version.</Typography.Text>;
    return <StateChanges diff={changes} />;
  };

  if (!stateDetailsVisible || !current) {
    return (
      <section className="state-versions" aria-labelledby="state-versions-title">
        <Typography.Title level={3} id="state-versions-title">
          State versions
        </Typography.Title>
        {versions.length === 0 ? (
          <div className="state-version-list state-version-list--empty">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="No state versions yet. A version is saved each time a run applies changes."
            />
          </div>
        ) : (
          <ul className="state-version-list">
            {versions.map((item) => (
              <li key={item.id} className="state-version-row">
                <Avatar size={20} shape="square" icon={<UserOutlined />} />
                <div className="state-version-body">
                  <button type="button" className="state-version-title" onClick={() => select(item)}>
                    {item.title}
                  </button>
                  <VersionMeta item={item} organizationId={organizationId} workspaceId={workspace.id} />
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    );
  }

  return (
    <section className="state-version" aria-labelledby="state-version-title">
      <header className="state-version-header">
        <Avatar size={20} shape="square" icon={<UserOutlined />} />
        <div className="state-version-body">
          <Typography.Title level={3} id="state-version-title">
            {current.title}
          </Typography.Title>
          <VersionMeta item={current} organizationId={organizationId} workspaceId={workspace.id} />
        </div>
      </header>

      <div className="state-version-toolbar">
        <Space wrap size={8}>
          <DownloadState stateUrl={current.output} manageState={manageState} />
          <Button icon={<RollbackOutlined />} danger disabled={!manageState} onClick={() => setRollbackOpen(true)}>
            Roll back to this version
          </Button>
        </Space>
        <Space wrap size={8}>
          <Button icon={<LeftOutlined />} disabled={index === 0} onClick={() => select(versions[index - 1])}>
            Newer
          </Button>
          <Button disabled={!previous} onClick={() => previous && select(previous)}>
            Older <RightOutlined />
          </Button>
          <Button type="link" icon={<ArrowLeftOutlined />} onClick={() => setStateDetailsVisible(false)}>
            Back to all versions
          </Button>
        </Space>
      </div>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: "diagram",
            label: "Diagram",
            disabled: !manageState,
            children: (
              <div className="state-diagram">
                <ResourceDrawer resource={resource} setOpen={setOpen} open={open} workspace={workspace} />
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
            ),
          },
          {
            key: "state",
            label: "State",
            disabled: !manageState,
            children: (
              <>
                <p className="state-tab-help">
                  Resources, outputs and their values as reported by <code>show -json</code>. Download JSON saves this
                  file.
                </p>
                {renderDocument(stateJson, "State")}
              </>
            ),
          },
          {
            key: "raw",
            label: "Raw",
            disabled: !manageState,
            children: (
              <>
                <p className="state-tab-help">The state file exactly as stored. Rolling back restores this file.</p>
                {renderDocument(rawState, "Raw state")}
              </>
            ),
          },
        ]}
      />

      <section className="state-changes" aria-labelledby="state-changes-title">
        <Typography.Title level={4} id="state-changes-title">
          Changes in this version
        </Typography.Title>
        <p className="state-tab-help">
          {previous ? (
            <>
              Compared with the raw state of version <code>#{previous.id}</code>. Removed values are red and struck
              through; added values are green. Sensitive values are hidden.
            </>
          ) : (
            "This is the first state version."
          )}
        </p>
        {previous && changesContent()}
      </section>

      <DeleteConfirmationModal
        open={rollbackOpen}
        title="Roll back to this state version"
        okText="Roll back"
        confirmLoading={rollingBack}
        message={
          <>
            Version <code>#{current.id}</code> becomes the current state of this workspace. Resources created or changed
            after it was saved are no longer tracked by Terrakube, but they keep running; the next run plans against
            this version.
          </>
        }
        onConfirm={handleRollback}
        onCancel={() => setRollbackOpen(false)}
      />
    </section>
  );
};
