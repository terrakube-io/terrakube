import { Modal, Space, Form, Input, Typography, Alert, Button, Flex, Select, Tag } from "antd";
import { useState, useEffect } from "react";
import { DateTime } from "luxon";
import { formatOrdinalDate } from "@/modules/utils/dates";
import useApiRequest from "@/modules/api/useApiRequest";
import { ApiResponse } from "@/modules/api/types";
import { CreateTokenForm, CreatedToken } from "@/modules/token/types";
import "./CreatePatModal.css";

type Props = {
  open: boolean;
  onCancel: () => void;
  onCreated: () => void;
  action: (data?: CreateTokenForm) => Promise<ApiResponse<CreatedToken>>;
  shortlivedTokens?: boolean;
};

export default function CreatePatModal({ onCancel, action, onCreated, open, shortlivedTokens }: Props) {
  const [form] = Form.useForm<CreateTokenForm>();
  const [tokenValue, setTokenValue] = useState<string>();
  const [expiryDate, setExpiryDate] = useState<string>("");
  const [selectedDays, setSelectedDays] = useState<number>(30);
  const description = Form.useWatch("description", form);

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
      setExpiryDate(`This token will expire ${formatOrdinalDate(DateTime.now().plus({ days: selectedDays }))}`);
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
      width={600}
      open={open}
      title="Creating a user token"
      destroyOnHidden
      onCancel={onCancel}
      footer={
        tokenValue === undefined ? (
          <Flex justify="start" gap="small">
            <Button type="primary" loading={loading} disabled={!description?.trim()} onClick={submitForm}>
              Generate token
            </Button>
            <Button onClick={onCancel}>Cancel</Button>
          </Flex>
        ) : null
      }
    >
      {tokenValue === undefined && (
        <Space className="content" orientation="vertical">
          {error && <Alert type="error" banner title={error?.message} />}
          <Form name="tokens" form={form} layout="vertical" disabled={loading} initialValues={{ description: "" }}>
            <Form.Item
              label={
                <span>
                  Description <Tag className="required-badge">Required</Tag>
                </span>
              }
              required={false}
            >
              <Typography.Text type="secondary" className="field-hint">
                To help you identify this token later.
              </Typography.Text>
              <Form.Item
                name="description"
                noStyle
                rules={[{ required: true, whitespace: true, message: "Description is required" }]}
              >
                <Input.TextArea placeholder="e.g. API testing" rows={3} aria-label="Description" />
              </Form.Item>
            </Form.Item>

            <Form.Item label="Expiration">
              <Flex align="center" gap="middle" wrap>
                <Form.Item name="expiration" initialValue={30} noStyle>
                  <Select
                    className="expiry-select"
                    onChange={handleDaysChange}
                    value={selectedDays}
                    aria-label="Expiration"
                  >
                    <Select.Option value={30}>30 days</Select.Option>
                    <Select.Option value={60}>60 days</Select.Option>
                    <Select.Option value={90}>90 days</Select.Option>
                    <Select.Option value={120}>120 days</Select.Option>
                    <Select.Option value={365}>1 year</Select.Option>
                    {!shortlivedTokens && <Select.Option value={0}>Never</Select.Option>}
                  </Select>
                </Form.Item>
                <Typography.Text type="secondary">{expiryDate}</Typography.Text>
              </Flex>
            </Form.Item>
          </Form>
        </Space>
      )}

      {tokenValue !== undefined && (
        <Space className="content" orientation="vertical" size="middle">
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
            title="Terrakube will not display this token again, so store it securely."
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
