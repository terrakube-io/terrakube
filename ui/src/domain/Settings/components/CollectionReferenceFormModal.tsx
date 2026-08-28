import type { FormInstance } from "antd";
import { Form, Input, Select } from "antd";
import { CrudFormModal } from "@/components/CrudFormModal";

export type ReferenceFormValues = {
  workspaceId: string;
  description: string;
};

type WorkspaceOption = {
  id: string;
  attributes: {
    name: string;
  };
};

type Props = {
  open: boolean;
  workspaces: WorkspaceOption[];
  form: FormInstance<ReferenceFormValues>;
  onCancel: () => void;
  onSubmit: (values: ReferenceFormValues) => void;
};

export default function CollectionReferenceFormModal({ open, workspaces, form, onCancel, onSubmit }: Props) {
  return (
    <CrudFormModal<ReferenceFormValues>
      open={open}
      title="Add workspace reference"
      okText="Add reference"
      form={form}
      formName="collectionReference"
      onCancel={onCancel}
      onSubmit={onSubmit}
    >
      <Form.Item name="workspaceId" label="Workspace" rules={[{ required: true }]}>
        <Select placeholder="Select a workspace">
          {workspaces.map((workspace) => (
            <Select.Option key={workspace.id} value={workspace.id}>
              {workspace.attributes.name}
            </Select.Option>
          ))}
        </Select>
      </Form.Item>
      <Form.Item name="description" label="Description" rules={[{ required: true }]}>
        <Input.TextArea rows={3} />
      </Form.Item>
    </CrudFormModal>
  );
}
