import {
  DownOutlined,
  GithubOutlined,
  GitlabOutlined,
  InfoCircleOutlined,
  QuestionCircleOutlined,
} from "@ant-design/icons";
import { Button, Col, Descriptions, Dropdown, Flex, Form, Input, Row, Space, Steps, Typography, message } from "antd";
import TextArea from "antd/es/input/TextArea";
import { useState } from "react";
import { HiOutlineExternalLink } from "react-icons/hi";
import { SiBitbucket } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import { useParams, useSearchParams } from "react-router-dom";
import { v1 as uuidv1 } from "uuid";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import axiosInstance from "../../config/axiosConfig";
import { getUiRedirectUri } from "../../config/basePath";
import { VcsConnectionType, VcsType, VcsTypeExtended } from "../types";
import SettingsSection from "@/components/SettingsSection/SettingsSection";
import "./Settings.css";
import { PermissionErrorMessage } from "@/components/PermissionErrorMessage";
import { SettingsPageHeader } from "@/components/SettingsPageHeader";

const validateMessages = {
  required: "${label} is required!",
};

const GuideStep = ({ number, children }: { number: number; children: React.ReactNode }) => (
  <div className="vcs-guide-step">
    <div className="vcs-guide-step-number">{number}</div>
    <div className="vcs-guide-step-content">{children}</div>
  </div>
);

const GuideValues = ({ items }: { items: { label: string; value: React.ReactNode; copyable?: boolean }[] }) => (
  <Descriptions
    column={1}
    bordered
    size="small"
    className="vcs-guide-values"
    items={items.map((item, index) => ({
      key: String(index),
      label: item.label,
      children: item.copyable ? (
        <Typography.Paragraph copyable style={{ margin: 0 }}>
          {item.value}
        </Typography.Paragraph>
      ) : (
        item.value
      ),
    }))}
  />
);

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
  const [searchParams] = useSearchParams();
  const [current, setCurrent] = useState(vcsName ? 1 : 0);
  const [vcsType, setVcsType] = useState<VcsTypeExtended>(vcsName ? vcsName : VcsTypeExtended.GITHUB);
  const [connectionType, setConnectionType] = useState(
    searchParams.get("connectionType") === VcsConnectionType.STANDALONE
      ? VcsConnectionType.STANDALONE
      : VcsConnectionType.OAUTH
  );
  const [uuid] = useState(uuidv1());

  const validatePrivateKeyFormat = (_: any, value: string) => {
    if (!value) {
      return Promise.resolve();
    }

    if (!value.includes("-----BEGIN PRIVATE KEY-----")) {
      return Promise.reject(new Error("Private key must be in PKCS#8 format (-----BEGIN PRIVATE KEY-----)"));
    }

    if (!value.includes("-----END PRIVATE KEY-----")) {
      return Promise.reject(new Error("Private key is incomplete (missing -----END PRIVATE KEY-----)"));
    }

    return Promise.resolve();
  };

  const validateUrlFormat = (_: any, value: string) => {
    if (!value) {
      return Promise.resolve();
    }

    try {
      const url = new URL(value);
      if (url.protocol !== "http:" && url.protocol !== "https:") {
        return Promise.reject(new Error("URL must start with http:// or https://"));
      }
      return Promise.resolve();
    } catch {
      return Promise.reject(new Error("Please enter a valid URL"));
    }
  };

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
        return "Azure DevOps";
      case "AZURE_DEVOPS_SERVER":
        return "Azure DevOps Server";
      case "GITHUB_ENTERPRISE":
        return "GitHub Enterprise";
      case "GITHUB_APP":
        return "GitHub App";
      default:
        return "GitHub";
    }
  };
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
      case "GITHUB_APP":
        return "https://docs.terrakube.io/user-guide/vcs-providers/github-app";
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
      case "GITHUB_APP":
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
      case "GITHUB_APP":
        return true;
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
      case "GITHUB_APP":
        return true;
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
          <GuideStep number={1}>
            <Typography.Text>
              On {renderVCSType(vcsType)},{" "}
              {vcsType === "GITLAB" ? (
                <>
                  <Typography.Link target="_blank" rel="noreferrer" href="https://gitlab.com/-/profile/applications">
                    register a new OAuth Application <HiOutlineExternalLink />
                  </Typography.Link>{" "}
                  with the following information:
                </>
              ) : (
                <span>
                  navigate to User Settings → Application and register a new OAuth Application with the following
                  information:
                </span>
              )}
            </Typography.Text>
            <GuideValues
              items={[
                { label: "Name", value: `Terrakube (${sessionStorage.getItem(ORGANIZATION_NAME)})`, copyable: true },
                { label: "Redirect URI", value: getCallBackUrl(), copyable: true },
                { label: "Scopes", value: getScopes(vcsType) },
              ]}
            />
          </GuideStep>
        );
      case "BITBUCKET":
      case "BITBUCKET_SERVER":
        return (
          <GuideStep number={1}>
            <Typography.Text>
              On {renderVCSType(vcsType)}, logged in as whichever account you want Terrakube to act as, add a new OAuth
              Consumer. You can find the OAuth Consumer settings page under your workspace settings. Enter the following
              information:
            </Typography.Text>
            <GuideValues
              items={[
                { label: "Name", value: `Terrakube (${sessionStorage.getItem(ORGANIZATION_NAME)})`, copyable: true },
                { label: "Description", value: "Any description of your choice" },
                { label: "Callback URL", value: getCallBackUrl(), copyable: true },
                { label: "URL", value: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin, copyable: true },
                { label: "This is a private consumer", value: "Checked" },
                {
                  label: "Permissions",
                  value: (
                    <ul>
                      <li>Account: Write</li>
                      <li>Repositories: Admin</li>
                      <li>Pull requests: Write</li>
                      <li>Webhooks: Read and write</li>
                    </ul>
                  ),
                },
              ]}
            />
          </GuideStep>
        );
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        return (
          <GuideStep number={1}>
            <Typography.Text>
              On {renderVCSType(vcsType)},{" "}
              <Typography.Link target="_blank" rel="noreferrer" href="https://aex.dev.azure.com/me?mkt=es-ES">
                grant accesses to the managed identity <HiOutlineExternalLink />
              </Typography.Link>{" "}
              with the following information:
            </Typography.Text>
            <GuideValues
              items={[
                {
                  label: "Organization setup",
                  value: "Add the managed identity to the organization and grant the Basic access level",
                },
                {
                  label: "Repository setup",
                  value: "Add the managed identity to the repository and grant the Contributor access level",
                },
              ]}
            />
          </GuideStep>
        );
      default:
        return (
          <GuideStep number={1}>
            <Typography.Text>
              On {renderVCSType(vcsType)},{" "}
              {vcsType === "GITHUB" ? (
                <Typography.Link
                  target="_blank"
                  rel="noreferrer"
                  href={
                    connectionType === "OAUTH"
                      ? "https://github.com/settings/applications/new"
                      : "https://github.com/settings/apps/new"
                  }
                >
                  register a new {connectionType == "OAUTH" ? "OAuth" : "GitHub"} Application <HiOutlineExternalLink />
                </Typography.Link>
              ) : (
                <span>
                  register a new {connectionType == "OAUTH" ? "OAuth" : "GitHub"} Application using the link https://
                  <i>yourdomain.com</i>/settings/{connectionType == "OAUTH" ? "applications" : "apps"}/new
                </span>
              )}{" "}
              with the information below
              {connectionType === "OAUTH" ? (
                <span>:</span>
              ) : (
                <span>
                  , install it to your organization or account, and grant necessary permissions. Please check{" "}
                  <Typography.Link
                    target="_blank"
                    rel="noreferrer"
                    href="https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/about-creating-github-apps"
                  >
                    here to learn more <HiOutlineExternalLink />
                  </Typography.Link>
                  .
                </span>
              )}
            </Typography.Text>
            <GuideValues
              items={[
                {
                  label: "Application Name",
                  value: `Terrakube (${sessionStorage.getItem(ORGANIZATION_NAME)})`,
                  copyable: true,
                },
                {
                  label: "Homepage URL",
                  value: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin,
                  copyable: true,
                },
                { label: "Authorization callback URL", value: getCallBackUrl(), copyable: true },
                { label: "Webhook", value: "Untick Active" },
                {
                  label: "Repository permissions",
                  value: (
                    <ul>
                      <li>Commit statuses: Read and write (only if webhook is used on VCS workflow workspaces)</li>
                      <li>Contents: Read-only</li>
                      <li>Metadata: Read-only</li>
                      <li>
                        Pull requests: Read and write (only if webhook is used on VCS workflow workspaces; write is
                        required to post plan/apply comments back on pull requests when PR Workflow is enabled)
                      </li>
                      <li>Webhooks: Read and write (only if webhook is used on VCS workflow workspaces)</li>
                    </ul>
                  ),
                },
              ]}
            />
          </GuideStep>
        );
    }
  };

  const getStep2Text = (vcs: VcsTypeExtended) => {
    switch (vcs) {
      case "GITLAB":
      case "GITLAB_ENTERPRISE":
      case "GITLAB_COMMUNITY":
        return "After clicking the Save application button, you will be taken to the new application page. Name this connection and enter the Application ID and Secret below.";
      case "BITBUCKET":
      case "BITBUCKET_SERVER":
        return "After clicking the Save button, find your new OAuth consumer under the OAuth Consumers heading, and click its name to reveal its details. Name this connection and enter the Key and Secret below.";
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        return "Now Terrakube should be able to access your Azure DevOps organization. Name this connection and enter the Managed Identity App ID below.";
      default:
        return "After clicking the Register application button, you will be taken to the new application page. Name this connection and enter the Client ID below.";
    }
  };

  const isGithubFamily = getVcsType(vcsType) === "GITHUB";

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
      case "GITHUB_APP":
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
          redirectUrl: `${getUiRedirectUri()}/organizations/${orgid}/settings/vcs`,
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
          } else {
            message.success("VCS provider created successfully");
          }
          loadVCS();
          setMode("list");
        }
      })
      .catch((error) => {
        if (error.response) {
          if (error.response.status === 403) {
            message.error(<PermissionErrorMessage action="create VCS Settings" permission="Manage VCS Settings" />);
          }
        }
      });
  };
  return (
    <div>
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/vcs-providers"
        title="Add VCS Provider"
        description="To connect workspaces and modules to git repositories containing configurations, Terrakube needs access to your version control system (VCS) provider."
      />
      <Steps
        direction="horizontal"
        size="small"
        current={current}
        onChange={handleChange}
        style={{ maxWidth: 960, margin: "8px 0 32px" }}
        items={[
          { title: "Connect to VCS", description: "Choose a provider" },
          { title: "Set up provider", description: "Configure credentials" },
        ]}
      />
      {current == 0 && (
        <SettingsSection
          maxWidth={960}
          title="Choose a version control provider"
          description="Choose the version control provider you would like to connect."
        >
          <Row gutter={[16, 16]}>
            <Col xs={24} sm={12} lg={6}>
              <Dropdown menu={{ items: githubItems }} trigger={["click"]}>
                <button type="button" className="vcs-provider-card">
                  <GithubOutlined className="vcs-provider-icon" />
                  <span>
                    GitHub <DownOutlined className="vcs-provider-caret" />
                  </span>
                </button>
              </Dropdown>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Dropdown menu={{ items: gitlabItems }} trigger={["click"]}>
                <button type="button" className="vcs-provider-card">
                  <GitlabOutlined className="vcs-provider-icon" />
                  <span>
                    GitLab <DownOutlined className="vcs-provider-caret" />
                  </span>
                </button>
              </Dropdown>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Dropdown menu={{ items: bitBucketItems }} trigger={["click"]}>
                <button type="button" className="vcs-provider-card">
                  <SiBitbucket className="vcs-provider-icon" />
                  <span>
                    Bitbucket <DownOutlined className="vcs-provider-caret" />
                  </span>
                </button>
              </Dropdown>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Dropdown menu={{ items: azDevOpsItems }} trigger={["click"]}>
                <button type="button" className="vcs-provider-card">
                  <VscAzureDevops className="vcs-provider-icon" />
                  <span>
                    Azure DevOps <DownOutlined className="vcs-provider-caret" />
                  </span>
                </button>
              </Dropdown>
            </Col>
          </Row>
        </SettingsSection>
      )}
      {current == 1 && (
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
          <SettingsSection
            maxWidth={960}
            title={`Connect to ${renderVCSType(vcsType)}`}
            description={
              <>Create the application on the {renderVCSType(vcsType)} side, then enter its credentials below.</>
            }
            extra={
              <Button
                icon={<QuestionCircleOutlined />}
                type="link"
                href={getDocsUrl(vcsType)}
                target="_blank"
                rel="noreferrer"
              >
                Provider guide
              </Button>
            }
          >
            {renderStep1(vcsType)}

            <GuideStep number={2}>
              <Typography.Text>{getStep2Text(vcsType)}</Typography.Text>
              <Row gutter={16}>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="name"
                    label="Name"
                    tooltip={{
                      title:
                        "A name for your VCS Provider. This is helpful if you will be configuring multiple instances of the same provider.",
                      icon: <InfoCircleOutlined />,
                    }}
                    rules={[{ required: true }]}
                  >
                    <Input placeholder={renderVCSType(vcsType)} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item name="clientId" label={getClientIdName(vcsType)} rules={[{ required: true }]}>
                    <Input placeholder={connectionType === "OAUTH" ? "ex. 824ff023a7136981f322" : "970081"} />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="endpoint"
                    label="HTTPS URL"
                    rules={[{ required: !httpsHidden(vcsType) }, { validator: validateUrlFormat }]}
                    hidden={httpsHidden(vcsType)}
                  >
                    <Input placeholder={getHttpsPlaceholder(vcsType)} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="apiUrl"
                    label="API URL"
                    rules={[{ required: !apiUrlHidden(vcsType) }, { validator: validateUrlFormat }]}
                    hidden={apiUrlHidden(vcsType)}
                  >
                    <Input placeholder={getAPIUrlPlaceholder(vcsType)} />
                  </Form.Item>
                </Col>
              </Row>
              {!isGithubFamily && (
                <Row gutter={16}>
                  <Col xs={24} md={12}>
                    <Form.Item
                      name="clientSecret"
                      label={getSecretIdName(vcsType)}
                      rules={[{ required: connectionType === "OAUTH" && vcsType != "AZURE_DEVOPS" }]}
                      hidden={connectionType != "OAUTH" || vcsType === "AZURE_DEVOPS"}
                    >
                      <Input placeholder="ex. db55545bd64e851dc298ba900dd197a02b42bb3s" />
                    </Form.Item>
                  </Col>
                </Row>
              )}
            </GuideStep>

            {isGithubFamily && (
              <GuideStep number={3}>
                <Typography.Text>
                  Next, generate a{" "}
                  {connectionType === "OAUTH"
                    ? "client secret and"
                    : "private key and convert it to PKCS#8 format then"}{" "}
                  enter the value below.
                </Typography.Text>
                {connectionType === "OAUTH" ? (
                  <Row gutter={16}>
                    <Col xs={24} md={12}>
                      <Form.Item name="clientSecret" label={getSecretIdName(vcsType)} rules={[{ required: true }]}>
                        <Input placeholder="ex. db55545bd64e851dc298ba900dd197a02b42bb3s" />
                      </Form.Item>
                    </Col>
                  </Row>
                ) : (
                  <Form.Item
                    name="privateKey"
                    label={getSecretIdName(vcsType)}
                    rules={[{ required: true }, { validator: validatePrivateKeyFormat }]}
                  >
                    <TextArea placeholder="-----BEGIN PRIVATE KEY-----" style={{ minHeight: "200px" }} />
                  </Form.Item>
                )}
              </GuideStep>
            )}
          </SettingsSection>

          <Flex justify="flex-end" style={{ maxWidth: 960 }}>
            <Space>
              <Button onClick={() => setCurrent(0)}>Back</Button>
              <Button type="primary" htmlType="submit">
                Connect and Continue
              </Button>
            </Space>
          </Flex>
        </Form>
      )}
    </div>
  );
};
