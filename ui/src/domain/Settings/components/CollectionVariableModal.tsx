import type { FormInstance } from "antd";
import { CrudFormModal } from "@/components/modals/CrudFormModal";
import { VariableFormFields } from "@/components/forms/VariableFormFields";

export type CollectionVariableFormValues = {
  key: string;
  value?: string;
  hcl: boolean;
  category: string;
  description: string;
  sensitive: boolean;
};

type Props = {
  open: boolean;
  mode: "create" | "edit";
  form: FormInstance<CollectionVariableFormValues>;
  confirmLoading?: boolean;
  onCancel: () => void;
  onSubmit: (values: CollectionVariableFormValues) => void;
};

export default function CollectionVariableModal({ open, mode, form, confirmLoading, onCancel, onSubmit }: Props) {
  return (
    <CrudFormModal<CollectionVariableFormValues>
      open={open}
      title={mode === "edit" ? "Edit variable" : "Add variable"}
      okText={mode === "edit" ? "Save changes" : "Add variable"}
      form={form}
      formName="collectionVariable"
      onCancel={onCancel}
      onSubmit={onSubmit}
      confirmLoading={confirmLoading}
      initialValues={{ category: "TERRAFORM", hcl: false, sensitive: false }}
    >
      <VariableFormFields />
    </CrudFormModal>
  );
}
