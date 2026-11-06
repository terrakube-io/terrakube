import { useState, useEffect } from "react";
import { Modal, Form, Input, Select, Switch, DatePicker, Tabs, Typography } from "antd";
import dayjs, { Dayjs } from "dayjs";
import ReactMarkdown from "react-markdown";
import rehypeSanitize from "rehype-sanitize";
import { Announcement, AnnouncementScope, AnnouncementSeverity } from "@/modules/announcements/announcementTypes";

const { TextArea } = Input;
const { Text } = Typography;

export interface AnnouncementFormData {
  title: string;
  severity: AnnouncementSeverity;
  scope: AnnouncementScope;
  message: string;
  active: boolean;
  dismissible: boolean;
  displayOnLogin: boolean;
  schedule?: [Dayjs, Dayjs] | null;
}

export interface AnnouncementModalProps {
  visible: boolean;
  mode: "create" | "edit";
  initialData?: Announcement | null;
  onCancel: () => void;
  onSave: (values: AnnouncementFormData) => Promise<void>;
  loading?: boolean;
}

export const AnnouncementModal = ({
  visible,
  mode,
  initialData,
  onCancel,
  onSave,
  loading = false,
}: AnnouncementModalProps) => {
  const [form] = Form.useForm<AnnouncementFormData>();
  const [currentScope, setCurrentScope] = useState<AnnouncementScope>("ORGANIZATION");
  const [markdownMessage, setMarkdownMessage] = useState<string>("");

  useEffect(() => {
    if (visible) {
      if (initialData) {
        setCurrentScope(initialData.scope);
        setMarkdownMessage(initialData.message || "");
        form.setFieldsValue({
          title: initialData.title,
          severity: initialData.severity,
          scope: initialData.scope,
          message: initialData.message,
          active: initialData.active,
          dismissible: initialData.dismissible,
          displayOnLogin: initialData.displayOnLogin,
          schedule:
            initialData.startsAt && initialData.endsAt
              ? [dayjs(initialData.startsAt), dayjs(initialData.endsAt)]
              : undefined,
        });
      } else {
        setCurrentScope("ORGANIZATION");
        setMarkdownMessage("");
        form.setFieldsValue({
          title: "",
          severity: "info",
          scope: "ORGANIZATION",
          message: "",
          active: true,
          dismissible: true,
          displayOnLogin: false,
          schedule: undefined,
        });
      }
    }
  }, [visible, initialData, form]);

  const handleFinish = async (values: AnnouncementFormData) => {
    await onSave(values);
  };

  return (
    <Modal
      title={mode === "create" ? "New Announcement" : "Edit Announcement"}
      open={visible}
      onCancel={onCancel}
      onOk={() => form.submit()}
      confirmLoading={loading}
      width={640}
    >
      <Form form={form} layout="vertical" onFinish={handleFinish}>
        <Form.Item name="title" label="Title" rules={[{ required: true, message: "Please provide a title" }]}>
          <Input placeholder="e.g. Scheduled Maintenance" />
        </Form.Item>

        <Form.Item name="severity" label="Severity" rules={[{ required: true }]}>
          <Select
            options={[
              { label: "Info", value: "info" },
              { label: "Warning", value: "warning" },
              { label: "Critical", value: "critical" },
            ]}
          />
        </Form.Item>

        <Form.Item name="scope" label="Scope" rules={[{ required: true }]}>
          <Select
            onChange={(val) => setCurrentScope(val)}
            options={[
              { label: "Organization", value: "ORGANIZATION" },
              { label: "Platform (Global)", value: "GLOBAL" },
            ]}
          />
        </Form.Item>

        <Form.Item
          name="message"
          label="Message (Markdown supported)"
          rules={[{ required: true, message: "Please provide a message" }]}
        >
          <Tabs
            defaultActiveKey="edit"
            items={[
              {
                key: "edit",
                label: "Write",
                children: (
                  <TextArea
                    rows={5}
                    placeholder="Provide details or links (e.g. [Docs](https://docs.terrakube.io))"
                    value={markdownMessage}
                    onChange={(e) => {
                      setMarkdownMessage(e.target.value);
                      form.setFieldsValue({ message: e.target.value });
                    }}
                  />
                ),
              },
              {
                key: "preview",
                label: "Preview",
                children: (
                  <div
                    style={{
                      minHeight: 120,
                      padding: "8px 12px",
                      border: "1px solid var(--tk-border-secondary, #e8e8e8)",
                      borderRadius: 6,
                      background: "var(--tk-bg-container, #fafafa)",
                    }}
                  >
                    {markdownMessage ? (
                      <ReactMarkdown rehypePlugins={[rehypeSanitize]}>{markdownMessage}</ReactMarkdown>
                    ) : (
                      <Text type="secondary">Nothing to preview</Text>
                    )}
                  </div>
                ),
              },
            ]}
          />
        </Form.Item>

        <Form.Item name="schedule" label="Schedule (Optional)">
          <DatePicker.RangePicker showTime format="YYYY-MM-DD HH:mm:ss" style={{ width: "100%" }} />
        </Form.Item>

        <Form.Item name="active" label="Active" valuePropName="checked">
          <Switch />
        </Form.Item>

        <Form.Item name="dismissible" label="Dismissible" valuePropName="checked">
          <Switch />
        </Form.Item>

        {currentScope === "GLOBAL" && (
          <Form.Item
            name="displayOnLogin"
            label="Display on Login Screen"
            valuePropName="checked"
            extra="Show this banner anonymously to unauthenticated visitors on the login page."
          >
            <Switch />
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
};

export default AnnouncementModal;
