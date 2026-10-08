import { Alert, Form, Input, Spin, Switch, message } from "antd";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../config/axiosConfig";
import { VcsConnectionType, VcsTypeExtended } from "../types";
import "./Settings.css";
import "./components/ResourceCard.css";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
import { IdField } from "@/components/settings/IdField";
import {
  getApiOrigin,
  getCallbackUrl,
  getClientIdName,
  getDocsUrl,
  getSecretIdName,
  getVcsTypeExtended,
  usesFixedUrls,
  validatePrivateKeyFormat,
  validateUrlFormat,
  vcsLabel,
} from "./vcsProviders";

type Props = {
  vcsId: string;
  setMode: (mode: "list" | "new" | "edit") => void;
  loadVCS: () => void;
};

type EditVcsForm = {
  name: string;
  endpoint: string;
  apiUrl: string;
  clientId: string;
  clientSecret: string;
  privateKey: string;
  appWebhookEnabled: boolean;
  webhookSecret: string;
};

export const EditVCS = ({ vcsId, setMode, loadVCS }: Props) => {
  const { orgid } = useParams();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [vcsTypeExtended, setVcsTypeExtended] = useState<VcsTypeExtended>(VcsTypeExtended.GITHUB);
  const [connectionType, setConnectionType] = useState<VcsConnectionType>(VcsConnectionType.OAUTH);
  const [callbackId, setCallbackId] = useState(vcsId);
  // A VCS that was already in App webhook mode has a stored secret, so a blank field keeps it.
  const [appWebhookInitiallyEnabled, setAppWebhookInitiallyEnabled] = useState(false);
  const [form] = Form.useForm<EditVcsForm>();
  const appWebhookEnabled = Form.useWatch("appWebhookEnabled", form);
  const isGithubApp = connectionType === VcsConnectionType.STANDALONE;

  useEffect(() => {
    setLoading(true);
    axiosInstance
      .get(`organization/${orgid}/vcs/${vcsId}`)
      .then((response) => {
        const attrs = response.data.data.attributes;
        setVcsTypeExtended(getVcsTypeExtended(attrs.vcsType, attrs.connectionType, attrs.endpoint));
        setConnectionType(attrs.connectionType);
        setCallbackId(attrs.callback ?? vcsId);
        setAppWebhookInitiallyEnabled(!!attrs.appWebhookEnabled);
        form.setFieldsValue({
          name: attrs.name,
          endpoint: attrs.endpoint ?? "",
          apiUrl: attrs.apiUrl ?? "",
          clientId: attrs.clientId,
          clientSecret: "",
          privateKey: "",
          appWebhookEnabled: !!attrs.appWebhookEnabled,
          webhookSecret: "",
        });
      })
      .catch((err) => {
        message.error(`Could not load the VCS provider: ${getErrorMessage(err)}`);
      })
      .finally(() => setLoading(false));
  }, [vcsId]);

  const fixedUrls = usesFixedUrls(vcsTypeExtended);

  const onFinish = async (values: EditVcsForm) => {
    const attributes: Record<string, unknown> = {
      name: values.name,
      endpoint: fixedUrls ? undefined : values.endpoint,
      apiUrl: fixedUrls ? undefined : values.apiUrl,
      clientId: values.clientId,
    };
    if (connectionType === VcsConnectionType.OAUTH && values.clientSecret) {
      attributes.clientSecret = values.clientSecret;
    }
    if (connectionType === VcsConnectionType.STANDALONE && values.privateKey) {
      attributes.privateKey = values.privateKey;
    }
    if (isGithubApp) {
      attributes.appWebhookEnabled = !!values.appWebhookEnabled;
      if (values.webhookSecret) {
        attributes.webhookSecret = values.webhookSecret;
      }
    }

    setSaving(true);
    try {
      await axiosInstance.patch(
        `organization/${orgid}/vcs/${vcsId}`,
        { data: { type: "vcs", id: vcsId, attributes } },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      );
      message.success("VCS provider updated");
      loadVCS();
      setMode("list");
    } catch (err: unknown) {
      message.error(`Could not update the VCS provider: ${getErrorMessage(err)}`);
    } finally {
      setSaving(false);
    }
  };

  const secretHidden =
    connectionType !== VcsConnectionType.OAUTH ||
    vcsTypeExtended === VcsTypeExtended.AZURE_DEVOPS ||
    vcsTypeExtended === VcsTypeExtended.AZURE_DEVOPS_SERVER;
  const label = vcsLabel(vcsTypeExtended);

  return (
    <Spin spinning={loading}>
      <SettingsPageHeader
        docUrl={getDocsUrl(vcsTypeExtended)}
        title="Edit VCS provider"
        description={`Update the ${label} credentials Terrakube uses to read your repositories.`}
        divider={false}
      />
      <SettingsForm form={form} onFinish={onFinish} saveLabel="Update VCS provider" saving={saving}>
        <IdField id="vcs-id" value={vcsId} copiedMessage="VCS provider ID copied" />
        <IdField id="vcs-callback-url" label="Callback URL" value={getCallbackUrl(callbackId)} />
        <Form.Item name="name" label="Name" rules={[{ required: true, message: "Name is required" }]}>
          <Input />
        </Form.Item>
        <Form.Item
          name="clientId"
          label={getClientIdName(vcsTypeExtended, connectionType)}
          rules={[{ required: true, message: `${getClientIdName(vcsTypeExtended, connectionType)} is required` }]}
        >
          <Input className="resource-mono" />
        </Form.Item>
        <Form.Item
          name="endpoint"
          label="HTTPS URL"
          hidden={fixedUrls}
          rules={[{ required: !fixedUrls, message: "HTTPS URL is required" }, { validator: validateUrlFormat }]}
        >
          <Input className="resource-mono" />
        </Form.Item>
        <Form.Item
          name="apiUrl"
          label="API URL"
          hidden={fixedUrls}
          rules={[{ required: !fixedUrls, message: "API URL is required" }, { validator: validateUrlFormat }]}
        >
          <Input className="resource-mono" />
        </Form.Item>
        <Form.Item
          name="clientSecret"
          label={getSecretIdName(vcsTypeExtended, connectionType)}
          hidden={secretHidden}
          extra="Leave blank to keep the current secret."
        >
          <Input.Password className="resource-mono" autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="privateKey"
          label={getSecretIdName(vcsTypeExtended, connectionType)}
          hidden={connectionType === VcsConnectionType.OAUTH}
          extra="Leave blank to keep the current private key."
          rules={[{ validator: validatePrivateKeyFormat }]}
        >
          <Input.TextArea className="resource-mono" placeholder="-----BEGIN PRIVATE KEY-----" rows={8} />
        </Form.Item>
        {isGithubApp && (
          <Form.Item name="appWebhookEnabled" label="Receive events via the GitHub App webhook" valuePropName="checked">
            <Switch />
          </Form.Item>
        )}
        {isGithubApp && appWebhookInitiallyEnabled && !appWebhookEnabled && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 24 }}
            title='Turning this off re-creates repository webhooks. Grant the GitHub App "Webhooks: Read and write" first.'
          />
        )}
        {isGithubApp && appWebhookEnabled && (
          <>
            <IdField
              id="vcs-app-webhook-url"
              label="Webhook URL"
              value={`${getApiOrigin()}/webhook/github-app/${vcsId}`}
            />
            <Form.Item
              name="webhookSecret"
              label="Webhook secret"
              extra={appWebhookInitiallyEnabled ? "Leave blank to keep the current secret." : undefined}
              rules={[{ required: !appWebhookInitiallyEnabled, message: "Webhook secret is required" }]}
            >
              <Input.Password className="resource-mono" autoComplete="new-password" />
            </Form.Item>
          </>
        )}
      </SettingsForm>
    </Spin>
  );
};
