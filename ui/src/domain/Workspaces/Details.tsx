import { ApprovalAttribution } from "@/components/display/ApprovalAttribution";
import {
  ArrowRightOutlined,
  BranchesOutlined,
  ClockCircleOutlined,
  FolderOutlined,
  LockOutlined,
  ProfileOutlined,
  ProjectOutlined,
  ThunderboltOutlined,
  UnlockOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Alert, Avatar, Button, Col, Empty, message, Row, Space, Table, Tabs, Typography, Modal } from "antd";

import { lazy, Suspense, useEffect, useState } from "react";
import { useJobStatusSubscription, usePolling } from "../../hooks";
import { BiTerminal } from "react-icons/bi";
import { FiGitCommit } from "react-icons/fi";
import { HiOutlineExternalLink } from "react-icons/hi";
import { Link, useNavigate, useParams } from "react-router-dom";
const ActionLoader = lazy(() => import("../../ActionLoader"));
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME, WORKSPACE_ARCHIVE } from "../../config/actionTypes";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { CreateJob } from "../Jobs/Create";
import PolicyStatusTag from "@/components/display/PolicyStatusTag";
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
  Template,
  VcsType,
  Workspace,
} from "../types.js";
import { CLIDriven } from "../Workspaces/CLIDriven";
import { ResourceDrawer } from "../Workspaces/ResourceDrawer";
import { RunTriggers } from "../Workspaces/RunTriggers";
import { Schedules } from "../Workspaces/Schedules";
import { Tags } from "../Workspaces/Tags";
import { Variables } from "../Workspaces/Variables";
import { getServiceIcon } from "./Icons.jsx";
import { getIaCIconById, getIaCNameById } from "./Workspaces";
import "./Workspaces.css";
import { UNLOCK_CONFIRM_TEXT } from "./Settings/lockingCopy";
import LoadingFallback from "@/components/feedback/LoadingFallback";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import { LinkButton } from "@/components/navigation/LinkButton";
import RunList from "@/modules/workspaces/components/RunList";
import WorkspaceStatusTag from "@/components/display/WorkspaceStatusTag";

import { setupWorkspaceIncludes, isValidUrl, fixSshURL, StateOutputVariableWithName } from "./workspaceDataUtils";
import VcsLogo from "@/components/display/VcsLogo";
import { formatDateTime, formatDuration, relativeTime } from "@/modules/utils/dates";
import { computeWorkspaceMetrics, FINISHED_JOB_STATUSES } from "./workspaceMetrics";
const DetailsJob = lazy(() => import("../Jobs/Details").then((m) => ({ default: m.DetailsJob })));
const States = lazy(() => import("../Workspaces/States").then((m) => ({ default: m.States })));
const WorkspaceSettings = lazy(() =>
  import("./Settings/WorkspaceSettings").then((m) => ({ default: m.WorkspaceSettings }))
);

const { Paragraph } = Typography;

const WORKSPACE_SECTION_LABELS: Record<string, string> = {
  "1": "Overview",
  "2": "Runs",
  "3": "States",
  "4": "Variables",
  "5": "Schedules",
  "6": "Settings",
  "7": "Run triggers",
};

const WORKSPACE_SETTINGS_SECTION_LABELS: Record<string, string> = {
  general: "General",
  policies: "Policies",
  locking: "Locking",
  sshkey: "SSH key",
  webhook: "Webhook",
  notifications: "Notifications",
  "state-shared": "State shared",
  "team-access": "Team access",
  advanced: "Destruction and deletion",
};

type Props = {
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
  setWorkspaceManageState: React.Dispatch<React.SetStateAction<boolean>>;
  selectedTab?: string;
  settingsSection?: string;
};

type Params = {
  id: string;
  runid: string;
  orgid: string;
};

export const WorkspaceDetails = ({
  setOrganizationName,
  setWorkspaceManageState,
  selectedTab,
  settingsSection,
}: Props) => {
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
  const [planJob, setPlanJob] = useState(false);
  const [approveJob, setApproveJob] = useState(false);
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
  const [loadError, setLoadError] = useState<string | null>(null);
  const [jobVisible, setJobVisible] = useState(false);
  const [organizationNameLocal, setOrganizationNameLocal] = useState<string>();
  const [workspaceName, setWorkspaceName] = useState("...");
  const [activeKey, setActiveKey] = useState(selectedTab ?? "1");
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
  const [projectName, setProjectName] = useState<string | null>(null);
  const [projectId, setProjectId] = useState<string | null>(null);

  const runLink = (jobid: string) => `/organizations/${organizationId}/workspaces/${id}/runs/${jobid}`;

  const getOutputValueFromState = (outputName: string): string => {
    const outputValue = (contextState as any)?.values?.outputs?.[outputName];
    if (outputValue) {
      if (typeof outputValue.type === "string") {
        return outputValue.value;
      } else {
        return JSON.stringify(outputValue.value);
      }
    }
    return "";
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
      render: (text: string, record: StateOutputVariableWithName) => (
        <Paragraph
          className="workspace-output-value"
          copyable={{ tooltips: false, text: getOutputValueFromState(record.name) }}
        >
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
      sorter: (a: Resource, b: Resource) => {
        const nameA =
          a.index !== undefined && a.index !== null
            ? typeof a.index === "string"
              ? `${a.name}["${a.index}"]`
              : `${a.name}[${a.index}]`
            : a.name;
        const nameB =
          b.index !== undefined && b.index !== null
            ? typeof b.index === "string"
              ? `${b.name}["${b.index}"]`
              : `${b.name}[${b.index}]`
            : b.name;
        return nameA.localeCompare(nameB);
      },
      render: (text: string, record: Resource) => {
        const displayName =
          record.index !== undefined && record.index !== null
            ? typeof record.index === "string"
              ? `${text}["${record.index}"]`
              : `${text}[${record.index}]`
            : text;
        return (
          <Button onClick={() => showDrawer(record)} type="link" className="resource-name-link" title={displayName}>
            <span className="resource-name-text">{displayName}</span>
            <HiOutlineExternalLink aria-hidden />
          </Button>
        );
      },
      width: "27%",
      ellipsis: true,
    },
    {
      title: "Provider",
      dataIndex: "provider",
      key: "provider",
      sorter: (a: Resource, b: Resource) => a.provider.localeCompare(b.provider),
      // Drop only the default registry host: "registry.terraform.io/hashicorp/null" reads "hashicorp/null".
      render: (text: string) => (
        <code className="resource-provider" title={text}>
          {text?.replace(/^registry\.(terraform\.io|opentofu\.org)\//, "")}
        </code>
      ),
      width: "25%",
      ellipsis: true,
    },
    {
      title: "Type",
      dataIndex: "type",
      key: "type",
      onFilter: (value: React.Key | boolean, record: Resource) => record.type.indexOf(value as any) === 0,
      sorter: (a: Resource, b: Resource) => a.type.localeCompare(b.type),
      render: (text: string, record: Resource) => (
        <span className="resource-type" title={text}>
          <img src={getServiceIcon(record.provider, record.type)} alt="" width={16} height={16} />
          <span>{text}</span>
        </span>
      ),
      width: "27%",
      ellipsis: true,
    },
    {
      title: "Module",
      dataIndex: "module",
      key: "module",
      ellipsis: true,
    },
  ];

  const loadOrgTemplates = () => {
    axiosInstance
      .get(`organization/${organizationId}/template`)
      .then((response) => {
        setOrgTemplates(response.data.data);
      })
      .catch((err) => {
        console.error("Failed to load org templates:", err);
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
      case "7":
        navigate(`/organizations/${organizationId}/workspaces/${id}/run-triggers`);
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
      const response = await axiosInstance.get("action", {
        params: { "filter[action]": "active==true;type=in=('Workspace/Action')" },
      });

      const fetchedActions = response.data.data || [];
      setActions(fetchedActions);
    } catch (error) {
      console.error("Error fetching actions:", error);
    }
  };

  useEffect(() => {
    setLoading(true);
    setLoadError(null);
    loadWorkspace(true, true, true);
    loadPermissionSet();
    loadOrgTemplates();
    // Polling is now handled by usePolling hook below
  }, [id]);

  useEffect(() => {
    setWorkspaceManageState(manageState);
  }, [manageState, setWorkspaceManageState]);

  useEffect(() => {
    setActiveKey(selectedTab ?? "1");
  }, [selectedTab]);

  // Keep the Runs view in sync with the URL: viewing /runs/:runid shows that job's
  // details, but navigating back to the bare /runs list (e.g. via the sidebar) must
  // reset jobVisible, otherwise it stays stuck showing the last-viewed job forever.
  useEffect(() => {
    if (runid) {
      setJobId(runid);
      setJobVisible(true);
    } else {
      setJobVisible(false);
    }
  }, [runid]);

  // Polling for workspace updates
  usePolling(
    () => {
      loadWorkspace(false, false, false);
    },
    { interval: 10000, enabled: Boolean(id), immediate: false }
  );

  // Pushes an immediate refresh on real job status changes; the poll above stays as a fallback for a
  // dropped WebSocket connection.
  useJobStatusSubscription({
    workspaceId: id ?? "",
    enabled: Boolean(id),
    onEvent: () => loadWorkspace(false, false, false),
  });

  const changeJob = (id: string) => {
    setJobId(id);
    setJobVisible(true);
    setActiveKey("2");
  };

  const loadPermissionSet = () => {
    const url = `${
      new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
    }/access-token/v1/teams/permissions/organization/${organizationId}`;
    axiosInstance
      .get(url)
      .then((response) => {
        setManageState(response.data.manageState);
        setManageWorkspace(response.data.manageWorkspace);
        setPlanJob(response.data.planJob);
        setApproveJob(response.data.approveJob);

        if (id !== undefined && id !== null) {
          const urlWorkspaceAccess = `${
            new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
          }/access-token/v1/teams/permissions/organization/${organizationId}/workspace/${id}`;
          axiosInstance
            .get(urlWorkspaceAccess)
            .then((response) => {
              setManageState(response.data.manageState);
              setManageWorkspace(response.data.manageWorkspace);
              setPlanJob(response.data.planJob);
              setApproveJob(response.data.approveJob);
            })
            .catch((err) => {
              console.error("Failed to load workspace permissions:", err);
            });
        }
      })
      .catch((err) => {
        console.error("Failed to load org permissions:", err);
      });
  };

  const loadWorkspace = (_loadVersions: boolean, _loadTemplates = false, _loadPermissionSet = false) => {
    const templatesRequest: Promise<any[]> = _loadTemplates
      ? axiosInstance.get(`organization/${organizationId}/template`).then((template) => {
          setTemplates(template.data.data);
          return template.data.data;
        })
      : Promise.resolve(templates);
    templatesRequest
      .then((templateList) => {
        axiosInstance
          .get(
            `organization/${organizationId}/workspace/${id}?include=job,variable,history,schedule,vcs,agent,organization,webhook,reference,project`
          )
          .then(async (response) => {
            if (_loadPermissionSet) loadPermissionSet();

            setWorkspace(response.data.data);

            if (response.data.included) {
              await setupWorkspaceIncludes(
                response.data,
                setVariables,
                setJobs,
                setEnvVariables,
                setHistory,
                setSchedule,
                templateList,
                setLastRun,
                setVCSProvider,
                setCurrentStateId,
                currentStateId,
                axiosInstance,
                setResources,
                setOutputs,
                setAgent,
                _loadTemplates,
                setContextState,
                setCollectionVariables,
                setCollectionEnvVariables,
                setGlobalVariables,
                setGlobalEnvVariables
              );
            }

            const organization: Organization | undefined = response.data.included?.find(
              (item: IncludedItem<Organization>) => item.type === "organization"
            );
            if (organization) {
              const organizationName = organization.attributes.name;
              setOrganizationName(organizationName);
              sessionStorage.setItem(ORGANIZATION_NAME, organizationName);
            }

            const proj = response.data.included?.find((item: any) => item.type === "project");
            if (proj) {
              setProjectName(proj.attributes?.name ?? null);
              setProjectId(proj.id ?? null);
            } else {
              setProjectName(null);
              setProjectId(null);
            }
            setOrganizationNameLocal(sessionStorage.getItem(ORGANIZATION_NAME)!);
            setWorkspaceName(response.data.data.attributes.name);
            setExecutionMode(response.data.data.attributes.executionMode);
            if (runid && _loadVersions) changeJob(runid); // if runid is provided, show the job details
            if (_loadVersions) fetchActions();
            setLoadError(null);
          })
          .catch((err) => {
            setLoadError(getErrorMessage(err));
          })
          .finally(() => {
            setLoading(false);
          });
      })
      .catch((err) => {
        setLoadError(getErrorMessage(err));
        setLoading(false);
      });
  };

  const handleClickSettings = () => {
    switchKey("6");
  };

  const [lockModal, lockModalContext] = Modal.useModal();

  // Unlocking asks the same question as the Locking settings page; locking from the header stays one click.
  const confirmLockButton = (locked: boolean) => {
    if (!locked) {
      handleLockButton(false);
      return;
    }
    lockModal.confirm({
      title: `Unlock ${workspaceName}?`,
      content: UNLOCK_CONFIRM_TEXT,
      okText: "Unlock workspace",
      cancelText: "Cancel",
      onOk: () => handleLockButton(true),
    });
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
        loadWorkspace(true);
        const newstatus = locked ? "unlocked" : "locked";
        message.success("Workspace " + newstatus + " successfully");
      })
      .catch((error) => {
        const newstatus = locked ? "unlock" : "lock";
        message.error("Workspace " + newstatus + " failed: " + error.response.data.errors[0].detail);
      });
  };

  const latestJob = jobs.length > 0 ? [...jobs].sort((a: any, b: any) => b.id - a.id)[0] : undefined;
  const metrics = computeWorkspaceMetrics(jobs);

  const renderSection = (workspace: Workspace) => {
    switch (activeKey) {
      case "1":
        return (
          <Row>
            <Col span={18} className="workspace-overview-main">
              {workspace.attributes.source === "empty" &&
              workspace.attributes.branch === "remote-content" &&
              (workspace.relationships?.history?.data?.length || 0) < 1 ? (
                <CLIDriven organizationName={organizationNameLocal} workspaceName={workspaceName} />
              ) : (
                <div>
                  <div className="latest-run-header">
                    <Typography.Title level={3}>Latest run</Typography.Title>
                    {jobs.length > 0 && (
                      <Link to={`/organizations/${organizationId}/workspaces/${id}/runs`}>
                        View all runs <ArrowRightOutlined aria-hidden />
                      </Link>
                    )}
                  </div>
                  {latestJob ? (
                    <article className="latest-run" aria-label="Latest run">
                      <div className="latest-run-title">
                        <Typography.Title level={4}>
                          <Link to={runLink(latestJob.id)} onClick={() => changeJob(latestJob.id)}>
                            {latestJob.title}
                          </Link>
                        </Typography.Title>
                        <WorkspaceStatusTag status={latestJob.status} />
                      </div>
                      <p className="latest-run-byline">
                        <Avatar size={20} shape="square" icon={<UserOutlined />} />
                        <span>
                          <strong>{latestJob.createdBy}</strong> triggered a run {latestJob.latestChange} via{" "}
                          <strong>{latestJob.via || "UI"}</strong>
                          {latestJob.commitId && latestJob.commitId !== "000000000" && (
                            <>
                              {" · "}
                              <FiGitCommit aria-label="commit" /> <code>{latestJob.commitId.substring(0, 7)}</code>
                            </>
                          )}{" "}
                          <ApprovalAttribution approvedBy={latestJob.approvedBy} approvedAt={latestJob.approvedAt} />
                        </span>
                      </p>
                      <dl className="latest-run-facts">
                        <div>
                          <dt>Template</dt>
                          <dd>
                            {(templates as Template[]).find((t) => t.id === latestJob.templateReference)?.attributes
                              ?.name ?? "—"}
                          </dd>
                        </div>
                        <div>
                          <dt>Policy checks</dt>
                          <dd>
                            <PolicyStatusTag
                              status={workspace.attributes?.policyComplianceStatus}
                              organizationId={organizationId}
                              workspaceId={id}
                              clickable
                            />
                          </dd>
                        </div>
                        <div>
                          <dt>Duration</dt>
                          <dd>
                            {FINISHED_JOB_STATUSES.includes(latestJob.status)
                              ? (formatDuration(latestJob.createdDate, latestJob.updatedDate) ?? "—")
                              : "In progress"}
                          </dd>
                        </div>
                        <div>
                          <dt>Run</dt>
                          <dd>
                            <code>#{latestJob.id}</code>
                          </dd>
                        </div>
                      </dl>
                      <div className="latest-run-actions">
                        <LinkButton to={runLink(latestJob.id)} onClick={() => changeJob(latestJob.id)}>
                          See details
                        </LinkButton>
                      </div>
                    </article>
                  ) : (
                    <div className="latest-run latest-run--empty">
                      <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description={
                          manageWorkspace
                            ? "No runs yet. Use Run now above to trigger your first plan."
                            : "No runs yet."
                        }
                      />
                    </div>
                  )}
                  <Tabs
                    type="card"
                    className="workspace-overview-tabs"
                    items={[
                      {
                        label: `Resources (${resources.length})`,
                        key: "1",
                        // Fills the column; scrolls only when it is narrower than 560px.
                        children: (
                          <Table size="middle" dataSource={resources} columns={resourceColumns} scroll={{ x: 560 }} />
                        ),
                      },
                      {
                        label: `Outputs (${outputs.length})`,
                        key: "2",
                        children: (
                          <Table
                            size="middle"
                            dataSource={outputs}
                            columns={outputColumns}
                            scroll={{ x: "max-content" }}
                          />
                        ),
                      },
                    ]}
                  />

                  <ResourceDrawer resource={resource} workspace={workspace} setOpen={setOpen} open={open} />
                </div>
              )}
            </Col>
            <Col span={6}>
              <aside className="workspace-rail" aria-label="Workspace details">
                <ul className="workspace-rail-facts">
                  <li>
                    {workspace.attributes.branch !== "remote-content" &&
                    isValidUrl(fixSshURL(workspace.attributes.source)) ? (
                      <>
                        <VcsLogo type={vcsProvider} />
                        <a href={fixSshURL(workspace.attributes.source)} target="_blank" rel="noreferrer">
                          {new URL(fixSshURL(workspace.attributes.source))?.pathname?.replace(".git", "")?.substring(1)}
                        </a>
                      </>
                    ) : (
                      <>
                        <BiTerminal aria-hidden />
                        <span>CLI/API-driven workflow</span>
                      </>
                    )}
                  </li>
                  {workspace.attributes.branch && workspace.attributes.branch !== "remote-content" && (
                    <li>
                      <BranchesOutlined aria-hidden />
                      <span className="workspace-rail-label">Branch:</span>
                      <code>{workspace.attributes.branch}</code>
                    </li>
                  )}
                  <li>
                    <ThunderboltOutlined aria-hidden />
                    <span className="workspace-rail-label">Execution mode:</span>
                    <Link to={`/organizations/${organizationId}/workspaces/${id}/settings/general`}>
                      {executionMode.charAt(0).toUpperCase() + executionMode.slice(1)}
                    </Link>
                  </li>
                  {workspace.attributes.folder && (
                    <li>
                      <FolderOutlined aria-hidden />
                      <span className="workspace-rail-label">Working directory:</span>
                      <code>{workspace.attributes.folder}</code>
                    </li>
                  )}
                  <li>
                    <ProjectOutlined aria-hidden />
                    <span className="workspace-rail-label">Project:</span>
                    {projectName && projectId ? (
                      <Link to={`/organizations/${organizationId}/projects/${projectId}`}>{projectName}</Link>
                    ) : (
                      <span>None</span>
                    )}
                  </li>
                </ul>
                {metrics && (
                  <section className="workspace-rail-section" aria-labelledby="workspace-rail-metrics">
                    <Typography.Title level={4} id="workspace-rail-metrics">
                      Metrics{" "}
                      <span className="workspace-rail-heading-note">
                        (last {metrics.runCount === 1 ? "run" : `${metrics.runCount} runs`})
                      </span>
                    </Typography.Title>
                    <dl className="workspace-rail-metrics">
                      <div>
                        <dt>Average run duration</dt>
                        <dd>{metrics.averageDuration ?? "—"}</dd>
                      </div>
                      <div>
                        <dt>Failed runs</dt>
                        <dd>{metrics.failedRuns}</dd>
                      </div>
                      <div>
                        <dt>Last success</dt>
                        <dd>
                          {metrics.lastSuccessfulRun ? (
                            <time
                              dateTime={metrics.lastSuccessfulRun}
                              title={formatDateTime(metrics.lastSuccessfulRun)}
                            >
                              {relativeTime(metrics.lastSuccessfulRun)}
                            </time>
                          ) : (
                            "None"
                          )}
                        </dd>
                      </div>
                    </dl>
                  </section>
                )}
                <section className="workspace-rail-section" aria-labelledby="workspace-rail-tags">
                  <Typography.Title level={4} id="workspace-rail-tags">
                    Tags
                  </Typography.Title>
                  <Tags organizationId={organizationId} workspaceId={id!} manageWorkspace={manageWorkspace} />
                </section>
              </aside>
            </Col>
          </Row>
        );
      case "2":
        return jobVisible ? (
          <Suspense fallback={<LoadingFallback />}>
            <DetailsJob jobId={jobId!} workspaceName={workspaceName} canApprove={approveJob} />
          </Suspense>
        ) : (
          <RunList jobs={jobs} onRunClick={changeJob} runLink={runLink} />
        );
      case "3":
        return (
          <Suspense fallback={<LoadingFallback />}>
            <States
              history={history}
              setStateDetailsVisible={setStateDetailsVisible}
              stateDetailsVisible={stateDetailsVisible}
              workspace={workspace}
              onRollback={loadWorkspace}
              manageState={manageState}
            />
          </Suspense>
        );
      case "4":
        return (
          <Variables
            vars={variables}
            env={envVariables}
            manageWorkspace={manageWorkspace}
            collectionVars={collectionVariables}
            collectionEnvVars={collectionEnvVariables}
            globalVariables={globalVariables}
            globalEnvVariables={globalEnvVariables}
            reload={() => loadWorkspace(false)}
          />
        );
      case "5":
        return templates ? (
          <Schedules schedules={schedule} manageWorkspace={manageWorkspace} reload={() => loadWorkspace(false)} />
        ) : (
          <p>Loading...</p>
        );
      case "7":
        return (
          <RunTriggers
            organizationId={organizationId!}
            workspaceId={id!}
            workspaceName={workspaceName}
            manageWorkspace={manageWorkspace}
          />
        );
      case "6":
        return (
          <Suspense fallback={<LoadingFallback />}>
            <WorkspaceSettings
              workspace={workspace}
              vcsProvider={vcsProvider}
              orgTemplates={orgTemplates}
              manageWorkspace={manageWorkspace}
              planJob={planJob}
              onWorkspaceUpdate={() => loadWorkspace(false)}
              activeSection={settingsSection || "general"}
            />
          </Suspense>
        );
      default:
        return null;
    }
  };

  const pageBreadcrumbs = [
    { label: organizationNameLocal ?? "", path: `/organizations/${organizationId}/workspaces` },
    { label: "Workspaces", path: `/organizations/${organizationId}/workspaces` },
    { label: workspaceName, path: `/organizations/${organizationId}/workspaces/${id}` },
    ...(activeKey === "6" && settingsSection
      ? [
          { label: "Settings", path: `/organizations/${organizationId}/workspaces/${id}/settings/general` },
          { label: WORKSPACE_SETTINGS_SECTION_LABELS[settingsSection] ?? "General" },
        ]
      : [{ label: WORKSPACE_SECTION_LABELS[activeKey ?? "1"] ?? "Overview" }]),
  ];

  const isSettings = activeKey === "6";
  // Settings pages drop the header entirely; General settings shows the ID as a field.
  const showWorkspaceId = !isSettings;

  const pageActions =
    !loading && workspace && !isSettings ? (
      <Space orientation="horizontal">
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
              <Suspense key={index} fallback={<LoadingFallback />}>
                <ActionLoader
                  action={action?.attributes.action}
                  context={{
                    workspace: workspace,
                    state: contextState,
                    resources: resources,
                    apiUrl: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin,
                    settings: action.settings,
                  }}
                />
              </Suspense>
            ))}
        <Button
          type="default"
          htmlType="button"
          onClick={() => confirmLockButton(workspace.attributes.locked)}
          icon={workspace.attributes.locked ? <UnlockOutlined /> : <LockOutlined />}
          disabled={!manageWorkspace}
        >
          {workspace.attributes.locked ? "Unlock" : "Lock"}
        </Button>
        <CreateJob
          changeJob={changeJob}
          planJob={planJob}
          resources={resources}
          disabledReason={
            workspace.attributes.source === "empty" && workspace.attributes.branch === "remote-content"
              ? "This CLI/API driven workspace has no applied configuration yet. Upload and apply a configuration with the terraform CLI/API before using Run now."
              : undefined
          }
        />
      </Space>
    ) : undefined;

  return (
    <PageWrapper
      title={workspaceName}
      showTitle={!isSettings}
      breadcrumbs={pageBreadcrumbs}
      error={
        loadError
          ? { title: loadError.includes("permission") ? "Access Denied" : "Error", message: loadError }
          : undefined
      }
      loading={!loadError && (loading || !workspace || !variables || !jobs)}
      loadingText="Loading Workspace..."
      actions={pageActions}
    >
      {lockModalContext}
      {workspace && (
        <div className="orgWrapper">
          <Space className="workspace-details" orientation="vertical">
            {showWorkspaceId && (
              <Paragraph className="workspace-id" copyable={{ text: id, tooltips: false }}>
                <Typography.Text type="secondary"> ID: {id} </Typography.Text>
              </Paragraph>
            )}
            {isSettings ? null : workspace.attributes?.description === "" ? (
              <a className="workspace-button" onClick={handleClickSettings}>
                Add workspace description
              </a>
            ) : (
              <Typography.Text type="secondary">{workspace.attributes.description}</Typography.Text>
            )}
            {!isSettings && (
              <div className="workspace-status-strip">
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
                <PolicyStatusTag
                  status={workspace.attributes?.policyComplianceStatus}
                  organizationId={organizationId}
                  workspaceId={id}
                  clickable
                />
                <Typography.Text>
                  <ProfileOutlined /> Resources <span className="workspace-status-value">{resources.length}</span>
                </Typography.Text>
                <span className="workspace-status-iac">
                  {getIaCIconById(workspace.attributes?.iacType)}
                  <Typography.Text>
                    {getIaCNameById(workspace.attributes?.iacType)}{" "}
                    <a onClick={handleClickSettings} className="workspace-button">
                      {workspace.attributes.terraformVersion}
                    </a>
                  </Typography.Text>
                </span>
                <Typography.Text>
                  <ClockCircleOutlined /> Updated{" "}
                  <span className="workspace-status-value">{relativeTime(lastRun) ?? "never executed"}</span>
                </Typography.Text>
                {workspace.attributes.locked && (
                  <Alert
                    className="workspace-status-lock"
                    title="Lock Description"
                    description={workspace.attributes.lockDescription}
                    type="warning"
                    showIcon
                  />
                )}
              </div>
            )}
          </Space>

          {renderSection(workspace)}
        </div>
      )}
    </PageWrapper>
  );
};
