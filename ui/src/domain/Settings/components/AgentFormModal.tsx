import type { FormInstance } from "antd";
import { Form, Input } from "antd";
import { CrudFormModal } from "@/components/CrudFormModal";

export type AddAgentFormValues = {
  name?: string;
} & UpdateAgentFormValues;

export type UpdateAgentFormValues = {
  description: string;
  url: string;
};

type Props = {
  open: boolean;
  mode: "create" | "edit";
  agentName?: string;
  form: FormInstance<AddAgentFormValues | UpdateAgentFormValues>;
  onCancel: () => void;
  onSubmit: (values: AddAgentFormValues | UpdateAgentFormValues) => void;
};

export default function AgentFormModal({ open, mode, agentName, form, onCancel, onSubmit }: Props) {
  return (
    <CrudFormModal<AddAgentFormValues | UpdateAgentFormValues>
      open={open}
      title={mode === "edit" ? "Edit Terrakube Agent  " + agentName : "Add a new Terrakube Agent"}
      okText="Save Terrakube Agent "
      form={form}
      formName="Agent"
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
      <Form.Item name="url" label="Url" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
    </CrudFormModal>
  );
}
