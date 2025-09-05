import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  LockOutlined,
  PlayCircleOutlined,
  ProfileOutlined,
  StopOutlined,
  SyncOutlined,
  ThunderboltOutlined,
  UnlockOutlined,
  UserOutlined,
} from "@ant-design/icons";
import {
  Alert,
  Avatar,
  Breadcrumb,
  Button,
  Col,
  Divider,
  Layout,
  List,
  message,
  Row,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
  Card,
  Segmented,
  Flex,
  Select,
  Input,
  theme,
} from "antd";
import { AxiosInstance } from "axios";
import { DateTime } from "luxon";
import { useEffect, useState } from "react";
import { IconContext } from "react-icons";
import { BiTerminal } from "react-icons/bi";
import { FiGitCommit } from "react-icons/fi";
import { HiOutlineExternalLink } from "react-icons/hi";
import { Link, useNavigate, useParams } from "react-router-dom";
import ActionLoader from "../../ActionLoader.js";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME, WORKSPACE_ARCHIVE } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { CreateJob } from "../Jobs/Create";
import { DetailsJob } from "../Jobs/Details";
import {
  Action,
  ActionWithSettings,
  FlatJob,
  FlatJobHistory,
  FlatSchedule,
  FlatVariable,
  IncludedItem,
  Organization,
  Resource,
  Schedule,
  StateOutputValue,
  Template,
  VcsType,
  Workspace,
} from "../types.js";
import { CLIDriven } from "../Workspaces/CLIDriven";
import { ResourceDrawer } from "../Workspaces/ResourceDrawer";
import { Schedules } from "../Workspaces/Schedules";
import { States } from "../Workspaces/States";
import { Tags } from "../Workspaces/Tags";
import { Variables } from "../Workspaces/Variables";
import { getServiceIcon } from "./Icons.jsx";
import { WorkspaceSettings } from "./Settings/WorkspaceSettings";
import { getIaCIconById, getIaCNameById, renderVCSLogo } from "./Workspaces";
import "./Workspaces.css";
import RunList from "@/modules/workspaces/components/RunList";
const { Paragraph } = Typography;

const include = {
  VARIABLE: "variable",
  JOB: "job",
  HISTORY: "history",
  SCHEDULE: "schedule",
  VCS: "vcs",
  AGENT: "agent",
  WEBHOOK: "webhook",
  REFERENCE: "reference",
  ORGANIZATION: "organization",
};
const { Content } = Layout;
const { TabPane } = Tabs;

type Props = {
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
  selectedTab?: string;
};

type Params = {
  id: string;
  runid: string;
  orgid: string;
};

type StateOutputVariableWithName = { name: string } & StateOutputValue;

export const WorkspaceDetails = ({ setOrganizationName, selectedTab }: Props) => {
  const navigate = useNavigate();
  const { id, runid, orgid } = useParams<Params>();
  if (orgid !== null && orgid !== undefined && orgid !== "") {
    sessionStorage.setItem(ORGANIZATION_ARCHIVE, orgid);
  }
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE)!;
  sessionStorage.setItem(WORKSPACE_ARCHIVE, id!);
  const [workspace, setWorkspace] = useState<Workspace>();
  const [manageWorkspace, setManageWorkspace] = useState(false);
  const [manageState, setManageState] = useState(false);
  const [variables, setVariables] = useState<FlatVariable[]>([]);
  const [collectionVariables, setCollectionVariables] = useState<any[]>([]);
  const [collectionEnvVariables, setCollectionEnvVariables] = useState<any[]>([]);
  const [globalVariables, setGlobalVariables] = useState<FlatVariable[]>([]);
  const [globalEnvVariables, setGlobalEnvVariables] = useState<FlatVariable[]>([]);
  const [history, setHistory] = useState<FlatJobHistory[]>([]);
  const [schedule, setSchedule] = useState<FlatSchedule[]>([]);
  const [open, setOpen] = useState(false);
  const [resource, setResource] = useState<Resource>();
  const [envVariables, setEnvVariables] = useState<FlatVariable[]>([]);
  const [jobs, setJobs] = useState<FlatJob[]>([]);
  const [stateDetailsVisible, setStateDetailsVisible] = useState(false);
  const [jobId, setJobId] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [jobVisible, setJobVisible] = useState(false);
  const [organizationNameLocal, setOrganizationNameLocal] = useState<string>();
  const [workspaceName, setWorkspaceName] = useState("...");
  const [activeKey, setActiveKey] = useState(selectedTab !== null ? selectedTab : "1");
  const [templates, setTemplates] = useState([]);
  const [lastRun, setLastRun] = useState("");
  const [executionMode, setExecutionMode] = useState("...");
  const [agent, setAgent] = useState("...");
  const [orgTemplates, setOrgTemplates] = useState([]);
  const [vcsProvider, setVCSProvider] = useState<VcsType>(VcsType.UNKNOWN);
  const [resources, setResources] = useState<Resource[]>([]);
  const [outputs, setOutputs] = useState<StateOutputVariableWithName[]>([]);
  const [currentStateId, setCurrentStateId] = useState("");
  const [actions, setActions] = useState<Action[]>([]);
  const [contextState, setContextState] = useState({});
  const {
    token: { colorBgContainer },
  } = theme.useToken();

  const handleClick = (jobid: string) => {
    changeJob(jobid);
    navigate(`/organizations/${organizationId}/workspaces/${id}/runs/${jobid}`);
  };

  const outputColumns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      sorter: (a: StateOutputVariableWithName, b: StateOutputVariableWithName) => a.name.localeCompare(b.name),
    },
    {
      title: "Type",
      dataIndex: "type",
      key: "type",
      sorter: (a: StateOutputVariableWithName, b: StateOutputVariableWithName) => a.type.localeCompare(b.type),
    },
    {
      title: "Value",
      dataIndex: "value",
      key: "value",
      render: (text: string) => (
        <Paragraph style={{ margin: "0px" }} copyable={{ tooltips: false }}>
          {text}
        </Paragraph>
      ),
    },
  ];

  const resourceColumns = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      sorter: (a: Resource, b: Resource) => a.name.localeCompare(b.name),
      render: (text: string, record: Resource) => (
        <Button onClick={() => showDrawer(record)} type="link">
          {text} &nbsp;
          <HiOutlineExternalLink />
        </Button>
      ),
    },
    {
      title: "Provider",
      dataIndex: "provider",
      key: "provider",
      sorter: (a: Resource, b: Resource) => a.provider.localeCompare(b.provider),
    },
    {
      title: "Type",
      dataIndex: "type",
      key: "type",
      onFilter: (value: React.Key | boolean, record: Resource) => record.type.indexOf(value as any) === 0,
      sorter: (a: Resource, b: Resource) => a.type.localeCompare(b.type),
      render: (text: string, record: Resource) => (
        <>
          <Avatar shape="square" size="small" src={getServiceIcon(record.provider, record.type)} /> &nbsp;{text}
        </>
      ),
    },
    {
      title: "Module",
      dataIndex: "module",
      key: "module",
    },
  ];
  const handleStatesClick = (key: string) => {
    switchKey(key);
  };
  const callback = (key: string) => {
    switchKey(key);
  };

  const loadOrgTemplates = () => {
    axiosInstance.get(`organization/${organizationId}/template`).then((response) => {
      setOrgTemplates(response.data.data);
    });
  };

  const showDrawer = (record: Resource) => {
    setOpen(true);
    setResource(record);
  };

  const switchKey = (key: string) => {
    setActiveKey(key);
    switch (key) {
      case "1":
        navigate(`/organizations/${organizationId}/workspaces/${id}`);
        break;
      case "2":
        setJobVisible(false);
        navigate(`/organizations/${organizationId}/workspaces/${id}/runs`);
        break;
      case "3":
        setStateDetailsVisible(false);
        navigate(`/organizations/${organizationId}/workspaces/${id}/states`);
        break;
      case "4":
        navigate(`/organizations/${organizationId}/workspaces/${id}/variables`);
        break;
      case "5":
        navigate(`/organizations/${organizationId}/workspaces/${id}/schedules`);
        break;
      case "6":
        navigate(`/organizations/${organizationId}/workspaces/${id}/settings`);
        break;
      default:
        break;
    }
  };

  const evaluateCriteria = (criteria: any, _: any) => {
    try {
      const result = eval(criteria.filter);

      if (result) {
        if (!criteria.settings) {
          return {};
        }
        return criteria.settings.reduce((acc: any, setting: any) => {
          acc[setting.key] = setting.value;
          return acc;
        }, {});
      }
    } catch (error) {
      console.error("Error evaluating criteria:", error);
    }
    return null;
  };

  const fetchActions = async () => {
    try {
      const response = await axiosInstance.get(`action?filter[action]=active==true;type=in=('Workspace/Action')`);

      const fetchedActions = response.data.data || [];
      setActions(fetchedActions);
    } catch (error) {
      console.error("Error fetching actions:", error);
    }
  };

  useEffect(() => {
    setLoading(true);
    loadWorkspace(true, true, true);
    loadPermissionSet();
    setLoading(false);
    loadOrgTemplates();
    const interval = setInterval(() => {
      loadWorkspace(false, false, false);
      loadPermissionSet();
    }, 10000);
    return () => clearInterval(interval);
  }, [id]);

  const changeJob = (id: string) => {
    setJobId(id);
    setJobVisible(true);
    setActiveKey("2");
  };

  const loadPermissionSet = () => {
    const url = `${
      new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
    }/access-token/v1/teams/permissions/organization/${organizationId}`;
    axiosInstance.get(url).then((response) => {
      setManageState(response.data.manageState);
      setManageWorkspace(response.data.manageWorkspace);

      if (id !== undefined && id !== null) {
        const urlWorkspaceAccess = `${
          new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
        }/access-token/v1/teams/permissions/organization/${organizationId}/workspace/${id}`;
        axiosInstance.get(urlWorkspaceAccess).then((response) => {
          setManageState(response.data.manageState);
          setManageWorkspace(response.data.manageWorkspace);
        });
      }
    });
  };

  const loadWorkspace = (_loadVersions: boolean, _loadWebhook = false, _loadPermissionSet = false) => {
    let url = `organization/${organizationId}/workspace/${id}?include=job,variable,history,schedule,vcs,agent,organization,reference`;
    if (_loadWebhook) url += ",webhook";
    axiosInstance.get(`organization/${organizationId}/template`).then((template) => {
      setTemplates(template.data.data);
      axiosInstance
        .get(
          `organization/${organizationId}/workspace/${id}?include=job,variable,history,schedule,vcs,agent,organization,webhook,reference`
        )
        .then((response) => {
          if (_loadPermissionSet) loadPermissionSet();

          setWorkspace(response.data.data);

          if (response.data.included) {
            setupWorkspaceIncludes(
              response.data,
              setVariables,
              setJobs,
              setEnvVariables,
              setHistory,
              setSchedule,
              template.data.data,
              setLastRun,
              setVCSProvider,
              setCurrentStateId,
              currentStateId,
              axiosInstance,
              setResources,
              setOutputs,
              setAgent,
              _loadWebhook,
              setContextState,
              setCollectionVariables,
              setCollectionEnvVariables,
              setGlobalVariables,
              setGlobalEnvVariables
            );
          }

          const organization: Organization | undefined = response.data.included.find(
            (item: IncludedItem<Organization>) => item.type === "organization"
          );
          if (organization) {
            const organizationName = organization.attributes.name;
            setOrganizationName(organizationName);
            sessionStorage.setItem(ORGANIZATION_NAME, organizationName);
          }
          setOrganizationNameLocal(sessionStorage.getItem(ORGANIZATION_NAME)!);
          setWorkspaceName(response.data.data.attributes.name);
          setExecutionMode(response.data.data.attributes.executionMode);
          if (runid && _loadVersions) changeJob(runid); // if runid is provided, show the job details
          fetchActions();
        });
    });
  };

  const handleClickSettings = () => {
    switchKey("6");
  };

  const handleLockButton = (locked: boolean) => {
    const body = {
      data: {
        type: "workspace",
        id: id,
        attributes: {
          locked: !locked,
        },
      },
    };
    axiosInstance
      .patch(`organization/${organizationId}/workspace/${id}`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        if (response.status === 204) {
          loadWorkspace(true);
          var newstatus = locked ? "unlocked" : "locked";
          message.success("Workspace " + newstatus + " successfully");
        } else {
          var newstatus = locked ? "unlock" : "lock";
          message.error("Workspace " + newstatus + " failed");
        }
      });
  };

  return (
    <Content style={{ padding: "0 50px" }}>
      <Breadcrumb
        style={{ margin: "16px 0" }}
        items={[
          {
            title: organizationNameLocal,
          },
          {
            title: <Link to={`/organizations/${organizationId}/workspaces`}>Workspaces</Link>,
          },
          {
            title: workspaceName,
          },
        ]}
      />

      <div className="site-layout-content" style={{ background: colorBgContainer }}>
        <div className="workspaceDisplay">
          {loading || !workspace || !variables || !jobs ? (
            <Spin spinning={true} tip="Loading Workspace...">
              <p style={{ marginTop: "50px" }}></p>
            </Spin>
          ) : (
            <div className="orgWrapper">
              <div className="variableActions">
                <h2>{workspace.attributes.name}</h2>
              </div>
              <Space className="workspace-details" direction="vertical">
                <Paragraph style={{ margin: "0px" }} copyable={{ text: id, tooltips: false }}>
                  <Typography.Text type="secondary"> ID: {id} </Typography.Text>
                </Paragraph>
                {workspace.attributes?.description === "" ? (
                  <a className="workspace-button" onClick={handleClickSettings}>
                    Add workspace description
                  </a>
                ) : (
                  <Typography.Text type="secondary">{workspace.attributes.description}</Typography.Text>
                )}
                <Space size={40} style={{ marginBottom: "40px" }} direction="horizontal">
                  <Typography.Text>
                    {workspace.attributes.locked ? (
                      <>
                        <LockOutlined /> Locked
                      </>
                    ) : (
                      <>
                        <UnlockOutlined /> Unlocked
                      </>
                    )}
                  </Typography.Text>
                  <Typography.Text>
                    <ProfileOutlined /> Resources <span style={{ fontWeight: "500" }}>{resources.length}</span>
                  </Typography.Text>
                  <Space direction="horizontal">
                    {getIaCIconById(workspace.attributes?.iacType)}
                    <Typography.Text>
                      {getIaCNameById(workspace.attributes?.iacType)}{" "}
                      <a onClick={handleClickSettings} className="workspace-button">
                        v{workspace.attributes.terraformVersion}
                      </a>
                    </Typography.Text>
                  </Space>

                  <Typography.Text>
                    <ClockCircleOutlined /> Updated{" "}
                    <span style={{ fontWeight: "500" }}>
                      {DateTime.fromISO(lastRun).toRelative() ?? "never executed"}
                    </span>
                  </Typography.Text>

                  <span>
                    {workspace.attributes.locked ? (
                      <>
                        <Alert
                          message="Lock Description"
                          description={workspace.attributes.lockDescription}
                          type="warning"
                          showIcon
                        />
                      </>
                    ) : (
                      <></>
                    )}
                  </span>
                </Space>
              </Space>

              <Tabs
                activeKey={activeKey}
                defaultActiveKey={selectedTab}
                onTabClick={handleStatesClick}
                tabBarExtraContent={
                  <>
                    <Space direction="horizontal">
                      {actions &&
                        actions
                          .reduce((acc: ActionWithSettings[], action: ActionWithSettings) => {
                            if (!action.attributes.displayCriteria) {
                              acc.push(action);
                              return acc;
                            }

                            let displayCriteria;
                            try {
                              displayCriteria = JSON.parse(action.attributes.displayCriteria);
                            } catch (error) {
                              console.error("Error parsing displayCriteria JSON:", error);
                              return acc;
                            }

                            for (const criteria of displayCriteria) {
                              const settings = evaluateCriteria(criteria, {
                                workspace: workspace,
                                state: contextState,
                                resources: resources,
                                apiUrl: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin,
                                settings: action.settings,
                              });
                              if (settings) {
                                action.settings = settings; // Attach settings to the action
                                acc.push(action);
                                break;
                              }
                            }

                            return acc;
                          }, [])
                          .filter((action) => action?.attributes.type === "Workspace/Action")
                          .map((action, index) => (
                            <ActionLoader
                              key={index}
                              action={action?.attributes.action}
                              context={{
                                workspace: workspace,
                                state: contextState,
                                resources: resources,
                                apiUrl: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin,
                                settings: action.settings,
                              }}
                            />
                          ))}
                      <Button
                        type="default"
                        htmlType="button"
                        onClick={() => handleLockButton(workspace.attributes.locked)}
                        icon={workspace.attributes.locked ? <UnlockOutlined /> : <LockOutlined />}
                        disabled={!manageWorkspace}
                      >
                        {workspace.attributes.locked ? "Unlock" : "Lock"}
                      </Button>
                      <CreateJob changeJob={changeJob} />
                    </Space>
                  </>
                }
                onChange={callback}
              >
                <TabPane tab="Overview" key="1">
                  <Row>
                    <Col span={19} style={{ paddingRight: "20px" }}>
                      {workspace.attributes.source === "empty" &&
                      workspace.attributes.branch === "remote-content" &&
                      (workspace.relationships?.history?.data?.length || 0) < 1 ? (
                        <CLIDriven organizationName={organizationNameLocal} workspaceName={workspaceName} />
                      ) : (
                        <div>
                          <h3>Latest Run</h3>
                          <div style={{ marginRight: "150px", borderWidth: "1px" }}>
                            <List
                              itemLayout="horizontal"
                              style={{
                                border: "1px solid #c2c5cb",
                                padding: "24px",
                              }}
                              dataSource={
                                jobs.length > 0
                                  ? jobs
                                      .sort((a: any, b: any) => a.id - b.id)
                                      .reverse()
                                      .slice(0, 1)
                                  : []
                              }
                              renderItem={(item) => (
                                <List.Item>
                                  <List.Item.Meta
                                    style={{ margin: "0px", padding: "0px" }}
                                    avatar={<Avatar shape="square" icon={<UserOutlined />} />}
                                    description={
                                      <div>
                                        <Row>
                                          <Col span={20}>
                                            <h4 className="ant-list-item-meta-title">
                                              <a onClick={() => handleClick(item.id)}>{item.title}</a>{" "}
                                            </h4>
                                            <b>{item.createdBy}</b> triggered a run {item.latestChange} via{" "}
                                            <b>{item.via || "UI"}</b>{" "}
                                            {item.commitId !== "000000000" ? (
                                              <>
                                                <FiGitCommit /> {item.commitId?.substring(0, 6)}{" "}
                                              </>
                                            ) : (
                                              ""
                                            )}
                                          </Col>
                                          <Col>
                                            {
                                              <div className="textLeft">
                                                <Tag
                                                  icon={
                                                    item.status == "completed" ? (
                                                      <CheckCircleOutlined />
                                                    ) : item.status == "running" ? (
                                                      <SyncOutlined spin />
                                                    ) : item.status === "waitingApproval" ? (
                                                      <ExclamationCircleOutlined />
                                                    ) : item.status === "cancelled" ? (
                                                      <StopOutlined />
                                                    ) : item.status === "failed" ? (
                                                      <StopOutlined />
                                                    ) : item.status === "notExecuted" ? (
                                                      <CheckCircleOutlined />
                                                    ) : (
                                                      <ClockCircleOutlined />
                                                    )
                                                  }
                                                  color={item.statusColor}
                                                >
                                                  {item.status}
                                                </Tag>{" "}
                                              </div>
                                            }
                                          </Col>
                                        </Row>
                                        <br />
                                        <br />
                                        <Row>
                                          <Col span={20}></Col>
                                          <Col>
                                            <Button onClick={() => handleClick(item.id)}>See details</Button>
                                          </Col>
                                        </Row>
                                      </div>
                                    }
                                  />
                                </List.Item>
                              )}
                            />
                          </div>
                          <Tabs
                            type="card"
                            style={{ marginTop: "30px" }}
                            items={[
                              {
                                label: `Resources (${resources.length})`,
                                key: "1",
                                children: <Table dataSource={resources} columns={resourceColumns} />,
                              },
                              {
                                label: `Outputs (${outputs.length})`,
                                key: "2",
                                children: <Table dataSource={outputs} columns={outputColumns} />,
                              },
                            ]}
                          />

                          <ResourceDrawer resource={resource} workspace={workspace} setOpen={setOpen} open={open} />
                        </div>
                      )}
                    </Col>
                    <Col span={5}>
                      <Space direction="vertical">
                        <br />
                        <span>
                          {workspace.attributes.branch !== "remote-content" ? (
                            <>
                              {" "}
                              {renderVCSLogo(vcsProvider)}{" "}
                              <a href={fixSshURL(workspace.attributes.source)} target="_blank" rel="noreferrer">
                                {new URL(fixSshURL(workspace.attributes.source))?.pathname
                                  ?.replace(".git", "")
                                  ?.substring(1)}
                              </a>
                            </>
                          ) : (
                            <>
                              <IconContext.Provider value={{ size: "1.4em" }}>
                                <BiTerminal />
                              </IconContext.Provider>
                              &nbsp;&nbsp;cli/api driven workflow
                            </>
                          )}
                        </span>
                        <span>
                          <ThunderboltOutlined /> Execution Mode:{" "}
                          <a onClick={handleClickSettings}>{executionMode}</a>{" "}
                        </span>
                        <span>
                          <PlayCircleOutlined /> Auto apply: <a>Off</a>{" "}
                        </span>
                        <Divider />
                        <h4>Tags</h4>
                        <Tags organizationId={organizationId} workspaceId={id!} manageWorkspace={manageWorkspace} />
                      </Space>
                    </Col>
                  </Row>
                </TabPane>

                <TabPane tab="Runs" key="2">
                  {jobVisible ? <DetailsJob jobId={jobId!} /> : <RunList jobs={jobs} onRunClick={handleClick} />}
                </TabPane>
                <TabPane tab="States" key="3" disabled={!manageState}>
                  <States
                    history={history}
                    setStateDetailsVisible={setStateDetailsVisible}
                    stateDetailsVisible={stateDetailsVisible}
                    workspace={workspace}
                    onRollback={loadWorkspace}
                    manageState={manageState}
                  />
                </TabPane>
                <TabPane tab="Variables" key="4">
                  <Variables
                    vars={variables}
                    env={envVariables}
                    manageWorkspace={manageWorkspace}
                    collectionVars={collectionVariables}
                    collectionEnvVars={collectionEnvVariables}
                    globalVariables={globalVariables}
                    globalEnvVariables={globalEnvVariables}
                  />
                </TabPane>
                <TabPane tab="Schedules" key="5">
                  {templates ? <Schedules schedules={schedule} manageWorkspace={manageWorkspace} /> : <p>Loading...</p>}
                </TabPane>
                <TabPane tab="Settings" key="6">
                  <WorkspaceSettings
                    workspace={workspace}
                    vcsProvider={vcsProvider}
                    orgTemplates={orgTemplates}
                    manageWorkspace={manageWorkspace}
                  />
                </TabPane>
              </Tabs>
            </div>
          )}
        </div>
      </div>
    </Content>
  );
};

function setupWorkspaceIncludes(
  data: any,
  setVariables: (val: any[]) => void,
  setJobs: (val: any[]) => void,
  setEnvVariables: (val: any[]) => void,
  setHistory: (val: FlatJobHistory[]) => void,
  setSchedule: (val: any[]) => void,
  templates: Template[],
  setLastRun: (val: string) => void,
  setVCSProvider: (val: VcsType) => void,
  setCurrentStateId: (val: string) => void,
  currentStateId: string,
  axiosInstance: any,
  setResources: (val: any[]) => void,
  setOutputs: (val: any[]) => void,
  setAgent: (val: any) => void,
  _loadWebhook: boolean,
  setContextState: (val: any) => void,
  setCollectionVariables: (val: any[]) => void,
  setCollectionEnvVariables: (val: any[]) => void,
  setGlobalVariables: (val: FlatVariable[]) => void,
  setGlobalEnvVariables: (val: FlatVariable[]) => void
) {
  const variables: FlatVariable[] = [];
  const jobs: FlatJob[] = [];
  const webhooks: any = {};
  const envVariables: FlatVariable[] = [];
  const history: FlatJobHistory[] = [];
  const schedule: Schedule[] = [];
  const collectionVariables: any[] = [];
  const collectionEnvVariables: any[] = [];
  const globalVariables: FlatVariable[] = [];
  const globalEnvVariables: FlatVariable[] = [];
  const includes = data.included;
  includes.forEach((element: any) => {
    switch (element.type) {
      case include.ORGANIZATION:
        axiosInstance.get(`/organization/${element.id}/globalvar`).then((response: any) => {
          const globalVar = response.data.data;
          if (globalVar != null) {
            globalVar.forEach((variableItem: any) => {
              if (variableItem.attributes.category === "ENV") {
                globalEnvVariables.push({
                  id: variableItem.id,
                  type: variableItem.type,
                  ...variableItem.attributes,
                });
              } else {
                globalVariables.push({
                  id: variableItem.id,
                  type: variableItem.type,
                  ...variableItem.attributes,
                });
              }
            });
          }
        });
        break;
      case include.JOB:
        let finalColor = "";
        switch (element.attributes.status) {
          case "completed":
            finalColor = "#2eb039";
            break;
          case "noChanges":
            finalColor = "#9f37fa";
            break;
          case "rejected":
            finalColor = "#FB0136";
            break;
          case "failed":
            finalColor = "#FB0136";
            break;
          case "running":
            finalColor = "#108ee9";
            break;
          case "waitingApproval":
            finalColor = "#fa8f37";
            break;
          default:
            finalColor = "";
            break;
        }
        jobs.push({
          id: element.id,
          title: "Queue manually using " + getIaCNameById(data?.data?.attributes?.iacType),
          statusColor: finalColor,
          commitId: element.attributes.commitId,
          stepNumber: element.attributes.stepNumber,
          latestChange: DateTime.fromISO(element.attributes.createdDate).toRelative(),
          ...element.attributes,
        });
        setLastRun(element.attributes.updatedDate);
        break;
      case include.HISTORY:
        console.log(element);
        history.push({
          id: element.id,
          title: "Queue manually using " + getIaCNameById(data?.data?.attributes?.iacType),
          relativeDate: DateTime.fromISO(element.attributes.createdDate).toRelative(),
          createdDate: element.attributes.createdDate,
          ...element.attributes,
        });
        break;

      case include.SCHEDULE:
        schedule.push({
          id: element.id,
          name: templates?.find((template: Template) => template.id === element.attributes.templateReference)
            ?.attributes?.name,
          ...element.attributes,
        });
        break;
      case include.VCS:
        setVCSProvider(element.attributes.vcsType);
        break;
      case include.AGENT:
        setAgent(element.id);
        break;
      case include.VARIABLE:
        if (element.attributes.category == "ENV") {
          envVariables.push({
            id: element.id,
            type: element.type,
            ...element.attributes,
          });
        } else {
          variables.push({
            id: element.id,
            type: element.type,
            ...element.attributes,
          });
        }
        break;
      case include.WEBHOOK:
        webhooks[element.attributes.event] = {
          id: element.id,
          type: element.type,
          ...element.attributes,
        };
        break;
      case include.REFERENCE:
        axiosInstance.get(`/reference/${element.id}/collection?include=item`).then((response: any) => {
          const collectionInfo = response.data.data;
          if (response.data.included != null) {
            const items = response.data.included;
            items.forEach((item: any) => {
              item.attributes.priority = collectionInfo.attributes.priority;
              item.attributes.collectionName = collectionInfo.attributes.name;
              if (item.attributes.category === "ENV") {
                collectionEnvVariables.push({
                  id: item.id,
                  type: item.type,
                  ...item.attributes,
                });
              } else {
                collectionVariables.push({
                  id: item.id,
                  type: item.type,
                  ...item.attributes,
                });
              }
            });
          }
        });
        break;
    }
  });

  setVariables(variables);
  setEnvVariables(envVariables);
  setJobs(jobs);
  setHistory(history);
  setSchedule(schedule);
  setCollectionVariables(collectionVariables);
  setCollectionEnvVariables(collectionEnvVariables);
  setGlobalVariables(globalVariables);
  setGlobalEnvVariables(globalEnvVariables);

  // set state data
  const lastState = history
    .sort((a: FlatJobHistory, b: FlatJobHistory) => parseInt(a.jobReference) - parseInt(b.jobReference))
    .reverse()[0];
  // reload state only if there is a new version

  if (currentStateId !== lastState?.id) {
    const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
    const workspaceId = sessionStorage.getItem(WORKSPACE_ARCHIVE);
    const url = `${
      new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
    }/access-token/v1/teams/permissions/organization/${organizationId}/workspace/${workspaceId}`;
    axiosInstance.get(url).then((response: any) => {
      loadState(
        lastState,
        axiosInstance,
        setOutputs,
        setResources,
        sessionStorage.getItem(WORKSPACE_ARCHIVE)!,
        setContextState,
        response.data.manageState
      );
    });
  }
  setCurrentStateId(lastState?.id);
}

function loadState(
  state: any,
  axiosInstance: AxiosInstance,
  setOutputs: (val: any) => void,
  setResources: (val: any) => void,
  workspaceId: string,
  setContextState: (val: any) => void,
  manageState: boolean
) {
  if (!state || !manageState) {
    return;
  }

  let currentState;
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);

  axiosInstance.get(state.output).then((resp) => {
    let result = parseState(resp.data);
    setContextState(resp.data);
    if (result.outputs.length < 1 && result.resources.length < 1) {
      axiosInstance
        .get(
          `${
            new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
          }/tfstate/v1/organization/${organizationId}/workspace/${workspaceId}/state/terraform.tfstate`
        )
        .then((currentStateData) => {
          currentState = currentStateData.data;
          setContextState(currentState);
          result = parseOldState(currentState);

          setResources(result.resources);
          setOutputs(result.outputs);
        })
        .catch(function (error: Error) {
          console.error(error);
        });
    } else {
      setResources(result.resources);
      setOutputs(result.outputs);
    }
  });
}

function parseState(state: any) {
  let resources: any[] = [];
  const outputs: StateOutputVariableWithName[] = [];

  // parse root outputs
  if (state?.values?.outputs != null) {
    for (const [key, value] of Object.entries(state?.values?.outputs) as [any, any][]) {
      if (typeof value.type === "string") {
          if (value.sensitive) {
              outputs.push({
                  name: key,
                  type: value.type,
                  value: "*****",
              });
          } else {
              outputs.push({
                  name: key,
                  type: value.type,
                  value: value.value,
              });
          }
      } else {
        const jsonObject = JSON.stringify(value.value);
        if (value.sensitive) {
            outputs.push({
                name: key,
                type: "Other type",
                value: "*****",
            });
        } else {
            outputs.push({
                name: key,
                type: "Other type",
                value: jsonObject,
            });
        }

      }
    }
  } else {
    console.log("State has no outputs");
  }

  // parse root module resources
  if (state?.values?.root_module?.resources != null) {
    for (const [_, value] of Object.entries(state?.values?.root_module?.resources) as [any, any][]) {
      resources.push({
        name: value.name,
        type: value.type,
        provider: value.provider_name,
        module: "root_module",
        values: value.values,
        depends_on: value.depends_on,
      });
    }
  } else {
    console.log("State has no resources");
  }

  // parse child module resources
  if (state?.values?.root_module?.child_modules?.length > 0) {
    state?.values?.root_module?.child_modules?.forEach((moduleVal: any, index: any) => {
      if (moduleVal.resources != null)
        for (const [_, value] of Object.entries(moduleVal.resources) as [any, any][]) {
          resources.push({
            name: value.name,
            type: value.type,
            provider: value.provider_name,
            module: moduleVal.address,
            values: value.values,
            depends_on: value.depends_on,
          });
        }

      if (moduleVal.child_modules?.length > 0) {
        resources = parseChildModules(resources, moduleVal.child_modules);
      }
    });
  } else {
    console.log("State has no child modules resources");
  }

  return { resources: resources, outputs: outputs };
}

function parseOldState(state: any) {
  const resources: any[] = [];
  const outputs = [];
  console.log(state);
  if (state?.outputs != null) {
    for (const [key, value] of Object.entries(state?.outputs) as [any, any][]) {
      if (typeof value.type === "string") {
          if (value.sensitive) {
              outputs.push({
              name: key,
              type: value.type,
              value: "********",
              })
          } else {
              outputs.push({
                  name: key,
                  type: value.type,
                  value: value.value,
              });
          }
      } else {
        const jsonObject = JSON.stringify(value.value);
        if (value.sensitive) {
            outputs.push({
              name: key,
              type: "Other type",
              value: "********",
            })
        } else {
            outputs.push({
                name: key,
                type: "Other type",
                value: jsonObject,
            });
        }
      }
    }
  } else {
    console.log("State has no outputs");
  }

  console.log("Parsing resources and modules fallback method");
  if (state?.resources != null && state?.resources.length > 0) {
    state?.resources.forEach((value: any) => {
      if (value.module != null) {
        resources.push({
          name: value.name,
          type: value.type,
          provider: value.provider.replace("provider[", "").replace("]", ""),
          module: value.module,
          values: value.instances[0].attributes,
          depends_on: value.instances[0].dependencies,
        });
      } else {
        resources.push({
          name: value.name,
          type: value.type,
          provider: value.provider.replace('provider["', "").replace('"]', ""),
          module: "root_module",
          values: value.instances[0].attributes,
          depends_on: value.instances[0].dependencies,
        });
      }
    });
  } else {
    console.log("State has no resources/modules");
  }

  return { resources: resources, outputs: outputs };
}

function parseChildModules(resources: any, child_modules?: any) {
  child_modules?.forEach((moduleVal: any, index: number) => {
    if (moduleVal.resources != null)
      for (const [_, value] of Object.entries(moduleVal.resources) as [any, any][]) {
        resources.push({
          name: value.name,
          type: value.type,
          provider: value.provider_name,
          module: moduleVal.address,
          values: value.values,
          depends_on: value.depends_on,
        });
      }

    if (moduleVal.child_modules?.length > 0) {
      resources = parseChildModules(resources);
    }
  });

  return resources;
}

function fixSshURL(source: string) {
  if (source.startsWith("git@")) {
    return source.replace(":", "/").replace("git@", "https://");
  } else {
    return source;
  }
}
