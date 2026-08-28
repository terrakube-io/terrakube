import { InfoCircleOutlined } from "@ant-design/icons";
import type { FormInstance } from "antd";
import { Form, Input, Select, Switch } from "antd";
import { CrudFormModal } from "@/components/CrudFormModal";
import { CreateVariableForm } from "@/domain/types";

type Props = {
  open: boolean;
  mode: "create" | "edit";
  variableKey?: string;
  form: FormInstance<CreateVariableForm>;
  onCancel: () => void;
  onSubmit: (values: CreateVariableForm) => void;
};

export default function GlobalVariableFormModal({ open, mode, variableKey, form, onCancel, onSubmit }: Props) {
  return (
    <CrudFormModal<CreateVariableForm>
      open={open}
      title={mode === "edit" ? "Edit global variable " + variableKey : "Create new global variable"}
      okText="Save global variable"
      form={form}
      formName="globalVariable"
      onCancel={onCancel}
      onSubmit={onSubmit}
    >
      <Form.Item name="key" label="Key" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item name="value" label="Value" rules={[{ required: true }]}>
        <Input.TextArea rows={1} autoSize={{ maxRows: 5 }} />
      </Form.Item>
      <Form.Item name="category" label="Category" rules={[{ required: true }]}>
        <Select placeholder="Please select a category">
          <Select.Option value="TERRAFORM">Terraform Variable</Select.Option>
          <Select.Option value="ENV">Environment Variable</Select.Option>
        </Select>
      </Form.Item>
      <Form.Item name="description" rules={[{ required: true }]} label="Description">
        <Input.TextArea style={{ width: "800px" }} />
      </Form.Item>
      <Form.Item
        name="hcl"
        valuePropName="checked"
        label="HCL"
        tooltip={{
          title:
            "Parse this field as HashiCorp Configuration Language (HCL). This allows you to interpolate values at runtime.",
          icon: <InfoCircleOutlined />,
        }}
      >
        <Switch />
      </Form.Item>
      {mode === "create" && (
        <Form.Item
          name="sensitive"
          valuePropName="checked"
          label="Sensitive"
          tooltip={{
            title:
              "Sensitive variables are never shown in the UI or API. They may appear in Terraform logs if your configuration is designed to output them.",
            icon: <InfoCircleOutlined />,
          }}
        >
          <Switch />
        </Form.Item>
      )}
    </CrudFormModal>
  );
}
