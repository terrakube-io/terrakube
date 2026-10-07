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
      title={mode === "edit" ? `Edit tag key ${tagName ?? ""}` : "Create a tag key"}
      okText={mode === "edit" ? "Save changes" : "Create tag key"}
      form={form}
      formName="tag"
      onCancel={onCancel}
      onSubmit={onSubmit}
    >
      <Form.Item
        name="name"
        label="Key"
        extra={
          mode === "edit"
            ? "The new key shows on every workspace that uses it."
            : "Workspaces set their own value for this key."
        }
        rules={[{ required: true, whitespace: true, message: "Enter a tag key" }]}
      >
        <Input maxLength={TAG_KEY_MAX_LENGTH} />
      </Form.Item>
    </CrudFormModal>
  );
}
