import { CopyOutlined } from "@ant-design/icons";
import { Button, ConfigProvider, Flex, Form, Input, message, theme } from "antd";
import { useId } from "react";
import { copyValue } from "@/modules/utils/clipboard";
import "./IdField.css";

type Props = {
  value: string;
  label?: string;
  copiedMessage?: string;
  id?: string;
};

// Read-only ID with a separate copy button. Not bound to the surrounding form.
export default function IdField({ value, label = "ID", copiedMessage = `${label} copied`, id }: Props) {
  const { token } = theme.useToken();
  const generatedId = useId();
  const inputId = id ?? generatedId;

  return (
    <Form.Item label={label} htmlFor={inputId}>
      <Flex gap={8} className="id-field">
        {/* Read-only: hovering the value must not look like an editable field. */}
        <ConfigProvider theme={{ components: { Input: { hoverBorderColor: token.colorBorder } } }}>
          <Input id={inputId} value={value} readOnly />
        </ConfigProvider>
        <Button
          icon={<CopyOutlined />}
          aria-label={`Copy ${label}`}
          onClick={() =>
            copyValue(value).then(
              () => message.success(copiedMessage),
              () => message.error(`Could not copy the ${label.toLowerCase()}`)
            )
          }
        />
      </Flex>
    </Form.Item>
  );
}
