import { InfoCircleOutlined } from "@ant-design/icons";
import type { FormInstance } from "antd";
import { Form, Input, Select } from "antd";
import { CrudFormModal } from "@/components/modals/CrudFormModal";

export type AddSshKeyFormValues = {
  name: string;
} & UpdateSshKeyFormValues;

export type UpdateSshKeyFormValues = {
  description: string;
  sshType: string;
  privateKey: string;
};

type Props = {
  open: boolean;
  mode: "create" | "edit";
  sshKeyName?: string;
  form: FormInstance<AddSshKeyFormValues | UpdateSshKeyFormValues>;
  onCancel: () => void;
  onSubmit: (values: AddSshKeyFormValues | UpdateSshKeyFormValues) => void;
};

export default function SshKeyFormModal({ open, mode, sshKeyName, form, onCancel, onSubmit }: Props) {
  return (
    <CrudFormModal<AddSshKeyFormValues | UpdateSshKeyFormValues>
      open={open}
      title={mode === "edit" ? "Edit Private SSH Key " + sshKeyName : "Add a new Private SSH Key"}
      okText="Save SSH Key"
      form={form}
      formName="sshKey"
      onCancel={onCancel}
      onSubmit={onSubmit}
      width="650px"
    >
      {mode === "create" && (
        <Form.Item name="name" label="Name" rules={[{ required: true }]}>
          <Input />
        </Form.Item>
      )}

      <Form.Item name="description" label="Description" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item name="sshType" label="SSH Type" rules={[{ required: true }]}>
        <Select placeholder="Please select a ssh type">
          <Select.Option value="rsa">RSA</Select.Option>
          <Select.Option value="ed25519">ED25519</Select.Option>
        </Select>
      </Form.Item>
      <Form.Item
        name="privateKey"
        rules={[{ required: true }]}
        label="Private SSH Key"
        tooltip={{
          title:
            "Generate a new key with ssh-keygen -t rsa -m PEM and make sure the private key starts with -----BEGIN RSA PRIVATE KEY-----",
          icon: <InfoCircleOutlined />,
        }}
      >
        <Input.TextArea rows={6} />
      </Form.Item>
    </CrudFormModal>
  );
}
