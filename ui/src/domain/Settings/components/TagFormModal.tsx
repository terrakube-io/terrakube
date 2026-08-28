import { InfoCircleOutlined } from "@ant-design/icons";
import type { FormInstance } from "antd";
import { Form, Input } from "antd";
import { CrudFormModal } from "@/components/CrudFormModal";

export type TagFormValues = {
  name: string;
};

type Props = {
  open: boolean;
  mode: "create" | "edit";
  tagName?: string;
  form: FormInstance<TagFormValues>;
  onCancel: () => void;
  onSubmit: (values: TagFormValues) => void;
};

export default function TagFormModal({ open, mode, tagName, form, onCancel, onSubmit }: Props) {
  return (
    <CrudFormModal<TagFormValues>
      open={open}
      title={mode === "edit" ? "Edit tag " + tagName : "Create new tag"}
      okText="Save tag"
      form={form}
      formName="tag"
      onCancel={onCancel}
      onSubmit={onSubmit}
    >
      <Form.Item
        name="name"
        tooltip={{
          title: "Must be a valid tag name",
          icon: <InfoCircleOutlined />,
        }}
        label="Name"
        rules={[{ required: true }]}
      >
        <Input />
      </Form.Item>
    </CrudFormModal>
  );
}
