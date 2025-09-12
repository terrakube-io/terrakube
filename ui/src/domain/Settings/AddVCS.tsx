import { DownOutlined, GithubOutlined, GitlabOutlined } from "@ant-design/icons";
import { Button, Dropdown, Form, Input, Space, Steps, Typography, message } from "antd";
import TextArea from "antd/es/input/TextArea";
import { useState } from "react";
import { HiOutlineExternalLink } from "react-icons/hi";
import { SiBitbucket } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import { useParams } from "react-router-dom";
import { v1 as uuidv1 } from "uuid";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { VcsConnectionType, VcsType, VcsTypeExtended } from "../types";
import "./Settings.css";

const { Step } = Steps;
const validateMessages = {
  required: "${label} is required!",
};

type Props = {
  setMode: (mode: string) => void;
  loadVCS: () => void;
};

type Params = {
  orgid: string;
  vcsName: VcsTypeExtended;
};

type CreateVcsForm = {
  name: string;
  description: string;
  connectionType: VcsConnectionType;
  vcsType: VcsType;
  clientId: string;
  clientSecret: string;
  privateKey: string;
  callback: string;
  endpoint: string;
  apiUrl: string;
  redirectUrl: string;
  status: string;
};

export const AddVCS = ({ setMode, loadVCS }: Props) => {
  const { orgid, vcsName } = useParams<Params>();
  const [current, setCurrent] = useState(vcsName ? 1 : 0);
  const [vcsType, setVcsType] = useState<VcsTypeExtended>(vcsName ? vcsName : VcsTypeExtended.GITHUB);
  const [connectionType, setConnectionType] = useState(VcsConnectionType.OAUTH);
  const [uuid] = useState(uuidv1());
  const handleChange = (currentVal: number) => {
    setCurrent(currentVal);
  };
  const handleVCSClick = (vcs: VcsTypeExtended, connectionType: VcsConnectionType = VcsConnectionType.OAUTH) => {
    setCurrent(1);
    setVcsType(vcs);
    setConnectionType(connectionType);
  };

  const getCallBackUrl = () => {
    return `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/callback/v1/vcs/${uuid}`;
  };

  const renderVCSType = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
        return "GitLab";
      case "GITLAB_ENTERPRISE":
        return "GitLab Enterprise";
      case "GITLAB_COMMUNITY":
        return "GitLab Community Edition";
      case "BITBUCKET":
        return "BitBucket";
      case "BITBUCKET_SERVER":
        return "BitBucket Server";
      case "AZURE_DEVOPS":
        return "Azure Devops";
      case "AZURE_DEVOPS_SERVER":
        return "Azure Devops Server";
      case "GITHUB_ENTERPRISE":
        return "GitHub Enterprise";
      default:
        return "GitHub";
    }
  };
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
        handleVCSClick(VcsTypeExtended.GITHUB_ENTERPRISE);
      },
    },
  ];

  const githubItems = [
    {
      label: "Github.com (GitHub App)",
      key: "1",
      onClick: () => {
        handleVCSClick(VcsTypeExtended.GITHUB, VcsConnectionType.STANDALONE);
      },
    },
    {
      label: "Github.com (oAuth App)",
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
  const getDocsUrl = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
        return "https://docs.terrakube.io/user-guide/vcs-providers/gitlab.com";
      case "GITLAB_ENTERPRISE":
      case "GITLAB_COMMUNITY":
        return "https://docs.terrakube.io/user-guide/vcs-providers/gitlab-ee-and-ce";
      case "BITBUCKET":
        return "https://docs.terrakube.io/user-guide/vcs-providers/bitbucket.com";
      case "BITBUCKET_SERVER":
        return "https://docs.terrakube.io/user-guide/vcs-providers/bitbucket-server";
      case "AZURE_DEVOPS":
        return "https://docs.terrakube.io/user-guide/vcs-providers/azure-devops";
      case "AZURE_DEVOPS_SERVER":
        return "https://docs.terrakube.io/user-guide/vcs-providers/azure-devops";
      case "GITHUB_ENTERPRISE":
        return "https://docs.terrakube.io/user-guide/vcs-providers/github-enterprise";
      default:
        return "https://docs.terrakube.io/user-guide/vcs-providers/github.com";
    }
  };

  const getClientIdName = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
      case "GITLAB_ENTERPRISE":
      case "GITLAB_COMMUNITY":
        return "Application ID";
      case "BITBUCKET":
      case "BITBUCKET_SERVER":
        return "Key";
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        return "Managed Identity App ID";
      default:
        return connectionType === "OAUTH" ? "Client ID" : "App ID";
    }
  };

  const getVcsType = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
      case "GITLAB_ENTERPRISE":
      case "GITLAB_COMMUNITY":
        return "GITLAB";
      case "BITBUCKET":
      case "BITBUCKET_SERVER":
        return "BITBUCKET";
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        return "AZURE_SP_MI";
      default:
        return "GITHUB";
    }
  };

  const getAPIUrl = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
        return "https://gitlab.com/api/v4";
      case "BITBUCKET":
        return "https://api.bitbucket.org/2.0";
      case "AZURE_DEVOPS":
        return "https://dev.azure.com";
      case "GITHUB":
        return "https://api.github.com";
      default:
        return "";
    }
  };

  const getAPIUrlPlaceholder = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB_ENTERPRISE":
      case "GITLAB_COMMUNITY":
        return "ex. https://<GITLAB INSTANCE HOSTNAME>/api/v4";
      case "BITBUCKET_SERVER":
        return "ex. https://<BITBUCKET INSTANCE HOSTNAME>/context-path/rest/api/1.0";
      case "AZURE_DEVOPS_SERVER":
        return "ex. https://<AZURE DEVOPS INSTANCE HOSTNAME>";
      case "GITHUB_ENTERPRISE":
        return "ex. https://<GITHUB INSTANCE HOSTNAME>/api/v3";
      default:
        return "";
    }
  };

  const getHttpsPlaceholder = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB_ENTERPRISE":
      case "GITLAB_COMMUNITY":
        return "ex. https://<GITLAB INSTANCE HOSTNAME>";
      case "BITBUCKET_SERVER":
        return "ex. https://<BITBUCKET INSTANCE HOSTNAME>/<CONTEXT PATH>";
      case "AZURE_DEVOPS_SERVER":
        return "ex. https://<AZURE DEVOPS INSTANCE HOSTNAME>";
      case "GITHUB_ENTERPRISE":
        return "ex. https://<GITHUB INSTANCE HOSTNAME>";
      default:
        return "";
    }
  };

  const httpsHidden = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
      case "BITBUCKET":
      case "AZURE_DEVOPS":
      case "GITHUB":
        return true;
      default:
        return false;
    }
  };

  const apiUrlHidden = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
      case "BITBUCKET":
      case "AZURE_DEVOPS":
      case "GITHUB":
        return true;
      default:
        return false;
    }
  };

  const getSecretIdName = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
      case "GITLAB_ENTERPRISE":
      case "GITLAB_COMMUNITY":
        return "Secret";
      case "BITBUCKET":
      case "BITBUCKET_SERVER":
        return "Secret";
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        return "Client Secret";
      default:
        return connectionType === "OAUTH" ? "Client Secret" : "Private Key in PKCS#8 format";
    }
  };

  const getScopes = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
        return "api";
      case "GITLAB_ENTERPRISE":
      case "GITLAB_COMMUNITY":
        return "api";
      case "BITBUCKET":
        return "repository";
      case "BITBUCKET_SERVER":
        return "repository";
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        return "vso.code+vso.code_status";
      default:
        return "repo";
    }
  };

  const renderStep1 = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
      case "GITLAB_ENTERPRISE":
        return (
          <div>
            <Typography.Text type="secondary" className="paragraph">
              1. On {renderVCSType(vcsType)},{" "}
              {vcsType === "GITLAB" ? (
                <>
                  <Button className="link" target="_blank" href="https://gitlab.com/-/profile/applications" type="link">
                    register a new OAuth Application&nbsp; <HiOutlineExternalLink />
                  </Button>
                  . Enter the following information:
                </>
              ) : (
                <span>
                  navigate to User Settings → Application and register a new OAuth Application with the following
                  information.
                </span>
              )}
            </Typography.Text>
            <div className="paragraph">
              <Typography.Text type="secondary" className="paragraph">
                <p></p>
                <ul className="disc-list">
                  <li>
                    <b>Name:</b>{" "}
                    <Typography.Paragraph
                      copyable
                      type="secondary"
                      style={{ display: "inline", margin: 0, paddingLeft: "5px" }}
                    >
                      Terrakube ({sessionStorage.getItem(ORGANIZATION_NAME)})
                    </Typography.Paragraph>
                  </li>
                  <li>
                    <b>Redirect URI:</b>{" "}
                    <Typography.Paragraph
                      copyable
                      type="secondary"
                      style={{ display: "inline", margin: 0, paddingLeft: "5px" }}
                    >
                      {getCallBackUrl()}
                    </Typography.Paragraph>
                  </li>
                  <li>
                    <b>Scopes:</b> {getScopes(vcsType)}
                  </li>
                </ul>
              </Typography.Text>
            </div>
          </div>
        );
      case "BITBUCKET":
      case "BITBUCKET_SERVER":
        return (
          <div>
            <Typography.Text type="secondary" className="paragraph">
              1. On {renderVCSType(vcsType)}, logged in as whichever account you want Terrakube to act as, add a new
              OAuth Consumer. You can find the OAuth Consumer settings page under your workspace settings. Enter the
              following information:
            </Typography.Text>
            <div className="paragraph">
              <Typography.Text type="secondary" className="paragraph">
                <p></p>
                <ul className="disc-list">
                  <li>
                    <b>Name:</b>{" "}
                    <Typography.Paragraph
                      copyable
                      type="secondary"
                      style={{ display: "inline", margin: 0, paddingLeft: "5px" }}
                    >
                      Terrakube ({sessionStorage.getItem(ORGANIZATION_NAME)})
                    </Typography.Paragraph>
                  </li>
                  <li>
                    <b>Description:</b> Any description of your choice
                  </li>
                  <li>
                    <b>Callback URL:</b>{" "}
                    <Typography.Paragraph
                      copyable
                      type="secondary"
                      style={{ display: "inline", margin: 0, paddingLeft: "5px" }}
                    >
                      {getCallBackUrl()}
                    </Typography.Paragraph>
                  </li>
                  <li>
                    <b>URL:</b>{" "}
                    <Typography.Paragraph
                      copyable
                      type="secondary"
                      style={{ display: "inline", margin: 0, paddingLeft: "5px" }}
                    >
                      {new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}
                    </Typography.Paragraph>
                  </li>
                  <li>
                    <b>This is a private consumer (checkbox):</b> Checked
                  </li>
                  <li>
                    <b>Permissions (checkboxes):</b> The following should be checked:
                    <br />
                    Account: Write
                    <br />
                    Repositories: Admin
                    <br />
                    Pull requests: Write
                    <br />
                    Webhooks: Read and write
                  </li>
                </ul>
              </Typography.Text>
            </div>
          </div>
        );
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        return (
          <div>
            <Typography.Text type="secondary" className="paragraph">
              1. On {renderVCSType(vcsType)},{" "}
              <Button
                className="link"
                target="_blank"
                href="https://aex.dev.azure.com/me?mkt=es-ES"
                type="link"
              >
                grant accesses to the managed identity &nbsp; <HiOutlineExternalLink />
              </Button>
              . Enter the following information:
            </Typography.Text>
            <div className="paragraph">
              <Typography.Text type="secondary" className="paragraph">
                <p></p>
                <ul className="disc-list">
                  <li>
                    <b>Organization Setup:</b>{" "}
                    <Typography.Paragraph
                      type="secondary"
                      style={{ display: "inline", margin: 0, paddingLeft: "5px" }}
                    >
                      Add the manage identity to the organization and grant "Basic" access level
                    </Typography.Paragraph>
                  </li>
                  <li>
                    <b>Repository setup:</b>{" "}
                    <Typography.Paragraph
                      type="secondary"
                      style={{ display: "inline", margin: 0, paddingLeft: "5px" }}
                    >
                      Add the manage identity to the repository and grant "Contributor" access level
                    </Typography.Paragraph>
                  </li>
                </ul>
              </Typography.Text>
            </div>
          </div>
        );
      default:
        return (
          <div>
            <Typography.Text type="secondary" className="paragraph">
              1. On {renderVCSType(vcsType)},{" "}
              {vcsType === "GITHUB" ? (
                <Button
                  className="link"
                  target="_blank"
                  href={
                    connectionType === "OAUTH"
                      ? "https://github.com/settings/applications/new"
                      : "https://github.com/settings/apps/new"
                  }
                  type="link"
                >
                  register a new {connectionType == "OAUTH" ? "OAuth" : "GitHub"} Application&nbsp;{" "}
                  <HiOutlineExternalLink />
                </Button>
              ) : (
                <span>
                  register a new {connectionType == "OAUTH" ? "OAuth" : "GitHub"} Application using the link https://
                  <i>yourdomain.com</i>/settings/{connectionType == "OAUTH" ? "applications" : "apps"}/new
                </span>
              )}
              with the below information
              {connectionType === "OAUTH" ? (
                <span>:</span>
              ) : (
                <span>
                  , install it to your organization or account, and grant necessary permissions. Please check
                  <Button
                    className="link"
                    target="_blank"
                    href="https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/about-creating-github-apps"
                    type="link"
                  >
                    &nbsp;here to learn more.
                    <HiOutlineExternalLink />
                  </Button>
                </span>
              )}
            </Typography.Text>
            <div className="paragraph">
              <Typography.Text type="secondary" className="paragraph">
                <p></p>
                <ul className="disc-list">
                  <li>
                    <b>Application Name:</b>{" "}
                    <Typography.Paragraph
                      copyable
                      type="secondary"
                      style={{ display: "inline", margin: 0, paddingLeft: "5px" }}
                    >
                      Terrakube ({sessionStorage.getItem(ORGANIZATION_NAME)})
                    </Typography.Paragraph>
                  </li>
                  <li>
                    <b>Homepage URL:</b>{" "}
                    <Typography.Paragraph
                      copyable
                      type="secondary"
                      style={{ display: "inline", margin: 0, paddingLeft: "5px" }}
                    >
                      {new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}
                    </Typography.Paragraph>
                  </li>
                  <li>
                    <b>Authorization callback URL:</b>{" "}
                    <Typography.Paragraph
                      copyable
                      type="secondary"
                      style={{ display: "inline", margin: 0, paddingLeft: "5px" }}
                    >
                      {getCallBackUrl()}
                    </Typography.Paragraph>
                  </li>
                  <li>
                    <b>Webhook:</b> <b>untick</b> Active
                  </li>
                  <li>
                    <b>Repository permissions:</b> Commit statuses: Read and write (Only if webhook to be used on VCS
                    workflow workspaces)
                    <br />
                    Content: Read-only
                    <br />
                    Metadata: Read-only
                    <br />
                    Webhooks: Read and write (Only if webhook to be used on VCS workflow workspaces)
                  </li>
                </ul>
              </Typography.Text>
            </div>
          </div>
        );
    }
  };

  const renderStep2 = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
        return (
          <Typography.Text type="secondary" className="paragraph">
            2. After clicking the "Save application" button, you'll be taken to the new application's page. Enter the
            Application ID and Secret below:
          </Typography.Text>
        );
      case "BITBUCKET":
      case "BITBUCKET_SERVER":
        return (
          <Typography.Text type="secondary" className="paragraph">
            2. After clicking the "Save" button, you'll be taken to the OAuth settings page. Find your new OAuth
            consumer under the "OAuth Consumers" heading, and click its name to reveal its details. Enter the Key and
            Secret below:
          </Typography.Text>
        );
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        return (
          <Typography.Text type="secondary" className="paragraph">
            2. Now Terrakube should be able to access your Azure DevOps organization.
          </Typography.Text>
        );
      default:
        return (
          <Typography.Text type="secondary" className="paragraph">
            2. After clicking the "Register application" button, you'll be taken to the new application's page. Enter
            the Client ID below:
          </Typography.Text>
        );
    }
  };

  const renderStep3 = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
      case "GITLAB_ENTERPRISE":
        return null;
      case "BITBUCKET":
      case "BITBUCKET_SERVER":
        return null;
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        return null;
      default:
        return (
          <div>
            <Typography.Text type="secondary" className="paragraph">
              3. Next, generate a{" "}
              {connectionType === "OAUTH" ? "client secret and" : "private key and convert it to PKCS#8 format then"}{" "}
              enter the value below:
            </Typography.Text>
            <br />
          </div>
        );
    }
  };

  const getConnectUrl = (vcs: VcsTypeExtended, clientId: string, callbackUrl: string, endpoint: string) => {
    switch (vcs) {
      case "GITLAB":
      case "GITLAB_ENTERPRISE":
        if (endpoint != null)
          return `${endpoint}/oauth/authorize?client_id=${clientId}&response_type=code&scope=api&&redirect_uri=${callbackUrl}`;
        else
          return `https://gitlab.com/oauth/authorize?client_id=${clientId}&response_type=code&scope=api&&redirect_uri=${callbackUrl}`;
      case "BITBUCKET":
      case "BITBUCKET_SERVER":
        if (endpoint != null)
          return `${endpoint}/site/oauth2/authorize?client_id=${clientId}&response_type=code&response_type=code&scope=repository`;
        else
          return `https://bitbucket.org/site/oauth2/authorize?client_id=${clientId}&response_type=code&response_type=code&scope=repository`;
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        if (endpoint != null)
          return `${endpoint}/oauth2/authorize?client_id=${clientId}&redirect_uri=${callbackUrl}&response_type=Assertion&scope=vso.code+vso.code_status`;
        else
          return `https://app.vssps.visualstudio.com/oauth2/authorize?client_id=${clientId}&redirect_uri=${callbackUrl}&response_type=Assertion&scope=vso.code+vso.code_status`;
      default:
        if (endpoint != null)
          return `${endpoint}/login/oauth/authorize?client_id=${clientId}&allow_signup=false&scope=repo`;
        else return `https://github.com/login/oauth/authorize?client_id=${clientId}&allow_signup=false&scope=repo`;
    }
  };

  const getDefaultHttps = (vcsType: VcsTypeExtended) => {
    switch (vcsType) {
      case "GITLAB":
        return `https://gitlab.com`;
      case "BITBUCKET":
        return `https://bitbucket.org`;
      case "AZURE_DEVOPS":
        return `https://app.vssps.visualstudio.com`;
      case "GITHUB":
        return `https://github.com`;
      default:
        return ``;
    }
  };

  const onFinish = (values: CreateVcsForm) => {
    const body = {
      data: {
        type: "vcs",
        attributes: {
          name: values.name,
          description: values.name,
          connectionType: connectionType,
          vcsType: getVcsType(vcsType),
          clientId: values.clientId,
          clientSecret: getVcsType(vcsType) != "AZURE_SP_MI" ? values.clientSecret : "12345",
          privateKey: values.privateKey,
          callback: uuid,
          endpoint: values.endpoint,
          apiUrl: values.apiUrl,
          redirectUrl: `${window._env_.REACT_APP_REDIRECT_URI}/organizations/${orgid}/settings/vcs`,
          status: connectionType === "OAUTH" || getVcsType(vcsType) != "AZURE_SP_MI" ? "PENDING" : "COMPLETED",
        },
      },
    };
    axiosInstance
      .post(`organization/${orgid}/vcs`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        if (response.status == 201) {
          if (connectionType === "OAUTH" && getVcsType(vcsType) != "AZURE_SP_MI") {
            window.location.replace(
              getConnectUrl(
                vcsType,
                response.data.data.attributes.clientId,
                getCallBackUrl(),
                response.data.data.attributes.endpoint
              )
            );
          }
          loadVCS();
          setMode("list");
        }
      })
      .catch((error) => {
        if (error.response) {
          if (error.response.status === 403) {
            message.error(
              <span>
                You are not authorized to create VCS Settings. <br /> Please contact your administrator and request the{" "}
                <b>Manage VCS Settings</b> permission. <br /> For more information, visit the{" "}
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
          }
        }
      });
  };
  return (
    <div>
      <h1>Add VCS Provider</h1>
      <div>
        <Typography.Text type="secondary" className="App-text">
          To connect workspaces and modules to git repositories containing configurations, Terrakube needs access to
          your version control system (VCS) provider.
        </Typography.Text>
      </div>
      <Steps direction="horizontal" size="small" current={current} onChange={handleChange}>
        <Step title="Connect to VCS" />
        <Step title="Set up provider" />
      </Steps>
      {current == 0 && (
        <Space className="chooseType" direction="vertical">
          <h3>Choose a version control provider to connect</h3>
          <div className="workflowDescription2 App-text">
            Choose the version control provider you would like to connect.
          </div>
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
        </Space>
      )}
      {current == 1 && (
        <Space className="chooseType" direction="vertical">
          <h3>Set up provider</h3>
          <Typography.Text type="secondary" className="paragraph">
            For additional information about connecting to {renderVCSType(vcsType)} to Terrakube, please read our{" "}
            <Button className="link" target="_blank" href={getDocsUrl(vcsType)} type="link">
              documentation&nbsp; <HiOutlineExternalLink />.
            </Button>
          </Typography.Text>
          {renderStep1(vcsType)}
          {renderStep2(vcsType)}
          <Form
            onFinish={onFinish}
            validateMessages={validateMessages}
            name="create-vcs"
            layout="vertical"
            initialValues={{
              endpoint: getDefaultHttps(vcsType),
              apiUrl: getAPIUrl(vcsType),
            }}
          >
            <Form.Item
              name="name"
              label="Name"
              extra=" A name for your VCS Provider. This is helpful if you will be configuring multiple instances of the same provider."
              rules={[{ required: true }]}
            >
              <Input placeholder={renderVCSType(vcsType)} />
            </Form.Item>
            <Form.Item name="endpoint" label="HTTPS URL" rules={[{ required: true }]} hidden={httpsHidden(vcsType)}>
              <Input placeholder={getHttpsPlaceholder(vcsType)} />
            </Form.Item>
            <Form.Item name="apiUrl" label="API URL" rules={[{ required: true }]} hidden={apiUrlHidden(vcsType)}>
              <Input placeholder={getAPIUrlPlaceholder(vcsType)} />
            </Form.Item>
            <Form.Item name="clientId" label={getClientIdName(vcsType)} rules={[{ required: true }]}>
              <Input placeholder={connectionType === "OAUTH" ? "ex. 824ff023a7136981f322" : "970081"} />
            </Form.Item>
            {renderStep3(vcsType)}
            <Form.Item
              name="clientSecret"
              label={getSecretIdName(vcsType)}
              rules={[{ required: connectionType === "OAUTH" && vcsType != "AZURE_DEVOPS" }]}
              hidden={connectionType != "OAUTH" || vcsType === "AZURE_DEVOPS"}
            >
              <Input placeholder="ex. db55545bd64e851dc298ba900dd197a02b42bb3s" />
            </Form.Item>
            <Form.Item
              name="privateKey"
              label={getSecretIdName(vcsType)}
              rules={[{ required: connectionType != "OAUTH" ? true : false }]}
              hidden={connectionType === "OAUTH"}
            >
              <TextArea placeholder="-----BEGIN PRIVATE KEY-----" style={{ minHeight: "200px" }} />
            </Form.Item>
            <Button type="primary" htmlType="submit">
              Connect and Continue
            </Button>
          </Form>
        </Space>
      )}
    </div>
  );
};
