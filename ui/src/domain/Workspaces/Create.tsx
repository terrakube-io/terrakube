import { DownOutlined, GithubOutlined, GitlabOutlined, LockOutlined } from "@ant-design/icons";
import {
  Alert,
  AutoComplete,
  Breadcrumb,
  Button,
  Card,
  Dropdown,
  Flex,
  Form,
  Input,
  Layout,
  List,
  Segmented,
  Select,
  Space,
  Steps,
  Tag,
  message,
  theme,
  Typography,
} from "antd";
import { useEffect, useRef, useState } from "react";
import { IconContext } from "react-icons";
import { BiBookBookmark, BiTerminal, BiUpload } from "react-icons/bi";
import { SiBitbucket, SiGit } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { v7 as uuid } from "uuid";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import {
  ProjectModel,
  SshKey,
  Template,
  TofuRelease,
  VcsConnectionType,
  VcsModel,
  VcsRepositoryGroup,
  VcsRepositoryPage,
  VcsRepositorySummary,
  VcsType,
  VcsTypeExtended,
} from "../types";
import { compareVersions, validateTerraformVersion } from "./Workspaces";
import projectService from "@/modules/projects/projectService";
import { withBasePath } from "../../config/basePath";
const { Content } = Layout;

const validateMessages = {
  required: "${label} is required!",
  types: {
    url: "${label} is not a valid git url",
  },
};
const { Option } = Select;

type IacType = {
  id: string;
  name: string;
  description?: string;
  icon?: string;
  color?: string;
};

type CreateWorkspaceForm = {
  source: string;
  folder: string;
  name: string;
  terraformVersion: string;
  branch: string;
  iacType: string;
  defaultTemplate: string;
  sshKey?: string;
  project?: string;
};

export const CreateWorkspace = () => {
  const { token } = theme.useToken();
  const { colorBgContainer } = token;
  const [organizationName, setOrganizationName] = useState<string | null>();
  const [executionMode, setExecutionMode] = useState<string | null>();
  const [terraformVersions, setTerraformVersions] = useState<string[]>([]);
  const [vcs, setVCS] = useState<VcsModel[]>([]);
  const [sshKeys, setSSHKeys] = useState<SshKey[]>([]);
  const [orgTemplates, setOrgTemplates] = useState<Template[]>([]);
  const [loading, setLoading] = useState(false);
  const [vcsButtonsVisible, setVCSButtonsVisible] = useState(true);
  const [vcsId, setVcsId] = useState("");
  const [current, setCurrent] = useState(0);
  const [step3Hidden, setStep4Hidden] = useState(true);
  const [step2Hidden, setStep3Hidden] = useState(true);
  const [sshKeysVisible, setSSHKeysVisible] = useState(false);
  const [versionControlFlow, setVersionControlFlow] = useState(true);
  const [requiredVcsPush, setRequiredVcsPush] = useState(true);
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [repoPickerMode, setRepoPickerMode] = useState<"list" | "manual">("list");
  const [discoveryUnsupported, setDiscoveryUnsupported] = useState(false);
  const [vcsGroups, setVcsGroups] = useState<VcsRepositoryGroup[]>([]);
  const [selectedGroup, setSelectedGroup] = useState<string>("");
  const [groupsLoading, setGroupsLoading] = useState(false);
  const [repoResults, setRepoResults] = useState<VcsRepositorySummary[]>([]);
  const [repoSearch, setRepoSearch] = useState("");
  const [repoPage, setRepoPage] = useState(1);
  const [repoHasMore, setRepoHasMore] = useState(false);
  const [repoLoading, setRepoLoading] = useState(false);
  const [selectedRepoUrl, setSelectedRepoUrl] = useState("");
  const [repoError, setRepoError] = useState<string | null>(null);
  const repoSearchDebounce = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [iacTypes, setIacTypes] = useState<IacType[]>([]);
  const [iacType, setIacType] = useState<IacType>({
    id: "terraform",
    name: "Terraform",
  });
  const [projectList, setProjectList] = useState<ProjectModel[]>([]);
  const gitlabItems = [
    {
      label: "GitLab.com",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITLAB);
      },
    },
    {
      label: "GitLab Community Edition",
      key: "2",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITLAB_COMMUNITY);
      },
    },
    {
      label: "GitLab Enterprise Edition",
      key: "3",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITLAB_ENTERPRISE);
      },
    },
  ];

  const githubItems = [
    {
      label: "GitHub.com (GitHub App)",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITHUB_APP, VcsConnectionType.STANDALONE);
      },
    },
    {
      label: "GitHub.com (oAuth App)",
      key: "2",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITHUB);
      },
    },
    {
      label: "GitHub Enterprise (GitHub App)",
      key: "3",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITHUB_ENTERPRISE, VcsConnectionType.STANDALONE);
      },
    },
    {
      label: "GitHub Enterprise (oAuth App)",
      key: "4",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITHUB_ENTERPRISE);
      },
    },
  ];

  const bitBucketItems = [
    {
      label: "Bitbucket Cloud",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.BITBUCKET);
      },
    },
  ];

  const azDevOpsItems = [
    {
      label: "Azure DevOps Services",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.AZURE_DEVOPS);
      },
    },
  ];
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const preselectedProjectId = searchParams.get("projectId");

  useEffect(() => {
    setOrganizationName(sessionStorage.getItem(ORGANIZATION_NAME));
    setLoading(true);
    getIacTypes();

    const versionsApi = `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/${iacType.id}/index.json`;

    // Parallel load: versions, SSH keys, templates, VCS, and projects
    Promise.all([
      axiosInstance.get(versionsApi),
      axiosInstance.get(`organization/${organizationId}/ssh`),
      axiosInstance.get(`organization/${organizationId}/template`),
      axiosInstance.get(`organization/${organizationId}/vcs`),
      projectService.listProjects(organizationId!),
    ]).then(([versionsRes, sshRes, templatesRes, vcsRes, projectsRes]) => {
      // Process versions
      const tfVersions: string[] = [];
      if (iacType.id === "tofu") {
        (versionsRes.data as TofuRelease[]).forEach((release) => {
          if (!release.tag_name.includes("-")) tfVersions.push(release.tag_name.replace("v", ""));
        });
      } else {
        for (const version in versionsRes.data.versions) {
          if (!version.includes("-")) tfVersions.push(version);
        }
      }
      tfVersions.sort(compareVersions).reverse();
      setTerraformVersions(tfVersions);
      if (tfVersions.length > 0) {
        form.setFieldsValue({ terraformVersion: tfVersions[0] });
      }

      // Set SSH keys
      setSSHKeys(sshRes.data.data);

      // Set templates
      setOrgTemplates(templatesRes.data.data);
      if (templatesRes.data.data.length > 0) {
        form.setFieldsValue({ defaultTemplate: templatesRes.data.data[0].id });
      }

      // Set VCS
      setVCS(vcsRes.data.data);

      // Set projects
      if (!projectsRes.isError) {
        setProjectList(projectsRes.data);
        if (preselectedProjectId) {
          form.setFieldsValue({ project: preselectedProjectId });
        }
      }

      setLoading(false);
    });
  }, []);
  const handleClick = () => {
    setCurrent(2);
    setVersionControlFlow(true);
    form.setFieldsValue({ source: "", branch: "" });
  };

  const handleIacTypeClick = (iacType: IacType) => {
    setCurrent(1);
    setIacType(iacType);
    loadVersions(iacType);
  };

  const handleGitClick = (id: string) => {
    if (id === "git") {
      setSSHKeysVisible(true);
      setRepoPickerMode("manual");
    } else {
      setSSHKeysVisible(false);
      setVcsId(id);
      setRepoPickerMode("list");
      setDiscoveryUnsupported(false);
      setVcsGroups([]);
      setRepoResults([]);
      setRepoSearch("");
      setSelectedRepoUrl("");
      fetchVcsGroups(id);
    }
    setCurrent(3);
    setRequiredVcsPush(true);
    setStep3Hidden(false);
  };

  const vcsApiUrl = (path: string) => `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}${path}`;

  const fetchVcsGroups = (id: string) => {
    setGroupsLoading(true);
    setRepoError(null);
    axiosInstance
      .get(vcsApiUrl(`/vcs/v1/${id}/groups`))
      .then((response) => {
        const groups: VcsRepositoryGroup[] = response.data || [];
        setVcsGroups(groups);
        setGroupsLoading(false);
        if (groups.length > 0) {
          setSelectedGroup(groups[0].id);
          fetchRepositories(id, groups[0].id, "", 1, false);
        } else {
          message.warning(
            "No organizations were found for this VCS connection. Enter the repository URL manually instead."
          );
          setRepoPickerMode("manual");
        }
      })
      .catch(() => {
        setGroupsLoading(false);
        setDiscoveryUnsupported(true);
        setRepoPickerMode("manual");
        message.warning(
          "We couldn't automatically list repositories for this connection. Enter the repository URL manually instead."
        );
      });
  };

  const fetchRepositories = (id: string, group: string, search: string, page: number, append: boolean) => {
    setRepoLoading(true);
    if (!append) {
      setRepoError(null);
    }
    axiosInstance
      .get(vcsApiUrl(`/vcs/v1/${id}/repositories`), { params: { group, search, page } })
      .then((response) => {
        const data: VcsRepositoryPage = response.data;
        setRepoResults((prev) => (append ? [...prev, ...data.items] : data.items));
        setRepoHasMore(data.hasMore);
        setRepoPage(data.page);
        setRepoLoading(false);
        setRepoError(null);
      })
      .catch(() => {
        setRepoLoading(false);
        setRepoError("Something went wrong while loading repositories from the VCS provider.");
      });
  };

  const handleGroupChange = (group: string) => {
    setSelectedGroup(group);
    fetchRepositories(vcsId, group, repoSearch, 1, false);
  };

  const handleRepoSearchChange = (value: string) => {
    setRepoSearch(value);
    if (repoSearchDebounce.current) {
      clearTimeout(repoSearchDebounce.current);
    }
    repoSearchDebounce.current = setTimeout(() => {
      fetchRepositories(vcsId, selectedGroup, value, 1, false);
    }, 400);
  };

  const handleLoadMoreRepos = () => {
    fetchRepositories(vcsId, selectedGroup, repoSearch, repoPage + 1, true);
  };

  const handleRepoSelect = (repo: VcsRepositorySummary) => {
    setSelectedRepoUrl(repo.url);
    form.setFieldsValue({ source: repo.url });
  };

  const handleVCSClick = (vcsType: VcsTypeExtended, connectionType?: VcsConnectionType) => {
    const query = connectionType ? `?connectionType=${connectionType}` : "";
    navigate(`/organizations/${organizationId}/settings/vcs/new/${vcsType}${query}`);
  };

  const handleConnectDifferent = () => {
    setVCSButtonsVisible(false);
  };

  const handleConnectExisting = () => {
    setVCSButtonsVisible(true);
  };

  const renderVCSLogo = (vcs: VcsType) => {
    switch (vcs) {
      case "GITLAB":
        return <GitlabOutlined style={{ fontSize: "20px" }} />;
      case "BITBUCKET":
        return (
          <IconContext.Provider value={{ size: "20px" }}>
            <SiBitbucket />
            &nbsp;&nbsp;
          </IconContext.Provider>
        );
      case "AZURE_DEVOPS":
        return (
          <IconContext.Provider value={{ size: "20px" }}>
            <VscAzureDevops />
            &nbsp;
          </IconContext.Provider>
        );
      case "AZURE_SP_MI":
        return (
          <IconContext.Provider value={{ size: "20px" }}>
            <VscAzureDevops />
            &nbsp;
          </IconContext.Provider>
        );
      default:
        return <GithubOutlined style={{ fontSize: "20px" }} />;
    }
  };

  const [form] = Form.useForm();
  const handleGitContinueClick = () => {
    setCurrent(4);
    setStep4Hidden(false);
    setStep3Hidden(true);
    const source = form.getFieldValue("source");

    if (source != null) {
      const nameValue = source.match("/([^/]+)/?$");
      if (nameValue != null && nameValue.length > 0) {
        form.setFieldsValue({ name: nameValue[1].replace(".git", "") });
      }
    }
  };

  const handleCliDriven = () => {
    setVersionControlFlow(false);
    setCurrent(2);
    setStep4Hidden(false);
    setSSHKeysVisible(false);
    setRequiredVcsPush(false);
    setExecutionMode("local");
    form.setFieldsValue({ source: "empty", branch: "remote-content" });
  };

  const onFinishFailed = (errorInfo: any) => {
    console.log(errorInfo.values);
    console.log(errorInfo.errorFields);
  };

  const loadVersions = (iacType: IacType) => {
    const versionsApi = `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/${iacType.id}/index.json`;
    axiosInstance.get(versionsApi).then((resp) => {
      const tfVersions = [];
      if (iacType.id === "tofu") {
        (resp.data as TofuRelease[]).forEach((release) => {
          if (!release.tag_name.includes("-")) tfVersions.push(release.tag_name.replace("v", ""));
        });
      } else {
        for (const version in resp.data.versions) {
          if (!version.includes("-")) tfVersions.push(version);
        }
      }
      tfVersions.sort(compareVersions).reverse();
      setTerraformVersions(tfVersions);
      if (tfVersions.length > 0) {
        form.setFieldsValue({ terraformVersion: tfVersions[0] });
      }
    });
  };

  const onFinish = async (values: CreateWorkspaceForm) => {
    const workspace_lid = uuid();
    const body = {
      "atomic:operations": [
        {
          op: "add",
          href: `/organization/${organizationId}/workspace`,
          data: {
            type: "workspace",
            lid: workspace_lid,
            attributes: {
              source: values.source,
              folder: values.folder,
              name: values.name,
              terraformVersion: values.terraformVersion,
              branch: values.branch,
              iacType: iacType.id,
              defaultTemplate: values.defaultTemplate,
              executionMode: executionMode,
            },
            relationships: {},
          },
        },
      ],
    };

    if (vcsId !== "") {
      (body["atomic:operations"][0].data.relationships as any)["vcs"] = {
        data: {
          type: "vcs",
          id: vcsId,
        },
      };
    }

    if (values.sshKey) {
      (body["atomic:operations"][0].data.relationships as any)["ssh"] = {
        data: {
          type: "ssh",
          id: values.sshKey,
        },
      };
    }

    if (values.project && values.project !== "none") {
      (body["atomic:operations"][0].data.relationships as any)["project"] = {
        data: {
          type: "project",
          id: values.project,
        },
      };
    }

    try {
      const response = await axiosInstance.post(`/operations`, body, {
        headers: {
          "Content-Type": 'application/vnd.api+json;ext="https://jsonapi.org/ext/atomic"',
          Accept: 'application/vnd.api+json;ext="https://jsonapi.org/ext/atomic"',
        },
      });
      if (response.status === 200) {
        const workspaceId = response.data["atomic:results"][0].data.id;
        navigate(`/organizations/${organizationId}/workspaces/${workspaceId}`);
      }
    } catch (error: any) {
      if (error.response) {
        if (error.response.status === 403) {
          message.error(
            <span>
              You are not authorized to create workspaces. <br /> Please contact your administrator and request the{" "}
              <b>Manage Workspaces</b> permission. <br /> For more information, visit the{" "}
              <a
                target="_blank"
                href="https://docs.terrakube.io/user-guide/organizations/team-management"
                rel="noreferrer"
              >
                Terrakube documentation
              </a>
              .
            </span>
          );
        } else {
          message.error(
            <span>An error occurred while submitting the workspace. Please contact your system administrator.</span>
          );
        }
      }
    }
  };

  const handleChange = (currentVal: number) => {
    // The breadcrumb can only step backward. Going forward again always requires redoing the
    // actual step action (a card click / Continue button), never a direct jump, so stale
    // choices from a step you've backed out of can't be skipped past silently.
    if (currentVal >= current) {
      return;
    }
    setCurrent(currentVal);
    if (currentVal === 3) {
      setStep3Hidden(false);
      setStep4Hidden(true);
    }

    if (currentVal === 4) {
      setStep4Hidden(false);
      setStep3Hidden(true);
    }

    if (currentVal === 2 || currentVal === 1 || currentVal === 0) {
      setStep4Hidden(true);
      setStep3Hidden(true);
      setVersionControlFlow(true);
    }
  };

  const getIacTypes = () => {
    const iacTypes = [
      {
        id: "terraform",
        name: "Terraform",
        description: "Create an empty template. So you can define your template from scratch.",
        icon: withBasePath("/providers/terraform.svg"),
      },
      { id: "tofu", name: "OpenTofu", icon: withBasePath("/providers/opentofu.png") },
    ];

    setIacTypes(iacTypes);
  };

  return (
    <Content style={{ padding: "0 50px" }}>
      <Breadcrumb
        style={{ margin: "16px 0" }}
        items={[
          {
            title: organizationName,
          },
          {
            title: <Link to={`/organizations/${organizationId}/workspaces`}>Workspaces</Link>,
          },
          {
            title: "New Workspace",
          },
        ]}
      />

      <div className="site-layout-content" style={{ background: colorBgContainer }}>
        <div className="createWorkspace">
          <h2>Create a new Workspace</h2>
          <div>
            <Typography.Text type="secondary" className="App-text">
              Workspaces determine how Terrakube organizes infrastructure. A workspace contains your configuration
              (infrastructure as code), shared variable values, your current and historical state, and run logs.
            </Typography.Text>
          </div>
          <div
            style={{
              background: token.colorFillAlter,
              border: `1px solid ${token.colorBorderSecondary}`,
              borderRadius: token.borderRadiusLG,
              padding: "20px 24px",
              margin: "16px 0 24px 0",
            }}
          >
            <Steps
              current={current}
              onChange={handleChange}
              responsive
              items={(versionControlFlow
                ? [
                    { title: "Choose IaC type", description: "Terraform or OpenTofu" },
                    { title: "Choose type", description: "How runs are triggered" },
                    { title: "Connect to VCS", description: "Pick a git provider" },
                    { title: "Choose a repository", description: "Select your source code" },
                    { title: "Configure settings", description: "Name, branch & defaults" },
                  ]
                : [
                    { title: "Choose IaC type", description: "Terraform or OpenTofu" },
                    { title: "Choose type", description: "How runs are triggered" },
                    { title: "Configure settings", description: "Name & defaults" },
                  ]
              ).map((step, index) => ({ ...step, disabled: index >= current }))}
            />
          </div>
          {current == 0 && (
            <Space className="chooseType" direction="vertical">
              <h3>Choose your IaC type </h3>
              <List
                grid={{
                  gutter: 24,
                  xs: 1,
                  sm: Math.min(iacTypes.length || 1, 2),
                  md: Math.min(iacTypes.length || 1, 3),
                  lg: Math.min(iacTypes.length || 1, 3),
                  xl: Math.min(iacTypes.length || 1, 3),
                }}
                dataSource={iacTypes}
                renderItem={(item) => (
                  <List.Item>
                    <Card
                      style={{ textAlign: "center", minHeight: 220 }}
                      styles={{ body: { padding: 32 } }}
                      hoverable
                      onClick={() => handleIacTypeClick(item)}
                    >
                      <Space direction="vertical" align="center" size="middle" style={{ width: "100%" }}>
                        <img
                          style={{
                            padding: "14px",
                            backgroundColor: item.color,
                            width: "96px",
                            maxWidth: "100%",
                            height: "auto",
                          }}
                          alt="example"
                          src={item.icon}
                        />
                        <span style={{ fontWeight: "bold", fontSize: 18, whiteSpace: "nowrap" }}>{item.name}</span>
                      </Space>
                    </Card>
                  </List.Item>
                )}
              />
            </Space>
          )}

          {current === 1 && (
            <Space className="chooseType" direction="vertical">
              <h3>Choose your workflow </h3>
              <Card hoverable onClick={handleClick}>
                <IconContext.Provider value={{ size: "1.3em" }}>
                  <BiBookBookmark />
                </IconContext.Provider>
                <span className="workflowType">Version control workflow</span>
                <div className="workflowDescription App-text">
                  Store your {iacType?.name} configuration in a git repository, and trigger runs based on pull requests
                  and merges.
                </div>
                <div className="workflowSelect"></div>
              </Card>
              <Card hoverable onClick={handleCliDriven}>
                <IconContext.Provider value={{ size: "1.3em" }}>
                  <BiTerminal />
                </IconContext.Provider>
                <span className="workflowType">CLI-driven workflow</span>
                <div className="workflowDescription App-text">
                  Trigger remote {iacType?.name} runs from your local command line.
                </div>
              </Card>
              <Card hoverable onClick={handleCliDriven}>
                <IconContext.Provider value={{ size: "1.3em" }}>
                  <BiUpload />
                </IconContext.Provider>
                <span className="workflowType">API-driven workflow</span>
                <div className="workflowDescription App-text">
                  A more advanced option. Integrate {iacType?.name} into a larger pipeline using the {iacType?.name}{" "}
                  API.
                </div>
              </Card>
            </Space>
          )}

          {current === 2 && versionControlFlow && (
            <Space className="chooseType" direction="vertical">
              <h3>Connect to a version control provider</h3>
              <div className="workflowDescription2 App-text">
                Choose the version control provider that hosts the {iacType?.name}&nbsp; configuration for this
                workspace.
              </div>

              {vcsButtonsVisible ? (
                <div>
                  <Space direction="horizontal">
                    <Button
                      icon={<SiGit />}
                      onClick={() => {
                        handleGitClick("git");
                      }}
                      size="large"
                    >
                      &nbsp;Git
                    </Button>
                    {loading ? (
                      <p>Data loading...</p>
                    ) : (
                      vcs.map(function (item) {
                        return (
                          <Button
                            icon={renderVCSLogo(item.attributes.vcsType)}
                            onClick={() => {
                              handleGitClick(item.id);
                            }}
                            size="large"
                          >
                            &nbsp;{item.attributes.name}
                          </Button>
                        );
                      })
                    )}
                  </Space>{" "}
                  <br />
                  <Button onClick={handleConnectDifferent} className="link" type="link">
                    Connect to a different VCS
                  </Button>
                </div>
              ) : (
                <div>
                  <Space direction="horizontal">
                    <Dropdown menu={{ items: githubItems }}>
                      <Button size="large">
                        <Space>
                          <GithubOutlined /> GitHub <DownOutlined />
                        </Space>
                      </Button>
                    </Dropdown>
                    <Dropdown menu={{ items: gitlabItems }}>
                      <Button size="large">
                        <Space>
                          <GitlabOutlined />
                          GitLab <DownOutlined />
                        </Space>
                      </Button>
                    </Dropdown>
                    <Dropdown menu={{ items: bitBucketItems }}>
                      <Button size="large">
                        <SiBitbucket /> &nbsp; Bitbucket <DownOutlined />
                      </Button>
                    </Dropdown>
                    <Dropdown menu={{ items: azDevOpsItems }}>
                      <Button size="large">
                        <Space>
                          <VscAzureDevops /> Azure Devops <DownOutlined />
                        </Space>
                      </Button>
                    </Dropdown>
                  </Space>
                  <br />
                  <Button onClick={handleConnectExisting} className="link" type="link">
                    Use an existing VCS connection
                  </Button>
                </div>
              )}
            </Space>
          )}

          <Form
            form={form}
            name="create-workspace"
            layout="vertical"
            onFinish={onFinish}
            onFinishFailed={onFinishFailed}
            validateMessages={validateMessages}
            initialValues={{ folder: "/" }}
          >
            <Space hidden={step2Hidden} className="chooseType" direction="vertical" style={{ width: "100%" }}>
              <h3>Choose a repository</h3>
              <div className="workflowDescription2 App-text">
                Choose the repository that hosts your {iacType?.name} source code.
              </div>

              {!discoveryUnsupported && vcsId !== "" && (
                <Segmented
                  value={repoPickerMode}
                  onChange={(value) => setRepoPickerMode(value as "list" | "manual")}
                  options={[
                    { label: "Browse repositories", value: "list" },
                    { label: "Enter URL manually", value: "manual" },
                  ]}
                  style={{ marginBottom: 8 }}
                />
              )}

              {repoPickerMode === "list" && (
                <div style={{ width: "100%", maxWidth: 640 }}>
                  <Card
                    size="small"
                    styles={{ body: { padding: 0 } }}
                    style={{ borderRadius: token.borderRadiusLG, overflow: "hidden" }}
                  >
                    <Flex
                      wrap="wrap"
                      gap="small"
                      justify="space-between"
                      align="center"
                      style={{
                        padding: "12px",
                        borderBottom: `1px solid ${token.colorBorderSecondary}`,
                        backgroundColor: token.colorFillAlter,
                      }}
                    >
                      <Select
                        style={{ flex: "1 1 200px", minWidth: 180 }}
                        loading={groupsLoading}
                        value={selectedGroup || undefined}
                        placeholder="Select organization"
                        onChange={handleGroupChange}
                        options={vcsGroups.map((group) => ({ label: group.name, value: group.id }))}
                      />
                      <Input.Search
                        placeholder="Filter repositories by name"
                        allowClear
                        style={{ flex: "1 1 220px", minWidth: 180 }}
                        value={repoSearch}
                        onChange={(e) => handleRepoSearchChange(e.target.value)}
                      />
                    </Flex>

                    {repoError && (
                      <Alert type="error" showIcon banner message={repoError} style={{ borderRadius: 0 }} />
                    )}

                    <List
                      loading={repoLoading && repoResults.length === 0}
                      style={{ maxHeight: 420, overflowY: "auto" }}
                      dataSource={repoResults}
                      locale={{
                        emptyText: repoSearch ? `No repositories match "${repoSearch}"` : "No repositories found",
                      }}
                      renderItem={(repo: VcsRepositorySummary) => (
                        <List.Item
                          onClick={() => handleRepoSelect(repo)}
                          style={{
                            cursor: "pointer",
                            padding: "10px 12px",
                            backgroundColor:
                              selectedRepoUrl === repo.url ? token.controlItemBgActive : "transparent",
                            borderLeft:
                              selectedRepoUrl === repo.url
                                ? `3px solid ${token.colorPrimary}`
                                : "3px solid transparent",
                          }}
                        >
                          <Flex justify="space-between" align="center" style={{ width: "100%" }}>
                            <Space direction="vertical" size={0}>
                              <span style={{ fontWeight: 500 }}>{repo.name}</span>
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                {repo.fullName}
                              </Typography.Text>
                            </Space>
                            {repo.privateRepo && (
                              <Tag icon={<LockOutlined />} style={{ marginRight: 0 }}>
                                Private
                              </Tag>
                            )}
                          </Flex>
                        </List.Item>
                      )}
                    />

                    {repoHasMore && (
                      <div
                        style={{
                          textAlign: "center",
                          padding: 8,
                          borderTop: `1px solid ${token.colorBorderSecondary}`,
                        }}
                      >
                        <Button onClick={handleLoadMoreRepos} loading={repoLoading} type="link" size="small">
                          Load more repositories
                        </Button>
                      </div>
                    )}
                  </Card>
                </div>
              )}

              <Form.Item
                name="source"
                label="Git repo"
                tooltip="e.g. https://github.com/Terrakube/terraform-sample-repository.git or git@github.com:AzBuilder/terraform-azurerm-webapp-sample.git"
                extra=" Git repo must be a valid git url using either https or ssh protocol."
                hidden={repoPickerMode === "list"}
                rules={[
                  {
                    required: true,
                    pattern: new RegExp(
                      "(empty)|(((git|ssh|http(s)?)|(git@[\\w\\.\\-]+))(:(//)?)([\\w\\.@\\:/\\-~]+)(\\.git)?(/)?)"
                    ),
                  },
                ]}
              >
                <Input />
              </Form.Item>

              <Form.Item>
                <Button onClick={handleGitContinueClick} type="primary">
                  Continue
                </Button>
              </Form.Item>
            </Space>

            <Space hidden={step3Hidden} className="chooseType" direction="vertical">
              <h3>Configure settings</h3>
              <Form.Item
                name="name"
                label="Workspace Name"
                rules={[
                  { required: true },
                  {
                    pattern: /^[A-Za-z0-9_-]+$/,
                    message: "Only dashes, underscores, and alphanumeric characters are permitted.",
                  },
                ]}
                extra="The name of your workspace is unique and used in tools, routing, and UI. Dashes, underscores, and alphanumeric characters are permitted."
              >
                <Input />
              </Form.Item>

              <Form.Item
                name="branch"
                label="VCS branch"
                extra="The branch from which the runs are kicked off, this is used for runs issued from the UI."
                rules={[{ required: true }]}
                hidden={!versionControlFlow}
              >
                <Input placeholder="Branch name" />
              </Form.Item>
              <Form.Item
                name="folder"
                label={iacType?.name + " Working Directory"}
                extra=" Default workspace directory. Use / for the root folder"
                rules={[{ required: true }]}
                hidden={!versionControlFlow}
              >
                <Input placeholder="/" />
              </Form.Item>
              <Form.Item
                name="defaultTemplate"
                label="Default template (VCS Push)"
                tooltip="Template that will be executed by default when doing a git push to the repository."
                rules={[{ required: requiredVcsPush }]}
                hidden={!versionControlFlow}
              >
                <Select placeholder="Select Template" style={{ width: 250 }}>
                  {orgTemplates.map(function (template) {
                    return <Option key={template?.id}>{template?.attributes?.name}</Option>;
                  })}
                </Select>
              </Form.Item>
              <Form.Item
                name="terraformVersion"
                label={iacType?.name + " Version"}
                rules={[{ required: true }, { validator: validateTerraformVersion(terraformVersions) }]}
                extra={
                  "The version of " +
                  iacType?.name +
                  " to use for this workspace. It will not upgrade automatically. Version constraints are also supported (e.g. ~>1.11.0, >=1.5.7 <1.9.0)."
                }
              >
                <AutoComplete
                  placeholder="e.g. 1.11.0 or ~>1.11.0"
                  options={terraformVersions.map((v) => ({ value: v }))}
                  filterOption={(input, option) => (option?.value ?? "").includes(input)}
                  style={{ width: 250 }}
                />
              </Form.Item>
              <Form.Item
                hidden={!sshKeysVisible}
                name="sshKey"
                label="SSH Key"
                tooltip="Select an SSH Key that will be used to clone this repo."
                extra="To use the SSH support in modules the source should be used like git@github.com:AzBuilder/terrakube-docker-compose.git"
                rules={[{ required: false }]}
              >
                <Select placeholder="select SSH Key" style={{ width: 250 }}>
                  {sshKeys.map(function (sshKey) {
                    return <Option key={sshKey?.id}>{sshKey?.attributes?.name}</Option>;
                  })}
                </Select>
              </Form.Item>
              <Form.Item
                name="project"
                label="Project"
                extra="Optional. Assigning a project lets you group and filter workspaces."
              >
                <Select placeholder="(No project)" style={{ width: 250 }}>
                  {!preselectedProjectId && <Option key="none">(No project)</Option>}
                  {projectList.map((p) => (
                    <Option key={p.id}>{p.name}</Option>
                  ))}
                </Select>
              </Form.Item>
              <Form.Item>
                <Button type="primary" htmlType="submit">
                  Create Workspace
                </Button>
              </Form.Item>
            </Space>
          </Form>
        </div>
      </div>
    </Content>
  );
};
