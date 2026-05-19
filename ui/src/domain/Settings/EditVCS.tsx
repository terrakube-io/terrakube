import { Button, Form, Input, Space, Spin, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { HiOutlineExternalLink } from "react-icons/hi";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { VcsConnectionType, VcsType, VcsTypeExtended } from "../types";
import "./Settings.css";

type Props = {
  vcsId: string;
  setMode: React.Dispatch<React.SetStateAction<"list" | "new" | "edit">>;
  loadVCS: () => void;
};

type EditVcsForm = {
  name: string;
  endpoint: string;
  apiUrl: string;
  clientId: string;
  clientSecret: string;
  privateKey: string;
};

const DEFAULT_ENDPOINTS: Partial<Record<VcsType, string>> = {
  [VcsType.GITHUB]: "https://github.com",
  [VcsType.GITLAB]: "https://gitlab.com",
  [VcsType.BITBUCKET]: "https://bitbucket.org",
  [VcsType.AZURE_SP_MI]: "https://app.vssps.visualstudio.com",
};

const getVcsTypeExtended = (
  vcsType: VcsType,
  connectionType: VcsConnectionType,
  endpoint: string | null | undefined
): VcsTypeExtended => {
  const ep = endpoint ?? "";
  const defaultEp = DEFAULT_ENDPOINTS[vcsType] ?? "";
  const isDefault = !ep || ep === defaultEp;

  switch (vcsType) {
    case VcsType.GITHUB:
      if (connectionType === VcsConnectionType.STANDALONE) {
        return isDefault ? VcsTypeExtended.GITHUB_APP : VcsTypeExtended.GITHUB_ENTERPRISE;
      }
      return isDefault ? VcsTypeExtended.GITHUB : VcsTypeExtended.GITHUB_ENTERPRISE;
    case VcsType.GITLAB:
      return isDefault ? VcsTypeExtended.GITLAB : VcsTypeExtended.GITLAB_ENTERPRISE;
    case VcsType.BITBUCKET:
      return isDefault ? VcsTypeExtended.BITBUCKET : VcsTypeExtended.BITBUCKET_SERVER;
    case VcsType.AZURE_SP_MI:
    case VcsType.AZURE_DEVOPS:
      return isDefault ? VcsTypeExtended.AZURE_DEVOPS : VcsTypeExtended.AZURE_DEVOPS_SERVER;
    default:
      return VcsTypeExtended.GITHUB;
  }
};

const renderVCSType = (vcs: VcsTypeExtended): string => {
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
    case "GITHUB_APP":
      return "Github App";
    default:
      return "GitHub";
  }
};

const getDocsUrl = (vcs: VcsTypeExtended): string => {
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

const httpsHidden = (vcs: VcsTypeExtended): boolean => {
  switch (vcs) {
    case "GITLAB":
    case "BITBUCKET":
    case "AZURE_DEVOPS":
    case "GITHUB_APP":
    case "GITHUB":
      return true;
    default:
      return false;
  }
};

const apiUrlHidden = (vcs: VcsTypeExtended): boolean => {
  switch (vcs) {
    case "GITLAB":
    case "BITBUCKET":
    case "AZURE_DEVOPS":
    case "GITHUB_APP":
    case "GITHUB":
      return true;
    default:
      return false;
  }
};

const getClientIdName = (vcs: VcsTypeExtended, connectionType: VcsConnectionType): string => {
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
      return connectionType === VcsConnectionType.OAUTH ? "Client ID" : "App ID";
  }
};

const getSecretIdName = (vcs: VcsTypeExtended, connectionType: VcsConnectionType): string => {
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
      return connectionType === VcsConnectionType.OAUTH ? "Client Secret" : "Private Key in PKCS#8 format";
  }
};

const validatePrivateKeyFormat = (_: unknown, value: string) => {
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

const validateUrlFormat = (_: unknown, value: string) => {
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

export const EditVCS = ({ vcsId, setMode, loadVCS }: Props) => {
  const { orgid } = useParams();
  const [loading, setLoading] = useState(true);
  const [vcsTypeExtended, setVcsTypeExtended] = useState<VcsTypeExtended>(VcsTypeExtended.GITHUB);
  const [connectionType, setConnectionType] = useState<VcsConnectionType>(VcsConnectionType.OAUTH);
  const [form] = Form.useForm<EditVcsForm>();

  useEffect(() => {
    setLoading(true);
    axiosInstance
      .get(`organization/${orgid}/vcs/${vcsId}`)
      .then((response) => {
        const attrs = response.data.data.attributes;
        const extended = getVcsTypeExtended(attrs.vcsType, attrs.connectionType, attrs.endpoint);
        setVcsTypeExtended(extended);
        setConnectionType(attrs.connectionType);
        form.setFieldsValue({
          name: attrs.name,
          endpoint: attrs.endpoint ?? "",
          apiUrl: attrs.apiUrl ?? "",
          clientId: attrs.clientId,
          clientSecret: "",
          privateKey: "",
        });
      })
      .catch((err) => {
        message.error(getErrorMessage(err));
      })
      .finally(() => setLoading(false));
  }, [vcsId]);

  const onFinish = async (values: EditVcsForm) => {
    const attributes: Record<string, unknown> = {
      name: values.name,
      endpoint: httpsHidden(vcsTypeExtended) ? undefined : values.endpoint,
      apiUrl: apiUrlHidden(vcsTypeExtended) ? undefined : values.apiUrl,
      clientId: values.clientId,
    };
    if (connectionType === VcsConnectionType.OAUTH && values.clientSecret) {
      attributes.clientSecret = values.clientSecret;
    }
    if (connectionType === VcsConnectionType.STANDALONE && values.privateKey) {
      attributes.privateKey = values.privateKey;
    }

    try {
      await axiosInstance.patch(
        `organization/${orgid}/vcs/${vcsId}`,
        { data: { type: "vcs", id: vcsId, attributes } },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      );
      message.success("VCS provider updated successfully");
      loadVCS();
      setMode("list");
    } catch (err: unknown) {
      message.error(getErrorMessage(err));
    }
  };

  const onCancel = () => {
    form.resetFields();
    setMode("list");
  };

  const secretHidden =
    connectionType !== VcsConnectionType.OAUTH ||
    vcsTypeExtended === VcsTypeExtended.AZURE_DEVOPS ||
    vcsTypeExtended === VcsTypeExtended.AZURE_DEVOPS_SERVER;

  return (
    <Spin spinning={loading}>
      <div className="chooseType">
        <h1>Edit VCS Provider</h1>
        <Typography.Text type="secondary" className="App-text">
          Update the {renderVCSType(vcsTypeExtended)} client credentials used by Terrakube to access your version
          control system. For additional information, please read our{" "}
          <Button className="link" target="_blank" href={getDocsUrl(vcsTypeExtended)} type="link">
            documentation&nbsp;
            <HiOutlineExternalLink />
          </Button>
        </Typography.Text>
        <br />
        <br />
        <Typography.Text type="secondary">
          <b>Provider:</b> {renderVCSType(vcsTypeExtended)}&nbsp;&nbsp;
          <b>Connection type:</b> {connectionType === VcsConnectionType.OAUTH ? "OAuth App" : "GitHub App (Standalone)"}
        </Typography.Text>
        <Form form={form} layout="vertical" onFinish={onFinish} style={{ marginTop: 24 }}>
          <Form.Item name="name" label="Name" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="endpoint"
            label="HTTPS URL"
            hidden={httpsHidden(vcsTypeExtended)}
            rules={[{ required: !httpsHidden(vcsTypeExtended) }, { validator: validateUrlFormat }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="apiUrl"
            label="API URL"
            hidden={apiUrlHidden(vcsTypeExtended)}
            rules={[{ required: !apiUrlHidden(vcsTypeExtended) }, { validator: validateUrlFormat }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="clientId"
            label={getClientIdName(vcsTypeExtended, connectionType)}
            rules={[{ required: true }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="clientSecret"
            label={getSecretIdName(vcsTypeExtended, connectionType)}
            hidden={secretHidden}
            extra="Leave blank to keep the existing secret"
          >
            <Input.Password />
          </Form.Item>
          <Form.Item
            name="privateKey"
            label={getSecretIdName(vcsTypeExtended, connectionType)}
            hidden={connectionType === VcsConnectionType.OAUTH}
            extra="Leave blank to keep the existing private key"
            rules={[{ validator: validatePrivateKeyFormat }]}
          >
            <Input.TextArea placeholder="-----BEGIN PRIVATE KEY-----" style={{ minHeight: "200px" }} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                Update
              </Button>
              <Button onClick={onCancel}>Cancel</Button>
            </Space>
          </Form.Item>
        </Form>
      </div>
    </Spin>
  );
};
