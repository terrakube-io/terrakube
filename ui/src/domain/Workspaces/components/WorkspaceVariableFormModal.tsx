import type { FormInstance } from "antd";
import { Typography } from "antd";
import { CrudFormModal } from "@/components/CrudFormModal";
import { VariableFormFields } from "@/components/VariableFormFields";
import { CreateVariableForm, VariableCategory } from "@/domain/types";

const validateMessages = {
  required: "${label} is required!",
};

type Props = {
  open: boolean;
  mode: "create" | "edit";
  variableName?: string;
  category: VariableCategory | null;
  onCategoryChange: (value: VariableCategory) => void;
  form: FormInstance<CreateVariableForm>;
  onCancel: () => void;
  onSubmit: (values: CreateVariableForm) => void;
};

export default function WorkspaceVariableFormModal({
  open,
  mode,
  variableName,
  category,
  onCategoryChange,
  form,
  onCancel,
  onSubmit,
}: Props) {
  return (
    <CrudFormModal<CreateVariableForm>
      open={open}
      title={mode === "edit" ? "Edit variable " + variableName : "Add variable"}
      okText="Save variable"
      form={form}
      formName="create-org"
      onCancel={onCancel}
      onSubmit={onSubmit}
      validateMessages={validateMessages}
    >
      <Typography.Title level={5} style={{ margin: "0 0 15px 0" }}>
        Select variable category
      </Typography.Title>

      <VariableFormFields
        category={category ?? undefined}
        onCategoryChange={(value) => onCategoryChange(value as VariableCategory)}
      />
    </CrudFormModal>
  );
}
