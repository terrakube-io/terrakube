import { GithubOutlined, GitlabOutlined } from "@ant-design/icons";
import { Button, Flex, Form, Input, Radio, Switch, Typography, message } from "antd";
import { useState } from "react";
import { HiOutlineExternalLink } from "react-icons/hi";
import { SiBitbucket } from "react-icons/si";
import { VscAzureDevops } from "react-icons/vsc";
import { useParams, useSearchParams } from "react-router-dom";
import { v1 as uuidv1 } from "uuid";
import { useOrganizationName } from "@/hooks/useOrganizationName";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { getUiRedirectUri } from "../../config/basePath";
import { VcsConnectionType, VcsType, VcsTypeExtended } from "../types";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import "./Settings.css";
import "./VCS.css";
import "./components/ResourceCard.css";
import { PermissionErrorMessage } from "@/components/feedback/PermissionErrorMessage";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
import { RadioChoices } from "@/components/settings/RadioChoices";
import { IdField } from "@/components/settings/IdField";
import {
  getApiOrigin,
  getCallbackUrl,
  getClientIdName,
  getConnectUrl,
  getDocsUrl,
  getSecretIdName,
  getVcsType,
  usesFixedUrls,
  usesOAuthFlow,
  validatePrivateKeyFormat,
  validateUrlFormat,
  vcsLabel,
} from "./vcsProviders";

const validateMessages = {
  required: "${label} is required",
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
  clientId: string;
  clientSecret: string;
  privateKey: string;
  endpoint: string;
  apiUrl: string;
  appWebhookEnabled?: boolean;
  webhookSecret?: string;
};

type Choice = { vcs: VcsTypeExtended; connectionType: VcsConnectionType; label: string; help?: string };

const { OAUTH, STANDALONE } = VcsConnectionType;

// Four providers as tiles, then their editions as stacked radios (DESIGN.md, Picker).
const PROVIDERS: { key: string; label: string; icon: React.ReactNode; choices: Choice[] }[] = [
  {
    key: "github",
    label: "GitHub",
    icon: <GithubOutlined />,
    choices: [
      {
        vcs: VcsTypeExtended.GITHUB_APP,
        connectionType: STANDALONE,
        label: "GitHub.com with a GitHub App",
        help: "Installed on an organization or account, with only the repository permissions you grant it.",
      },
      {
        vcs: VcsTypeExtended.GITHUB,
        connectionType: OAUTH,
        label: "GitHub.com with an OAuth app",
        help: "Acts as the user who connects it, with access to all of their repositories.",
      },
      {
        vcs: VcsTypeExtended.GITHUB_ENTERPRISE,
        connectionType: STANDALONE,
        label: "GitHub Enterprise with a GitHub App",
        help: "For a self-hosted GitHub Enterprise Server.",
      },
      {
        vcs: VcsTypeExtended.GITHUB_ENTERPRISE,
        connectionType: OAUTH,
        label: "GitHub Enterprise with an OAuth app",
        help: "For a self-hosted GitHub Enterprise Server, acting as the user who connects it.",
      },
    ],
  },
  {
    key: "gitlab",
    label: "GitLab",
    icon: <GitlabOutlined />,
    choices: [
      { vcs: VcsTypeExtended.GITLAB, connectionType: OAUTH, label: "GitLab.com" },
      {
        vcs: VcsTypeExtended.GITLAB_COMMUNITY,
        connectionType: OAUTH,
        label: "GitLab Community Edition",
        help: "A self-managed GitLab instance.",
      },
      {
        vcs: VcsTypeExtended.GITLAB_ENTERPRISE,
        connectionType: OAUTH,
        label: "GitLab Enterprise Edition",
        help: "A self-managed GitLab instance.",
      },
    ],
  },
  {
    key: "bitbucket",
    label: "Bitbucket",
    icon: <SiBitbucket />,
    choices: [{ vcs: VcsTypeExtended.BITBUCKET, connectionType: OAUTH, label: "Bitbucket Cloud" }],
  },
  {
    key: "azure",
    label: "Azure DevOps",
    icon: <VscAzureDevops />,
    choices: [{ vcs: VcsTypeExtended.AZURE_DEVOPS, connectionType: OAUTH, label: "Azure DevOps Services" }],
  },
];

const choiceKey = (choice: { vcs: VcsTypeExtended; connectionType: VcsConnectionType }) =>
  `${choice.vcs}:${choice.connectionType}`;

const providerOf = (vcs: VcsTypeExtended) =>
  PROVIDERS.find((provider) => provider.choices.some((choice) => choice.vcs === vcs)) ?? PROVIDERS[0];

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

const getDefaultHttps = (vcs: VcsTypeExtended) => {
  switch (vcs) {
    case "GITLAB":
      return "https://gitlab.com";
    case "BITBUCKET":
      return "https://bitbucket.org";
    case "AZURE_DEVOPS":
      return "https://app.vssps.visualstudio.com";
    case "GITHUB":
    case "GITHUB_APP":
      return "https://github.com";
    default:
      return "";
  }
};

const getHttpsPlaceholder = (vcs: VcsTypeExtended) => {
  switch (vcs) {
    case "GITLAB_ENTERPRISE":
    case "GITLAB_COMMUNITY":
      return "https://gitlab.example.com";
    case "BITBUCKET_SERVER":
      return "https://bitbucket.example.com/context-path";
    case "AZURE_DEVOPS_SERVER":
      return "https://azure-devops.example.com";
    default:
      return "https://github.example.com";
  }
};

const getAPIUrlPlaceholder = (vcs: VcsTypeExtended) => {
  switch (vcs) {
    case "GITLAB_ENTERPRISE":
    case "GITLAB_COMMUNITY":
      return "https://gitlab.example.com/api/v4";
    case "BITBUCKET_SERVER":
      return "https://bitbucket.example.com/context-path/rest/api/1.0";
    case "AZURE_DEVOPS_SERVER":
      return "https://azure-devops.example.com";
    default:
      return "https://github.example.com/api/v3";
  }
};

const ExternalLink = ({ href, children }: { href: string; children: React.ReactNode }) => (
  <Typography.Link target="_blank" rel="noreferrer" href={href}>
    {children} <HiOutlineExternalLink />
  </Typography.Link>
);

export const AddVCS = ({ setMode, loadVCS }: Props) => {
  const { orgid, vcsName } = useParams<Params>();
  const [searchParams] = useSearchParams();
  const [saving, setSaving] = useState(false);
  const [current, setCurrent] = useState(vcsName ? 1 : 0);
  const [vcsType, setVcsType] = useState<VcsTypeExtended>(vcsName ? vcsName : VcsTypeExtended.GITHUB_APP);
  const [connectionType, setConnectionType] = useState(
    vcsName ? (searchParams.get("connectionType") === STANDALONE ? STANDALONE : OAUTH) : STANDALONE
  );
  const [uuid] = useState(uuidv1());

  const provider = providerOf(vcsType);
  const label = vcsLabel(vcsType);
  const callbackUrl = getCallbackUrl(uuid);
  const terrakubeAppName = `Terrakube (${useOrganizationName(orgid) ?? ""})`;
  const isGithubFamily = getVcsType(vcsType) === VcsType.GITHUB;
  const oauth = connectionType === OAUTH;
  const showSecret = oauth && vcsType !== VcsTypeExtended.AZURE_DEVOPS;
  const githubApp = isGithubFamily && !oauth;
  const [form] = Form.useForm<CreateVcsForm>();
  const appWebhookEnabled = Form.useWatch("appWebhookEnabled", form);
  const showAppWebhook = githubApp && !!appWebhookEnabled;

  const choose = (choice: Choice) => {
    setVcsType(choice.vcs);
    setConnectionType(choice.connectionType);
  };

  const renderRegistration = () => {
    switch (vcsType) {
      case "GITLAB":
      case "GITLAB_ENTERPRISE":
      case "GITLAB_COMMUNITY":
        return (
          <SettingsSection
            maxWidth="100%"
            title={`Register Terrakube on ${label}`}
            description={
              vcsType === "GITLAB" ? (
                <>
                  On GitLab,{" "}
                  <ExternalLink href="https://gitlab.com/-/profile/applications">
                    register a new OAuth application
                  </ExternalLink>{" "}
                  with these values.
                </>
              ) : (
                "In User settings → Applications, register a new OAuth application with these values."
              )
            }
          >
            <IdField id="vcs-app-name" label="Name" value={terrakubeAppName} />
            <IdField id="vcs-callback-url" label="Redirect URI" value={callbackUrl} />
            <Form.Item label="Scopes">
              <Typography.Text code>api</Typography.Text>
            </Form.Item>
          </SettingsSection>
        );
      case "BITBUCKET":
      case "BITBUCKET_SERVER":
        return (
          <SettingsSection
            maxWidth="100%"
            title={`Register Terrakube on ${label}`}
            description="Signed in as the account Terrakube should act as, add an OAuth consumer under your workspace settings with these values."
          >
            <IdField id="vcs-app-name" label="Name" value={terrakubeAppName} />
            <IdField id="vcs-callback-url" label="Callback URL" value={callbackUrl} />
            <IdField id="vcs-homepage-url" label="URL" value={getApiOrigin()} />
            <Form.Item label="This is a private consumer">Checked</Form.Item>
            <Form.Item label="Permissions">
              <ul className="vcs-guide-list">
                <li>Account: Write</li>
                <li>Repositories: Admin</li>
                <li>Pull requests: Write</li>
                <li>Webhooks: Read and write</li>
              </ul>
            </Form.Item>
          </SettingsSection>
        );
      case "AZURE_DEVOPS":
      case "AZURE_DEVOPS_SERVER":
        return (
          <SettingsSection
            maxWidth="100%"
            title={`Give the managed identity access to ${label}`}
            description={
              <>
                In {label},{" "}
                <ExternalLink href="https://aex.dev.azure.com/me">grant access to the managed identity</ExternalLink> as
                follows.
              </>
            }
          >
            <Form.Item label="Organization">Add the managed identity with the Basic access level.</Form.Item>
            <Form.Item label="Repositories">Add the managed identity with the Contributor access level.</Form.Item>
          </SettingsSection>
        );
      default: {
        const kind = oauth ? "OAuth app" : "GitHub App";
        const path = oauth ? "applications" : "apps";
        return (
          <SettingsSection
            maxWidth="100%"
            title={`Register Terrakube on ${label}`}
            description={
              <>
                {vcsType === "GITHUB_ENTERPRISE" ? (
                  <>
                    On your server, open <Typography.Text code>{`/settings/${path}/new`}</Typography.Text> and register
                    a new {kind} with these values.
                  </>
                ) : (
                  <>
                    On GitHub,{" "}
                    <ExternalLink href={`https://github.com/settings/${path}/new`}>register a new {kind}</ExternalLink>{" "}
                    with these values.
                  </>
                )}
                {!oauth && (
                  <>
                    {" "}
                    Then install it on your organization or account.{" "}
                    <ExternalLink href="https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/about-creating-github-apps">
                      About GitHub Apps
                    </ExternalLink>
                  </>
                )}
              </>
            }
          >
            <IdField id="vcs-app-name" label="Application name" value={terrakubeAppName} />
            <IdField id="vcs-homepage-url" label="Homepage URL" value={getApiOrigin()} />
            <IdField id="vcs-callback-url" label="Authorization callback URL" value={callbackUrl} />
            <Form.Item label="Webhook">
              {showAppWebhook
                ? "Leave Active unchecked for now. After you add this provider, open its edit page, copy the webhook URL and the same secret into the App's webhook settings, and check Active."
                : "Leave Active unchecked."}
            </Form.Item>
            {showAppWebhook && (
              <Form.Item label="Subscribe to events">Push, Pull request, Issue comment, Release</Form.Item>
            )}
            <Form.Item label="Repository permissions">
              <ul className="vcs-guide-list">
                <li>Contents: Read-only</li>
                <li>Metadata: Read-only</li>
                <li>Commit statuses: Read and write, for workspaces that run on VCS webhooks</li>
                {showAppWebhook ? (
                  <>
                    <li>Issues: Read-only, for Issue comment events</li>
                    <li>Webhooks: Read and write, only if you later turn off GitHub App delivery</li>
                  </>
                ) : (
                  <li>Webhooks: Read and write, for workspaces that run on VCS webhooks</li>
                )}
                <li>Pull requests: Read and write, for webhooks and to comment plans on pull requests</li>
              </ul>
            </Form.Item>
          </SettingsSection>
        );
      }
    }
  };

  const credentialsHelp = () => {
    switch (getVcsType(vcsType)) {
      case VcsType.GITLAB:
        return "After you save the application, GitLab shows its application ID and secret.";
      case VcsType.BITBUCKET:
        return "After you save, open the new consumer under OAuth consumers to see its key and secret.";
      case VcsType.AZURE_SP_MI:
        return "Enter the app ID of the managed identity you gave access to.";
      default:
        return oauth
          ? "After you register the application, copy its client ID and generate a client secret."
          : "After you register the app, copy its app ID, then generate a private key and convert it to PKCS#8.";
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
          ...(githubApp && {
            appWebhookEnabled: !!values.appWebhookEnabled,
            webhookSecret: values.appWebhookEnabled ? values.webhookSecret : undefined,
          }),
          callback: uuid,
          endpoint: values.endpoint,
          apiUrl: values.apiUrl,
          redirectUrl: `${getUiRedirectUri()}/organizations/${orgid}/settings/vcs`,
          status: connectionType === "OAUTH" || getVcsType(vcsType) != "AZURE_SP_MI" ? "PENDING" : "COMPLETED",
        },
      },
    };
    setSaving(true);
    axiosInstance
      .post(`organization/${orgid}/vcs`, body, {
        headers: {
          "Content-Type": "application/vnd.api+json",
        },
      })
      .then((response) => {
        if (response.status == 201) {
          if (usesOAuthFlow(getVcsType(vcsType), connectionType)) {
            window.location.replace(
              getConnectUrl(
                getVcsType(vcsType),
                response.data.data.attributes.clientId,
                callbackUrl,
                response.data.data.attributes.endpoint
              )
            );
          } else {
            message.success("VCS provider added");
          }
          loadVCS();
          setMode("list");
        }
      })
      .catch((error) => {
        if (error.response?.status === 403) {
          message.error(<PermissionErrorMessage action="create VCS Settings" permission="Manage VCS Settings" />);
        } else {
          message.error(`Could not add the VCS provider: ${getErrorMessage(error)}`);
        }
      })
      .finally(() => setSaving(false));
  };

  return (
    <div>
      <SettingsPageHeader
        docUrl={current === 1 ? getDocsUrl(vcsType) : "https://docs.terrakube.io/user-guide/vcs-providers"}
        title="Add a VCS provider"
        description="Register Terrakube with your provider, then enter the credentials it gives you."
        divider={false}
      />
      {current == 0 && (
        <SettingsForm name="choose-vcs" onFinish={() => setCurrent(1)} saveLabel="Continue">
          <Form.Item label="Provider">
            <Radio.Group
              className="vcs-provider-tiles"
              value={provider.key}
              onChange={(event) => choose(PROVIDERS.find((p) => p.key === event.target.value)!.choices[0])}
            >
              {PROVIDERS.map((p) => (
                <Radio key={p.key} value={p.key} className="vcs-provider-tile">
                  <span className="vcs-provider-tile-icon" aria-hidden="true">
                    {p.icon}
                  </span>
                  {p.label}
                </Radio>
              ))}
            </Radio.Group>
          </Form.Item>
          {provider.choices.length > 1 && (
            <Form.Item label="Type">
              <RadioChoices
                value={choiceKey({ vcs: vcsType, connectionType })}
                onChange={(event) => choose(provider.choices.find((c) => choiceKey(c) === event.target.value)!)}
                options={provider.choices.map((c) => ({ value: choiceKey(c), label: c.label, help: c.help }))}
              />
            </Form.Item>
          )}
        </SettingsForm>
      )}
      {current == 1 && (
        <SettingsForm
          form={form}
          onFinish={onFinish}
          validateMessages={validateMessages}
          name="create-vcs"
          initialValues={{
            endpoint: getDefaultHttps(vcsType),
            apiUrl: getAPIUrl(vcsType),
          }}
          saveLabel={usesOAuthFlow(getVcsType(vcsType), connectionType) ? "Add and connect" : "Add VCS provider"}
          saving={saving}
        >
          <Form.Item label="Provider">
            <Flex align="center" gap="small" wrap>
              <span className="vcs-provider-selected-icon" aria-hidden="true">
                {provider.icon}
              </span>
              <Typography.Text>
                {label}
                {isGithubFamily && (oauth ? " (OAuth app)" : " (GitHub App)")}
              </Typography.Text>
              <Button type="link" size="small" onClick={() => setCurrent(0)}>
                Change provider
              </Button>
            </Flex>
          </Form.Item>

          {renderRegistration()}

          <SettingsSection maxWidth="100%" title="Credentials" description={credentialsHelp()}>
            <Form.Item
              name="name"
              label="Name"
              extra="Tells connections apart when you add more than one provider."
              rules={[{ required: true }]}
            >
              <Input placeholder={label} />
            </Form.Item>
            <Form.Item name="clientId" label={getClientIdName(vcsType, connectionType)} rules={[{ required: true }]}>
              <Input className="resource-mono" placeholder={oauth ? "824ff023a7136981f322" : "970081"} />
            </Form.Item>
            <Form.Item
              name="endpoint"
              label="HTTPS URL"
              extra="The address of your instance, as users open it in a browser."
              rules={[{ required: !usesFixedUrls(vcsType) }, { validator: validateUrlFormat }]}
              hidden={usesFixedUrls(vcsType)}
            >
              <Input className="resource-mono" placeholder={getHttpsPlaceholder(vcsType)} />
            </Form.Item>
            <Form.Item
              name="apiUrl"
              label="API URL"
              extra="Terrakube calls this address to read repositories and register webhooks."
              rules={[{ required: !usesFixedUrls(vcsType) }, { validator: validateUrlFormat }]}
              hidden={usesFixedUrls(vcsType)}
            >
              <Input className="resource-mono" placeholder={getAPIUrlPlaceholder(vcsType)} />
            </Form.Item>
            {showSecret && (
              <Form.Item
                name="clientSecret"
                label={getSecretIdName(vcsType, connectionType)}
                rules={[{ required: true }]}
              >
                <Input.Password className="resource-mono" autoComplete="off" />
              </Form.Item>
            )}
            {githubApp && (
              <Form.Item
                name="privateKey"
                label={getSecretIdName(vcsType, connectionType)}
                extra={
                  <>
                    Convert the downloaded key with{" "}
                    <Typography.Text code>openssl pkcs8 -topk8 -nocrypt -in key.pem</Typography.Text>.
                  </>
                }
                rules={[{ required: true }, { validator: validatePrivateKeyFormat }]}
              >
                <Input.TextArea className="resource-mono" placeholder="-----BEGIN PRIVATE KEY-----" rows={8} />
              </Form.Item>
            )}
            {githubApp && (
              <Form.Item
                name="appWebhookEnabled"
                label="Receive events via the GitHub App webhook"
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
            )}
            {showAppWebhook && (
              <Form.Item name="webhookSecret" label="Webhook secret" rules={[{ required: true }]}>
                <Input.Password className="resource-mono" autoComplete="off" />
              </Form.Item>
            )}
          </SettingsSection>
        </SettingsForm>
      )}
    </div>
  );
};
