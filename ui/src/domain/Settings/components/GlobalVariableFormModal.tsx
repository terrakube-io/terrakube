import type { FormInstance } from "antd";
import { Checkbox, Form, Input } from "antd";
import { CrudFormModal } from "@/components/modals/CrudFormModal";
import { RadioChoices } from "@/components/settings/RadioChoices";
import "../TeamsTagsVariables.css";
import { CreateVariableForm } from "@/domain/types";

type Props = {
  open: boolean;
  mode: "create" | "edit";
  variableKey?: string;
  existingKeys?: string[];
  form: FormInstance<CreateVariableForm>;
  onCancel: () => void;
  onSubmit: (values: CreateVariableForm) => void;
};

export default function GlobalVariableFormModal({
  open,
  mode,
  variableKey,
  existingKeys,
  form,
  onCancel,
  onSubmit,
}: Props) {
  return (
    <CrudFormModal<CreateVariableForm>
      open={open}
      title={mode === "edit" ? `Edit global variable ${variableKey ?? ""}` : "Create a global variable"}
      okText={mode === "edit" ? "Save changes" : "Create variable"}
      initialValues={{ category: "TERRAFORM" }}
      form={form}
      formName="globalVariable"
      onCancel={onCancel}
      onSubmit={onSubmit}
    >
      <Form.Item
        name="key"
        label="Key"
        rules={[
          { required: true, message: "Enter a key" },
          {
            validator: (_, value) => {
              if (!value) return Promise.resolve();
              const trimmed = value.trim();
              if (mode === "create" && existingKeys?.includes(trimmed)) {
                return Promise.reject(new Error("A global variable with this key already exists"));
              }
              if (mode === "edit" && trimmed !== variableKey && existingKeys?.includes(trimmed)) {
                return Promise.reject(new Error("A global variable with this key already exists"));
              }
              return Promise.resolve();
            },
          },
        ]}
      >
        <Input />
      </Form.Item>
      <Form.Item name="value" label="Value" rules={[{ required: true, message: "Enter a value" }]}>
        <Input.TextArea rows={1} autoSize={{ maxRows: 5 }} />
      </Form.Item>
      <Form.Item name="category" label="Category" rules={[{ required: true, message: "Choose a category" }]}>
        <RadioChoices
          aria-label="Category"
          options={[
            {
              value: "TERRAFORM",
              label: "Terraform variable",
              help: "Passed to the configuration as an input variable.",
            },
            {
              value: "ENV",
              label: "Environment variable",
              help: "Set in the environment of every run, for providers and scripts.",
            },
          ]}
        />
      </Form.Item>
      <Form.Item name="description" label="Description" rules={[{ required: true, message: "Enter a description" }]}>
        <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} />
      </Form.Item>
      <Form.Item
        name="hcl"
        valuePropName="checked"
        className="settings-checkbox-item"
        extra="The value is parsed as HCL, so it can hold lists, maps and expressions."
      >
        <Checkbox>HCL</Checkbox>
      </Form.Item>
      {mode === "create" && (
        <Form.Item
          name="sensitive"
          valuePropName="checked"
          className="settings-checkbox-item"
          extra="Hidden in the UI and API after saving, though it can still appear in run logs; this cannot be changed later."
        >
          <Checkbox>Sensitive</Checkbox>
        </Form.Item>
      )}
    </CrudFormModal>
  );
}
