import { Modal, Form, Input, Radio, Alert, Button, Flex, Typography, message } from "antd";
import { useState } from "react";
import { importProviderFromPrivateRegistry } from "./providerService";
import { ProviderSourceType } from "./types";

type Props = {
  orgId: string;
  open: boolean;
  onCancel: () => void;
  onImported: () => void;
};

type FormValues = {
  name: string;
  description?: string;
  registryHost?: string;
  registryNamespace?: string;
  repositoryUrl?: string;
  repositoryVersions?: string;
  gpgKeyId?: string;
  gpgAsciiArmor?: string;
  registryToken?: string;
};

/**
 * Imports a custom provider from a private Terraform registry or a repository release page.
 * The provider is created with imported=true; the backend refresh job then pulls the versions,
 * platforms and shasums asynchronously.
 */
export default function ImportProviderModal({ orgId, open, onCancel, onImported }: Props) {
  const [form] = Form.useForm<FormValues>();
  const [sourceType, setSourceType] = useState<ProviderSourceType>("TERRAFORM_REGISTRY");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>();

  const reset = () => {
    form.resetFields();
    setSourceType("TERRAFORM_REGISTRY");
    setError(undefined);
  };

  const handleCancel = () => {
    reset();
    onCancel();
  };

  const submit = async () => {
    setError(undefined);
    const values = await form.validateFields();
    setLoading(true);
    try {
      if (sourceType === "TERRAFORM_REGISTRY") {
        await importProviderFromPrivateRegistry(orgId, {
          sourceType: "TERRAFORM_REGISTRY",
          name: values.name.trim(),
          registryNamespace: values.registryNamespace!.trim(),
          registryHost: values.registryHost?.trim() || undefined,
          registryToken: values.registryToken?.trim() || undefined,
          description: values.description?.trim(),
        });
      } else {
        await importProviderFromPrivateRegistry(orgId, {
          sourceType: "REPOSITORY",
          name: values.name.trim(),
          repositoryUrl: values.repositoryUrl!.trim(),
          repositoryVersions: values.repositoryVersions!.trim(),
          gpgKeyId: values.gpgKeyId?.trim() || undefined,
          gpgAsciiArmor: values.gpgAsciiArmor?.trim() || undefined,
          registryToken: values.registryToken?.trim() || undefined,
        });
      }
      message.success("Provider import started. Versions will appear shortly.");
      reset();
      onImported();
      onCancel();
    } catch (e: any) {
      setError(e?.response?.data?.errors?.[0]?.detail || e?.message || "Failed to import provider");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Import provider from a private registry"
      destroyOnClose
      onCancel={handleCancel}
      footer={
        <Flex justify="end" gap="small">
          <Button onClick={handleCancel}>Cancel</Button>
          <Button type="primary" loading={loading} onClick={submit}>
            Import
          </Button>
        </Flex>
      }
    >
      {error && <Alert type="error" banner message={error} style={{ marginBottom: 16 }} />}
      <Form form={form} layout="vertical" disabled={loading}>
        <Form.Item label="Source">
          <Radio.Group
            value={sourceType}
            onChange={(e) => setSourceType(e.target.value)}
            optionType="button"
            buttonStyle="solid"
          >
            <Radio.Button value="TERRAFORM_REGISTRY">Terraform registry</Radio.Button>
            <Radio.Button value="REPOSITORY">Repository / web page</Radio.Button>
          </Radio.Group>
        </Form.Item>

        <Form.Item
          name="name"
          label="Provider name"
          help="The provider type, e.g. 'random' or 'mycloud'."
          rules={[{ required: true, message: "Provider name is required" }]}
        >
          <Input placeholder="mycloud" />
        </Form.Item>

        {sourceType === "TERRAFORM_REGISTRY" ? (
          <>
            <Form.Item
              name="registryHost"
              label="Registry host"
              help="Leave empty to use the public registry.terraform.io. Example: gitlab.example.com, artifactory.example.com."
            >
              <Input placeholder="registry.example.com" />
            </Form.Item>
            <Form.Item
              name="registryNamespace"
              label="Namespace"
              help="The provider namespace/organization in the registry."
              rules={[{ required: true, message: "Namespace is required" }]}
            >
              <Input placeholder="acme" />
            </Form.Item>
          </>
        ) : (
          <>
            <Form.Item
              name="repositoryUrl"
              label="Release base URL"
              help="Base URL hosting goreleaser style assets. Use {version} where the version/tag appears, e.g. https://github.com/acme/terraform-provider-mycloud/releases/download/v{version}"
              rules={[{ required: true, message: "Release base URL is required" }]}
            >
              <Input placeholder="https://github.com/acme/terraform-provider-mycloud/releases/download/v{version}" />
            </Form.Item>
            <Form.Item
              name="repositoryVersions"
              label="Versions"
              help="Comma separated list of versions to import, e.g. 1.0.0, 1.1.0"
              rules={[{ required: true, message: "At least one version is required" }]}
            >
              <Input placeholder="1.0.0, 1.1.0" />
            </Form.Item>
            <Form.Item
              name="gpgKeyId"
              label="GPG key id"
              help="Key id used to sign the SHA256SUMS file (required by Terraform to verify the provider)."
            >
              <Input placeholder="51852D87348FFC4C" />
            </Form.Item>
            <Form.Item name="gpgAsciiArmor" label="GPG public key (ASCII armored)">
              <Input.TextArea rows={3} placeholder="-----BEGIN PGP PUBLIC KEY BLOCK-----" />
            </Form.Item>
          </>
        )}

        <Form.Item
          name="registryToken"
          label="Bearer token"
          help="Optional. Sent as 'Authorization: Bearer ...' when downloading from a private source."
        >
          <Input.Password placeholder="Leave empty for public sources" autoComplete="new-password" />
        </Form.Item>

        <Typography.Text type="secondary">
          Versions are imported in the background and may take a moment to appear.
        </Typography.Text>
      </Form>
    </Modal>
  );
}
