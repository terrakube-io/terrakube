import { Modal, Space, Form, Input, Typography, Alert, Button, Flex, Select, Tag } from "antd";
import { useState, useEffect } from "react";
import { DateTime } from "luxon";
import useApiRequest from "@/modules/api/useApiRequest";
import { CreateTokenForm, CreatedToken } from "@/modules/token/types";
import "./CreatePatModal.css";

type Props = {
  visible: boolean;
  onCancel: () => void;
  onCreated: () => void;
  action: (data?: CreateTokenForm) => Promise<ApiResponse<CreatedToken>>;
};

export default function CreatePatModal({ onCancel, action, onCreated, visible }: Props) {
  const [form] = Form.useForm<CreateTokenForm>();
  const [tokenValue, setTokenValue] = useState<string>();
  const [expiryDate, setExpiryDate] = useState<string>("");
  const [selectedDays, setSelectedDays] = useState<number>(30);

  const { loading, execute, error } = useApiRequest({
    showErrorAsNotification: false,
    action: action,
    onReturn: (data) => {
      setTokenValue(data.token);
      form.resetFields();
    },
    requestErrorInfo: {
      title: "Failed to create token",
      message: "Failed to create token. Please try again",
    },
  });

  useEffect(() => {
    if (selectedDays === 0) {
      setExpiryDate("Never expires");
    } else {
      const date = DateTime.now().plus({ days: selectedDays });
      setExpiryDate(`This token will expire on ${date.toFormat("MMMM d, yyyy")}`);
    }
  }, [selectedDays]);

  async function submitForm() {
    setTokenValue(undefined);
    const formValues = await form.validateFields();
    // Map the selected days to the form values expected by the API
    // The API expects days, hours, minutes. We'll just set days.
    const apiValues: CreateTokenForm = {
      description: formValues.description,
      days: selectedDays,
      hours: 0,
      minutes: 0,
    };
    await execute(apiValues);
  }

  const handleDaysChange = (value: number) => {
    setSelectedDays(value);
  };

  return (
    <Modal
      className="create-pat-modal"
      open={visible}
      title="Creating a user token"
      destroyOnClose
      onCancel={onCancel}
      footer={
        tokenValue === undefined ? (
          <Flex justify="end" gap="small">
            <Button onClick={onCancel}>Cancel</Button>
            <Button type="primary" loading={loading} onClick={submitForm}>
              Generate token
            </Button>
          </Flex>
        ) : null
      }
    >
      {tokenValue === undefined && (
        <Space className="content" direction="vertical">
          {error && <Alert type="error" banner message={error?.message} />}
          <Form name="tokens" form={form} layout="vertical" disabled={loading} initialValues={{ description: "" }}>
            <Form.Item
              name="description"
              label={
                <span>
                  Description <Tag className="required-badge">Required</Tag>
                </span>
              }
              help="To help you identify this token later."
              rules={[{ required: true, message: "Description is required" }]}
            >
              <Input placeholder="e.g. API testing" />
            </Form.Item>

            <Form.Item label="Expiration" name="expiration" initialValue={30}>
              <Select onChange={handleDaysChange} value={selectedDays}>
                <Select.Option value={30}>30 days</Select.Option>
                <Select.Option value={60}>60 days</Select.Option>
                <Select.Option value={90}>90 days</Select.Option>
                <Select.Option value={120}>120 days</Select.Option>
                <Select.Option value={365}>1 year</Select.Option>
                <Select.Option value={0}>Never</Select.Option>
              </Select>
            </Form.Item>

            <Typography.Text type="secondary" className="expiry-text">
              {expiryDate}
            </Typography.Text>
          </Form>
        </Space>
      )}

      {tokenValue !== undefined && (
        <Space className="content" direction="vertical" size="middle">
          <Typography.Text>
            Your new API token is displayed below. Treat this token like a password, as it can be used to access your
            account without a username, password, or two-factor authentication.
          </Typography.Text>

          <div className="token-display">
            <Typography.Paragraph className="created-token" copyable>
              {tokenValue}
            </Typography.Paragraph>
          </div>

          <Alert
            message="Terrakube will not display this token again, so store it securely."
            type="warning"
            showIcon
            className="warning-banner"
          />

          <Flex justify="end">
            <Button
              type="primary"
              onClick={() => {
                setTokenValue(undefined);
                onCreated();
                onCancel();
              }}
            >
              Close
            </Button>
          </Flex>
        </Space>
      )}
    </Modal>
  );
}
