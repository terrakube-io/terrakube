import { InfoCircleOutlined } from "@ant-design/icons";
import { Checkbox, Form, Input, Radio, Typography } from "antd";

type Props = {
  category?: string;
  onCategoryChange?: (value: string) => void;
};

export default function VariableFormFields({ category, onCategoryChange }: Props) {
  const radioGroupProps = onCategoryChange
    ? { value: category, onChange: (e: any) => onCategoryChange(e.target.value) }
    : {};

  return (
    <>
      <Form.Item name="category" rules={[{ required: true, message: "Please select a variable category" }]}>
        <Radio.Group {...radioGroupProps}>
          <div style={{ display: "flex", flexDirection: "column", gap: "15px" }}>
            <Radio value="TERRAFORM" style={{ display: "flex", alignItems: "flex-start" }}>
              <div>
                <div>Terraform variable</div>
                <Typography.Text type="secondary">
                  These variables should match the declarations in your configuration. Click the HCL box to use
                  interpolation or set a non-string value.
                </Typography.Text>
              </div>
            </Radio>

            <Radio value="ENV" style={{ display: "flex", alignItems: "flex-start" }}>
              <div>
                <div>Environment variable</div>
                <Typography.Text type="secondary">
                  These variables are available in the Terraform runtime environment.
                </Typography.Text>
              </div>
            </Radio>
          </div>
        </Radio.Group>
      </Form.Item>

      <Form.Item name="key" label="Key" rules={[{ required: true }]}>
        <Input placeholder="Enter key name" />
      </Form.Item>

      <Form.Item name="value" label="Value" rules={[{ required: true }]}>
        <Input.TextArea rows={3} autoSize={{ minRows: 3, maxRows: 6 }} placeholder="Enter variable value" />
      </Form.Item>

      <div style={{ display: "flex", gap: "30px", marginBottom: "15px" }}>
        <Form.Item
          name="hcl"
          valuePropName="checked"
          style={{ marginBottom: 0 }}
          tooltip={{
            title:
              "Parse this field as HashiCorp Configuration Language (HCL). This allows you to interpolate values at runtime.",
            icon: <InfoCircleOutlined />,
          }}
        >
          <Checkbox>HCL</Checkbox>
        </Form.Item>

        <Form.Item
          name="sensitive"
          valuePropName="checked"
          style={{ marginBottom: 0 }}
          tooltip={{
            title:
              "Sensitive variables are never shown in the UI or API. They may appear in Terraform logs if your configuration is designed to output them.",
            icon: <InfoCircleOutlined />,
          }}
        >
          <Checkbox>Sensitive</Checkbox>
        </Form.Item>
      </div>

      <Form.Item name="description" label="Description">
        <Input.TextArea placeholder="Description (optional)" style={{ width: "100%" }} rows={3} />
      </Form.Item>
    </>
  );
}
