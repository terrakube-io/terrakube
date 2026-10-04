import type { FormInstance } from "antd";
import { Form, Input } from "antd";
import { CrudFormModal } from "@/components/modals/CrudFormModal";
import { validateUrlFormat } from "../vcsProviders";
import "./ResourceCard.css";

export type AddAgentFormValues = {
  name: string;
  description: string;
  url: string;
};

type Props = {
  open: boolean;
  form: FormInstance<AddAgentFormValues>;
  saving?: boolean;
  onCancel: () => void;
  onSubmit: (values: AddAgentFormValues) => void;
};

export default function AgentFormModal({ open, form, saving, onCancel, onSubmit }: Props) {
  return (
    <CrudFormModal<AddAgentFormValues>
      open={open}
      title="Add an agent pool"
      okText="Add agent pool"
      form={form}
      formName="Agent"
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
      <Form.Item
        name="url"
        label="URL"
        extra="Jobs for workspaces in this pool are sent to this address."
        rules={[
          { required: true, message: "URL is required" },
          // Not antd's url type: it rejects host names without a dot, like terrakube-executor.
          { validator: validateUrlFormat },
        ]}
      >
        <Input className="resource-mono" placeholder="http://terrakube-executor:8090" />
      </Form.Item>
    </CrudFormModal>
  );
}
