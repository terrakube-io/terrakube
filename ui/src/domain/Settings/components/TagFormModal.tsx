import { InfoCircleOutlined } from "@ant-design/icons";
import type { FormInstance } from "antd";
import { Form, Input } from "antd";
import { CrudFormModal } from "@/components/modals/CrudFormModal";
import { TAG_KEY_MAX_LENGTH } from "@/modules/workspaces/utils/tagLimits";

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
      title={mode === "edit" ? "Edit tag key " + tagName : "Create new tag key"}
      okText="Save tag"
      form={form}
      formName="tag"
      onCancel={onCancel}
      onSubmit={onSubmit}
    >
      <Form.Item
        name="name"
        tooltip={{
          title: "The key of a tag. Workspaces set their own value for it.",
          icon: <InfoCircleOutlined />,
        }}
        label="Key"
        rules={[{ required: true }]}
      >
        <Input maxLength={TAG_KEY_MAX_LENGTH} />
      </Form.Item>
    </CrudFormModal>
  );
}
