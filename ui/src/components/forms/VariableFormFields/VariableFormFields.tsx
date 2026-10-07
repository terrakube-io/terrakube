import { Checkbox, Form, Input, RadioChangeEvent } from "antd";
import RadioChoices from "@/components/settings/RadioChoices";

type Props = {
  category?: string;
  onCategoryChange?: (value: string) => void;
};

export default function VariableFormFields({ category, onCategoryChange }: Props) {
  const radioGroupProps = onCategoryChange
    ? { value: category, onChange: (e: RadioChangeEvent) => onCategoryChange(e.target.value) }
    : {};

  return (
    <>
      <Form.Item name="category" label="Category" rules={[{ required: true, message: "Choose a category" }]}>
        <RadioChoices
          aria-label="Category"
          {...radioGroupProps}
          options={[
            {
              value: "TERRAFORM",
              label: "Terraform variable",
              help: "Passed to the configuration as an input variable; the key must match a variable declaration.",
            },
            {
              value: "ENV",
              label: "Environment variable",
              help: "Set in the environment of every run, for providers and scripts.",
            },
          ]}
        />
      </Form.Item>

      <Form.Item name="key" label="Key" rules={[{ required: true, message: "Enter a key" }]}>
        <Input />
      </Form.Item>

      <Form.Item name="value" label="Value" rules={[{ required: true, message: "Enter a value" }]}>
        <Input.TextArea autoSize={{ minRows: 3, maxRows: 6 }} />
      </Form.Item>

      <Form.Item
        name="hcl"
        valuePropName="checked"
        className="settings-checkbox-item"
        extra="The value is parsed as HCL, so it can hold lists, maps and expressions."
      >
        <Checkbox>HCL</Checkbox>
      </Form.Item>

      <Form.Item
        name="sensitive"
        valuePropName="checked"
        className="settings-checkbox-item"
        extra="Hidden in the UI and API after saving, though it can still appear in run logs."
      >
        <Checkbox>Sensitive</Checkbox>
      </Form.Item>

      <Form.Item name="description" label="Description">
        <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} placeholder="Optional" />
      </Form.Item>
    </>
  );
}
