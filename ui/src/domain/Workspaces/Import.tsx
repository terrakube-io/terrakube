import { DownOutlined, GithubOutlined, GitlabOutlined } from "@ant-design/icons";
import {
  Breadcrumb,
  Button,
  Card,
  Dropdown,
  Form,
  Input,
  Layout,
  List,
  Space,
  Spin,
  Steps,
  Table,
  message,
  theme,
} from "antd";
import parse from "html-react-parser";
import { ValidateErrorEntity } from "rc-field-form/lib/interface";
import { useEffect, useState } from "react";
import { IconContext } from "react-icons";
import { BiBookBookmark, BiTerminal, BiUpload } from "react-icons/bi";
import { SiBitbucket } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import { Link, useNavigate } from "react-router-dom";
import { ORGANIZATION_ARCHIVE, ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { VcsModel, VcsType, VcsTypeExtended } from "../types";
const { Content } = Layout;
const { Step } = Steps;
const validateMessages = {
  required: "${label} is required!",
  types: {
    url: "${label} is not a valid git url",
  },
};

type Platform = {
  id: string;
  name: string;
  description?: string;
  icon?: string;
  height?: string;
};

export const ImportWorkspace = () => {
  const {
    token: { colorBgContainer },
  } = theme.useToken();
  const [organizationName, setOrganizationName] = useState<string>();
  const [vcs, setVCS] = useState<VcsModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [vcsButtonsVisible, setVCSButtonsVisible] = useState(true);
  const [vcsId, setVcsId] = useState("");
  const [workspaces, setWorkspaces] = useState([]);
  const [workspacesHidden, setWorkspacesHidden] = useState(true);
  const [workspacesLoading, setWorkspacesLoading] = useState(false);
  const [apiUrlHidden, setApiUrlHidden] = useState(true);
  const [workspacesImport, setWorkspacesImport] = useState<any[]>([]);
  const [stepsHidden, setStepsHidden] = useState(false);
  const [listHidden, setListHidden] = useState(true);
  const columns = [
    {
      title: "Name",
      dataIndex: ["attributes", "name"],
      sorter: {
        compare: (a: any, b: any) => a.attributes.name.localeCompare(b.attributes.name),
        multiple: 1,
      },
    },
    {
      title: "Terraform Version",
      dataIndex: ["attributes", "terraform-version"],
      sorter: {
        compare: (a: any, b: any) => a.attributes["terraform-version"].localeCompare(b.attributes["terraform-version"]),
        multiple: 2,
      },
    },
    {
      title: "Vcs Provider",
      dataIndex: ["attributes", "vcs-repo", "service-provider"],
      sorter: {
        compare: (a: any, b: any) => {
          const vcsRepoA = a.attributes["vcs-repo"];
          const vcsRepoB = b.attributes["vcs-repo"];
          const serviceProviderA = vcsRepoA && vcsRepoA["service-provider"] ? vcsRepoA["service-provider"] : "";
          const serviceProviderB = vcsRepoB && vcsRepoB["service-provider"] ? vcsRepoB["service-provider"] : "";
          return serviceProviderA.localeCompare(serviceProviderB);
        },
        multiple: 2,
      },
    },
    {
      title: "Vcs Identifier",
      dataIndex: ["attributes", "vcs-repo", "identifier"],
    },
  ];
  const [current, setCurrent] = useState(0);
  const [step3Hidden, setStep4Hidden] = useState(true);
  const [versionControlFlow, setVersionControlFlow] = useState(true);
  const organizationId = sessionStorage.getItem(ORGANIZATION_ARCHIVE);
  const [platforms, setPlatforms] = useState<Platform[]>([]);
  const [platform, setPlatform] = useState<Platform>({
    id: "tfcloud",
    name: "Terraform Cloud",
  });
  const gitlabItems = [
    {
      label: "Gitlab.com",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITLAB);
      },
    },
    {
      label: "Gitlab Community Edition",
      key: "2",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITLAB_COMMUNITY);
      },
    },
    {
      label: "Gitlab Enterprise Edition",
      key: "3",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITLAB_ENTERPRISE);
      },
    },
  ];

  const githubItems = [
    {
      label: "Github.com",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITHUB);
      },
    },
    {
      label: "Github Enterprise",
      key: "2",
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
  useEffect(() => {
    setOrganizationName(sessionStorage.getItem(ORGANIZATION_NAME) ?? undefined);
    setLoading(true);
    loadVCS();
    getPlatforms();
  }, []);
  const handleClick = () => {
    setCurrent(2);
    setVersionControlFlow(true);
    form.setFieldsValue({ source: "", branch: "" });
  };

  const handlePlatformClick = (platform: Platform) => {
    setCurrent(1);
    setPlatform(platform);
    if (platform.id === "tfcloud") {
      form.setFieldsValue({ apiUrl: "https://app.terraform.io/api/v2" });
      setApiUrlHidden(true);
    } else {
      setApiUrlHidden(false);
      form.setFieldsValue({ apiUrl: "" });
    }
  };

  const handleGitClick = (id: string) => {
    setVcsId(id);
    handleChange(3);
  };

  const handleVCSClick = (vcsType: VcsTypeExtended) => {
    navigate(`/organizations/${organizationId}/settings/vcs/new/${vcsType}`);
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

  const loadVCS = () => {
    axiosInstance.get(`organization/${organizationId}/vcs`).then((response) => {
      setVCS(response.data.data);
      setLoading(false);
    });
  };

  const [form] = Form.useForm();

  const handleCliDriven = () => {
    setVersionControlFlow(false);
    setCurrent(2);
    setStep4Hidden(false);
    form.setFieldsValue({ source: "empty", branch: "remote-content" });
  };

  const onFinishFailed = (errorInfo: ValidateErrorEntity<any>) => {
    console.log(errorInfo.values);
    console.log(errorInfo.errorFields);
  };

  const handleImportClick = async () => {
    setStepsHidden(true);
    setListHidden(false);
    const workspacesImported = workspacesImport.map((workspace) => ({
      id: workspace.id,
      name: workspace.attributes.name,
      status: "Importing...",
    }));
    setWorkspacesImport(workspacesImported);

    for (const workspace of workspacesImport) {
      var result = await importWorkspace(workspace);

      setWorkspacesImport((prevWorkspaces) =>
        prevWorkspaces.map((w) => (w.id === workspace.id ? { ...w, status: result } : w))
      );
    }
  };

  const importWorkspace = async (workspace: any) => {
    try {
      const response = await axiosInstance.post(
        `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/importer/tfcloud/workspaces`,
        {
          organizationId: organizationId,
          vcsId: vcsId,
          id: workspace.id,
          organization: form.getFieldValue("organization"),
          branch: workspace.attributes["vcs-repo"]?.branch,
          folder: workspace.attributes["vcs-repo"]?.directory,
          name: workspace.attributes.name,
          terraformVersion: workspace.attributes["terraform-version"],
          source: workspace.attributes["vcs-repo"]?.["repository-http-url"],
          executionMode: workspace?.attributes["execution-mode"],
          description: workspace.attributes.description,
        },
        {
          headers: {
            "X-TFC-Token": form.getFieldValue("apiToken"),
            "X-TFC-Url": form.getFieldValue("apiUrl"),
          },
        }
      );

      return response?.data;
    } catch (error) {
      console.error("Error importing workspace:", error);
      return "Failed to import workspace: " + error;
    }
  };

  const rowSelection = {
    onChange: (selectedRowKeys: (string | number | bigint)[], selectedRows: any[]) => {
      setWorkspacesImport(selectedRows);
    },
    getCheckboxProps: (record: any) => ({
      name: record.id,
    }),
  };

  const onFinish = (values: any) => {
    handleChange(4);
    setWorkspacesLoading(true);

    axiosInstance
      .get(
        `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/importer/tfcloud/workspaces?organization=${
          values.organization
        }`,
        {
          headers: {
            "X-TFC-Token": values.apiToken,
            "X-TFC-Url": values.apiUrl,
          },
        }
      )
      .then((response) => {
        setWorkspaces(response.data);
        setWorkspacesLoading(false);
      })
      .catch((error) => {
        console.error("Error fetching workspaces:", error);
        message.error("Error fetching workspaces:" + error);
        setWorkspacesLoading(false);
      });
  };

  const handleChange = (currentVal: number) => {
    setCurrent(currentVal);

    if (currentVal === 3) {
      setStep4Hidden(false);
    } else {
      setStep4Hidden(true);
    }

    if (currentVal === 1 || currentVal === 0) {
      setVersionControlFlow(true);
    }

    if (currentVal === 4) {
      setWorkspacesHidden(false);
    } else {
      setWorkspacesHidden(true);
    }
  };

  const getPlatforms = () => {
    const platforms: Platform[] = [
      {
        id: "tfcloud",
        name: "Terraform Cloud",
        description: "Create an empty template. So you can define your template from scratch.",
        icon: "/platforms/terraform-cloud.svg",
        height: "60px",
      },
      {
        id: "tfenterprise",
        name: "Terraform Enterprise",
        icon: "/platforms/terraform-enterprise.svg",
        height: "50px",
      },
    ];

    setPlatforms(platforms);
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
            title: "Import Workspaces",
          },
        ]}
      />

      <div className="site-layout-content" style={{ background: colorBgContainer }}>
        <div className="importWorkspace">
          <h2>Import Workspaces</h2>
          <div className="App-text">
            Easily transfer workspaces from Terraform Cloud and Terraform Enterprise to Terrakube.
          </div>
          <Content hidden={stepsHidden}>
            <Steps direction="horizontal" size="small" current={current} onChange={handleChange}>
              <Step title="Choose Platform" />
              <Step title="Choose Type" />
              {versionControlFlow ? (
                <>
                  <Step title="Connect to VCS" />
                </>
              ) : (
                ""
              )}
              <Step title="Connect to Platform" />
              <Step title="Import Workspaces" />
            </Steps>
            {current == 0 && (
              <Space className="chooseType" direction="vertical">
                <h3>Select a Platform for Workspace Import </h3>
                <List
                  grid={{ gutter: 1, column: 4 }}
                  dataSource={platforms}
                  renderItem={(item) => (
                    <List.Item>
                      <Card
                        style={{
                          width: "240px",
                          height: "120px",
                          textAlign: "center",
                        }}
                        hoverable
                        onClick={() => handlePlatformClick(item)}
                      >
                        <Space direction="vertical">
                          <img
                            style={{
                              padding: "6px",
                              height: item.height,
                            }}
                            alt="example"
                            src={item.icon}
                          />
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
                    Store your Terraform configuration in a git repository, and trigger runs based on pull requests and
                    merges.
                  </div>
                  <div className="workflowSelect"></div>
                </Card>
                <Card hoverable onClick={handleCliDriven}>
                  <IconContext.Provider value={{ size: "1.3em" }}>
                    <BiTerminal />
                  </IconContext.Provider>
                  <span className="workflowType">CLI-driven workflow</span>
                  <div className="workflowDescription App-text">
                    Trigger remote Terraform runs from your local command line.
                  </div>
                </Card>
                <Card hoverable onClick={handleCliDriven}>
                  <IconContext.Provider value={{ size: "1.3em" }}>
                    <BiUpload />
                  </IconContext.Provider>
                  <span className="workflowType">API-driven workflow</span>
                  <div className="workflowDescription App-text">
                    A more advanced option. Integrate Terraform into a larger pipeline using the Terraform API.
                  </div>
                </Card>
              </Space>
            )}

            {current === 2 && versionControlFlow && (
              <Space className="chooseType" direction="vertical">
                <h3>Connect to a version control provider</h3>
                <div className="workflowDescription2 App-text">
                  Choose the version control provider hosting your Terraform configurations for workspace import. For
                  workspaces across different VCS providers, please run the importer separately for each.
                </div>

                {vcsButtonsVisible ? (
                  <div>
                    <Space direction="horizontal">
                      {loading || vcs.length === 0 ? (
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
                            <GithubOutlined /> Github <DownOutlined />
                          </Space>
                        </Button>
                      </Dropdown>
                      <Dropdown menu={{ items: gitlabItems }}>
                        <Button size="large">
                          <Space>
                            <GitlabOutlined />
                            Gitlab <DownOutlined />
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
              initialValues={{
                apiUrl: platform.id === "tfcloud" ? "https://app.terraform.io/api/v2" : "",
              }}
            >
              <Space hidden={step3Hidden} className="chooseType" direction="vertical">
                <h3>Connect to Platform</h3>
                <div className="workflowDescription2 App-text">
                  Provide the API token to connect with Terraform Cloud API. Terrakube will use this token exclusively
                  for the duration of the migration process and will not store it. For guidance on generating an API
                  token, refer to the{" "}
                  <a href="https://developer.hashicorp.com/terraform/cloud-docs/users-teams-organizations/api-tokens">
                    Terraform Cloud documentation on API tokens
                  </a>
                  .
                </div>
                <Form.Item name="apiUrl" label="API URL" hidden={apiUrlHidden} rules={[{ required: true }]}>
                  <Input placeholder="ex. https://<TERRAFORM ENTERPRISE HOSTNAME>/api/v2" />
                </Form.Item>
                <Form.Item
                  name="organization"
                  label="Organization"
                  extra="Organization name where the workspaces are located."
                  rules={[{ required: true }]}
                >
                  <Input placeholder="ex. My-Organization" />
                </Form.Item>
                <Form.Item
                  name="apiToken"
                  label="Api Token"
                  rules={[{ required: true }]}
                  extra="Ensure that the provided API token has permissions to access the workspaces you intend to import."
                >
                  <Input.Password />
                </Form.Item>
                <Form.Item>
                  <Button type="primary" htmlType="submit">
                    Continue
                  </Button>
                </Form.Item>
              </Space>
            </Form>

            <Space className="chooseType" hidden={workspacesHidden} direction="vertical">
              <h3>Import Workspaces</h3>
              <div className="workflowDescription2 App-text">
                Select one or multiple workspaces that you wish to import. After making your selection, click the
                'Import' button to initiate the import process. The chosen workspaces will be imported into the
                organization specified in the previous step.
              </div>
              <Spin spinning={workspacesLoading} tip="Loading Workspaces...">
                <Table
                  rowSelection={{
                    type: "checkbox",
                    ...rowSelection,
                  }}
                  rowKey={(record: any) => record.id}
                  dataSource={workspaces}
                  columns={columns}
                  pagination={{
                    defaultPageSize: 50,
                    showSizeChanger: true,
                    pageSizeOptions: ["20", "50", "100"],
                  }}
                />
                <br />

                <Button onClick={handleImportClick} type="primary" htmlType="button">
                  Import Workspaces
                </Button>
              </Spin>
            </Space>
          </Content>
          <Space hidden={listHidden} direction="vertical">
            <h3>Importing Workspaces</h3>
            <div className="workflowDescription2 App-text">
              Import of the selected workspaces is underway. You can monitor the progress for each workspace in the
              section below.
            </div>
            <List
              dataSource={workspacesImport}
              renderItem={(item) => (
                <List.Item>
                  {" "}
                  <List.Item.Meta title={item.name} description={<ul>{parse(item.status || "Waiting...")}</ul>} />
                </List.Item>
              )}
            ></List>
          </Space>
        </div>
      </div>
    </Content>
  );
};
