import type { FormInstance } from "antd";
import { Form, Input, Select, Typography } from "antd";
import { CrudFormModal } from "@/components/modals/CrudFormModal";
import "./ResourceCard.css";

export type AddSshKeyFormValues = {
  name: string;
  description: string;
  sshType: string;
  privateKey: string;
};

type Props = {
  open: boolean;
  form: FormInstance<AddSshKeyFormValues>;
  saving?: boolean;
  onCancel: () => void;
  onSubmit: (values: AddSshKeyFormValues) => void;
};

export default function SshKeyFormModal({ open, form, saving, onCancel, onSubmit }: Props) {
  return (
    <CrudFormModal<AddSshKeyFormValues>
      open={open}
      title="Add an SSH key"
      okText="Add SSH key"
      form={form}
      formName="sshKey"
      onCancel={onCancel}
      onSubmit={onSubmit}
      confirmLoading={saving}
    >
      <Form.Item name="name" label="Name" rules={[{ required: true, message: "Name is required" }]}>
        <Input />
      </Form.Item>
      <Form.Item
        name="description"
        label="Description"
        rules={[{ required: true, message: "Description is required" }]}
      >
        <Input />
      </Form.Item>
      <Form.Item name="sshType" label="Key type" rules={[{ required: true, message: "Key type is required" }]}>
        <Select
          placeholder="Select a key type"
          options={[
            { value: "rsa", label: "RSA" },
            { value: "ed25519", label: "ED25519" },
          ]}
        />
      </Form.Item>
      <Form.Item label="Private key" htmlFor="ssh-private-key" required>
        <Typography.Paragraph type="secondary" className="modal-field-hint">
          Paste the whole private key. Generate RSA keys with{" "}
          <Typography.Text code>ssh-keygen -t rsa -m PEM</Typography.Text> so they start with{" "}
          <Typography.Text code>-----BEGIN RSA PRIVATE KEY-----</Typography.Text>.
        </Typography.Paragraph>
        <Form.Item name="privateKey" noStyle rules={[{ required: true, message: "Private key is required" }]}>
          <Input.TextArea id="ssh-private-key" className="resource-mono" rows={6} autoComplete="off" />
        </Form.Item>
      </Form.Item>
    </CrudFormModal>
  );
}
