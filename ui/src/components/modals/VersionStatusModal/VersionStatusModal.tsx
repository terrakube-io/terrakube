import { Alert, Form, Input, Modal, Radio } from "antd";
import { useEffect, useState } from "react";
import { VersionKind, VersionStatus } from "./versionStatus";

type StatusValue = "active" | "deprecated" | "removed";

type FormValues = {
  status: StatusValue;
  deprecationMessage?: string;
};

type AlertProps = {
  version: string;
  status: VersionStatus;
  // Suggested when the maintainers left no message of their own.
  upgradeTo?: string;
};

export function VersionStatusAlert({ version, status, upgradeTo }: AlertProps) {
  if (!status.removed && !status.deprecated) return null;
  const suggestion = upgradeTo && upgradeTo !== version ? `Use version ${upgradeTo} instead.` : undefined;
  return (
    <Alert
      type={status.removed ? "error" : "warning"}
      showIcon
      title={
        status.removed
          ? `Version ${version} has been removed and is no longer served by the registry.`
          : `Version ${version} is deprecated.`
      }
      description={status.deprecationMessage || suggestion}
    />
  );
}

const MESSAGE_HELP: Record<VersionKind, string> = {
  module: "Shown on this page, for example a removal date or upgrade instructions.",
  provider:
    "Shown on this page and as a warning in terraform init for everyone using this provider, for example a removal date or upgrade instructions.",
};

const REMOVE_WARNING: Record<VersionKind, string> = {
  module:
    "Terraform runs that require this version will fail. The registry caches module versions, so it keeps serving this version for a few minutes. You can make it active again at any time.",
  provider: "Terraform runs that require this version will fail. You can make it active again at any time.",
};

type Props = {
  open: boolean;
  version: string;
  kind: VersionKind;
  status: VersionStatus;
  onCancel: () => void;
  onSave: (status: Required<VersionStatus>) => Promise<void>;
};

export default function VersionStatusModal({ open, version, kind, status, onCancel, onSave }: Props) {
  const [form] = Form.useForm<FormValues>();
  const [saving, setSaving] = useState(false);
  const selected = Form.useWatch("status", form);
  const removing = selected === "removed" && !status.removed;

  useEffect(() => {
    if (open) {
      form.setFieldsValue({
        status: status.removed ? "removed" : status.deprecated ? "deprecated" : "active",
        deprecationMessage: status.deprecationMessage ?? "",
      });
    }
    // Reset only when the modal opens, so a parent re-render cannot discard what the user typed.
  }, [open]);

  const handleOk = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await onSave({
        deprecated: values.status === "deprecated",
        removed: values.status === "removed",
        deprecationMessage: values.status === "active" ? null : values.deprecationMessage?.trim() || null,
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title={`Change status of version ${version}`}
      open={open}
      onCancel={onCancel}
      onOk={handleOk}
      okText={removing ? `Remove version ${version}` : "Save"}
      okButtonProps={{ danger: removing }}
      confirmLoading={saving}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item name="status" label="Status">
          <Radio.Group aria-label="Status">
            <Radio value="active">Active</Radio>
            <Radio value="deprecated">Deprecated</Radio>
            <Radio value="removed">Removed</Radio>
          </Radio.Group>
        </Form.Item>
        {selected && selected !== "active" && (
          <>
            <Form.Item name="deprecationMessage" label="Message" extra={MESSAGE_HELP[kind]} rules={[{ max: 1024 }]}>
              <Input.TextArea rows={3} maxLength={1024} showCount />
            </Form.Item>
            {selected === "removed" && <Alert type="error" showIcon title={REMOVE_WARNING[kind]} />}
          </>
        )}
      </Form>
    </Modal>
  );
}
