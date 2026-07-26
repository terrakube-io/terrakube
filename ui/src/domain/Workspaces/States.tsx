import { InfoCircleOutlined, RollbackOutlined, UserOutlined } from "@ant-design/icons";
import Editor, { OnMount } from "@monaco-editor/react";
import { Avatar, Button, Card, Col, List, Popconfirm, Row, Space, Tooltip, Typography, message, theme } from "antd";
import { useCallback, useMemo, useRef, useState } from "react";
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
import axiosInstance, { axiosClient } from "../../config/axiosConfig";
import { getMonacoTheme, monacoOptions } from "../../config/monacoConfig";
import { ErrorResource, FlatJobHistory, Resource, StateOutput, StateOutputResource, Workspace } from "../types";
import { ResourceDrawer } from "../Workspaces/ResourceDrawer";
import { DownloadState } from "./DownloadState";
import NodeResource from "./NodeResource";

type IStandaloneCodeEditor = Parameters<OnMount>[0];

type Props = {
  history: FlatJobHistory[];
  setStateDetailsVisible: (val: boolean) => void;
  stateDetailsVisible: boolean;
  workspace: Workspace;
  onRollback: any;
  manageState: boolean;
};

export const States = ({
  history,
  setStateDetailsVisible,
  stateDetailsVisible,
  workspace,
  onRollback,
  manageState,
}: Props) => {
  const [currentState, setCurrentState] = useState<FlatJobHistory>();
  const [stateContent, setStateContent] = useState("");
  const [rawStateContent, setRawStateContent] = useState("");
  const [activeTab, setactivetab] = useState("diagram");
  const [nodes, setNodes] = useState<Node<Resource | ErrorResource>[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);
  const [open, setOpen] = useState(false);
  const [resource, setResource] = useState<Resource>();
  const onNodesChange = useCallback((changes: NodeChange[]) => setNodes((ns) => applyNodeChanges(changes, ns)), []);
  const onEdgesChange = useCallback((changes: EdgeChange[]) => setEdges((es) => applyEdgeChanges(changes, es)), []);
  const editorRef = useRef<IStandaloneCodeEditor>(null);
  const jsonEditorRef = useRef<IStandaloneCodeEditor>(null);
  const { token } = theme.useToken();
  const handleClick = (state: FlatJobHistory) => {
    changeState(state);
  };

  function handleEditorDidMount(editor: IStandaloneCodeEditor) {
    editorRef.current = editor;
  }

  function handleJSONEditorDidMount(editor: IStandaloneCodeEditor) {
    jsonEditorRef.current = editor;
  }
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
          style: { stroke: "#1890ff" },
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

  const changeState = (state: FlatJobHistory) => {
    setCurrentState(state);
    setStateDetailsVisible(true);

    const apiDomain = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).hostname;
    if (state.output.includes(apiDomain))
      axiosInstance
        .get(state.output)
        .then((resp) => {
          setStateContent(JSON.stringify(resp.data, null, "\t"));
          loadData(resp.data);

          //GET RAW STATE BASICALLY JUST ADDING .raw.json
          axiosInstance
            .get(state.output.replace(".json", ".raw.json"))
            .then((response) => {
              console.log("Downloading raw state successful...");
              setRawStateContent(JSON.stringify(response.data, null, "\t"));
            })
            .catch((err) => {
              setRawStateContent(`{"error":"Failed to load raw state${err}"}`);
            });
        })
        .catch((err) => {
          setStateContent(`{"error":"Failed to load state ${err}"}`);
          setRawStateContent(`{"error":"Failed to load state ${err}"}`);
        });
    else
      axiosClient
        .get(state.output)
        .then((resp) => {
          loadData(resp.data);
        })
        .catch((err) => {
          setStateContent(`{"error":"Failed to load state ${err}"}`);
          setRawStateContent(`{"error":"Failed to load state ${err}"}`);
        });
  };

  const tabs = [
    {
      key: "diagram",
      tab: "diagram",
      disabled: !manageState,
    },
    {
      key: "raw",
      disabled: !manageState,
      tab: (
        <span>
          code&nbsp;
          <Tooltip title="This is the terraform/opentofu state">
            <InfoCircleOutlined style={{ fontSize: "12px" }} />
          </Tooltip>
        </span>
      ),
    },
    {
      key: "json",
      disabled: !manageState,
      tab: (
        <span>
          json&nbsp;
          <Tooltip title="This is the result from terraform/tofu show -json">
            <InfoCircleOutlined style={{ fontSize: "12px" }} />
          </Tooltip>
        </span>
      ),
    },
  ];

  const onTabChange = (key: string) => {
    setactivetab(key);
    if (key === "json" && jsonEditorRef.current) {
      jsonEditorRef.current.layout();
    } else if (key === "raw" && editorRef.current) {
      editorRef.current.layout();
    }
  };

  const nodeTypes = useMemo(
    () => ({
      resourceNode: NodeResource,
    }),
    []
  );

  const handleRollback = () => {
    const outputUrl = currentState!.output;
    const rollbackUrl = outputUrl.replace("/state/", "/rollback/"); // Replace /state/ with /rollback/

    axiosInstance
      .put(rollbackUrl)
      .then((response) => {
        // Show success message on successful rollback
        message.success(
          "The state was successfully rolled back. Please verify that the workspace version is compatible with this state."
        );

        console.log("Rollback successful:", response.data);
        onRollback(false, false);
        setStateDetailsVisible(false);
      })
      .catch((error) => {
        // Extract error message from the API response
        let errorMessage = "An unexpected error occurred.";
        if (error.response?.status === 403) {
          errorMessage = "You do not have permission to perform this action.";
        }

        errorMessage = error.response?.data || errorMessage;

        // Show the error message
        message.error(`Failed to roll back the state: ${errorMessage}`);

        // Log the error for debugging purposes
        console.error("Error during rollback:", error);
      });
  };

  return (
    <div>
      {!stateDetailsVisible ? (
        <List
          split
          className="moduleList"
          dataSource={history.sort(
            (a: FlatJobHistory, b: FlatJobHistory) =>
              new Date(b.createdDate).getTime() - new Date(a.createdDate!).getTime()
          )}
          renderItem={(item) => (
            <List.Item>
              <Card onClick={() => handleClick(item)} style={{ width: "100%" }} hoverable>
                <Space className="states" size={40} split="|">
                  <span>#{item.id}</span>
                  <span>
                    <b>{item.createdBy}</b> triggered from Terraform {item.relativeDate}
                  </span>
                  <span>
                    <a>
                      {/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(item.jobReference)
                        ? `rollback to #${item.jobReference}`
                        : `job #${item.jobReference}`}
                    </a>
                  </span>
                </Space>
              </Card>
            </List.Item>
          )}
        />
      ) : (
        <div style={{ paddingTop: "10px" }}>
          <Row>
            <Col span={1}>
              <Avatar shape="square" icon={<UserOutlined />} />
            </Col>
            <Col span={19}>
              <Typography.Title level={3} style={{ margin: 0 }}>
                {currentState?.title}
              </Typography.Title>
              <Space className="stateDetails" size={40} split="|">
                <span>#{currentState?.id}</span>
                <span>
                  <b>{currentState?.createdBy}</b> triggered from Terraform{" "}
                  <span className="stateDetails">{currentState?.relativeDate}</span>
                </span>
                <span>
                  <a>
                    {/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(currentState!.jobReference)
                      ? `rollback to #${currentState?.jobReference}`
                      : `job #${currentState?.jobReference}`}
                  </a>
                </span>
              </Space>
            </Col>
            <Col span={3}>
              <Space style={{ marginTop: "30px" }} direction="horizontal">
                <Popconfirm
                  title="Are you sure?"
                  description={
                    <span>
                      Restoring this workspace to its previous state may lead to loss of data. <br /> Any resources that
                      have been added or modified since this state was saved <br /> will no longer be tracked by
                      Terrakube.
                    </span>
                  }
                  onConfirm={handleRollback}
                  okText="Yes"
                  cancelText="No"
                >
                  <Tooltip title="Rollback to this State Version">
                    <Button icon={<RollbackOutlined />} danger type="default" disabled={!manageState}>
                      Rollback
                    </Button>
                  </Tooltip>
                </Popconfirm>
                <DownloadState stateUrl={currentState!.output} manageState={manageState} />
              </Space>
            </Col>
          </Row>
          <Row style={{ paddingTop: "30px" }}>
            <Col span={24}>
              <Card
                style={{ width: "100%" }}
                tabList={tabs}
                activeTabKey={activeTab}
                onTabChange={(key) => {
                  onTabChange(key);
                }}
              >
                {activeTab === "diagram" ? (
                  <div style={{ height: 500 }}>
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
                ) : activeTab === "raw" ? (
                  <Editor
                    key="raw"
                    height="60vh"
                    options={{ ...monacoOptions, readOnly: true }}
                    onMount={handleEditorDidMount}
                    defaultLanguage="json"
                    defaultValue={manageState ? rawStateContent : "No access to raw state"}
                    theme={getMonacoTheme(token.colorBgContainer === "#141414" ? "dark" : "light")}
                  />
                ) : (
                  <Editor
                    key="json"
                    height="60vh"
                    options={{ ...monacoOptions, readOnly: true }}
                    onMount={handleJSONEditorDidMount}
                    defaultLanguage="json"
                    defaultValue={manageState ? stateContent : "No access to state"}
                    theme={getMonacoTheme(token.colorBgContainer === "#141414" ? "dark" : "light")}
                  />
                )}
              </Card>
            </Col>
          </Row>
        </div>
      )}
    </div>
  );
};
