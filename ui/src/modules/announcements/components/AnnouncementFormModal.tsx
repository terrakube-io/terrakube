import {
  Checkbox,
  DatePicker,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Space,
  Switch,
  Tabs,
  Typography,
  message,
} from "antd";
import dayjs, { type Dayjs } from "dayjs";
import { useContext, useEffect, useState } from "react";
import { getErrorMessage } from "@/config/axiosConfig";
import { OrganizationSummaryContext } from "@/modules/organizations/OrganizationSummaryStore";
import { createAnnouncement, updateAnnouncement } from "../announcementService";
import { formatUtcTimestamp } from "../announcementUtils";
import type {
  AnnouncementItem,
  AnnouncementPayload,
  AnnouncementScheduleType,
  AnnouncementScope,
  AnnouncementSeverity,
} from "../types";
import { AnnouncementMarkdown } from "./AnnouncementMarkdown";

const { TextArea } = Input;
const { RangePicker } = DatePicker;

interface Props {
  open: boolean;
  mode: "create" | "edit";
  initialData?: AnnouncementItem | null;
  defaultScope: AnnouncementScope;
  defaultOrganizationId?: string | null;
  allowGlobalScope: boolean;
  isSuperUser: boolean;
  onSuccess: () => Promise<void> | void;
  onCancel: () => void;
}

interface FormValues {
  scope: AnnouncementScope;
  organizationId?: string;
  title: string;
  message: string;
  severity: AnnouncementSeverity;
  scheduleType: AnnouncementScheduleType;
  scheduleRange?: [Dayjs, Dayjs];
  immediateEndsAt?: Dayjs | null;
  active: boolean;
  dismissible: boolean;
  displayOnLogin: boolean;
}

export function AnnouncementFormModal({
  open,
  mode,
  initialData,
  defaultScope,
  defaultOrganizationId,
  allowGlobalScope,
  isSuperUser,
  onSuccess,
  onCancel,
}: Props) {
  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [messageTab, setMessageTab] = useState("write");
  const orgContext = useContext(OrganizationSummaryContext);
  const organizations = orgContext?.organizations ?? [];

  const watchedScope = Form.useWatch("scope", form) ?? defaultScope;
  const watchedScheduleType = Form.useWatch("scheduleType", form) ?? "IMMEDIATE";
  const watchedMessage = Form.useWatch("message", form) ?? "";
  const watchedRange = Form.useWatch("scheduleRange", form);
  const watchedImmediateEnd = Form.useWatch("immediateEndsAt", form);

  useEffect(() => {
    if (!open) {
      return;
    }
    setMessageTab("write");
    if (mode === "edit" && initialData) {
      form.setFieldsValue({
        scope: initialData.scope,
        organizationId: initialData.organizationId ?? undefined,
        title: initialData.title,
        message: initialData.message,
        severity: initialData.severity,
        scheduleType: initialData.scheduleType,
        scheduleRange:
          initialData.scheduleType === "SCHEDULED" && initialData.startsAt && initialData.endsAt
            ? [dayjs(initialData.startsAt), dayjs(initialData.endsAt)]
            : undefined,
        immediateEndsAt:
          initialData.scheduleType === "IMMEDIATE" && initialData.endsAt ? dayjs(initialData.endsAt) : null,
        active: initialData.active,
        dismissible: initialData.dismissible,
        displayOnLogin: initialData.scope === "GLOBAL" ? initialData.displayOnLogin : false,
      });
    } else {
      form.setFieldsValue({
        scope: allowGlobalScope ? defaultScope : "ORGANIZATION",
        organizationId:
          (allowGlobalScope ? defaultScope : "ORGANIZATION") === "ORGANIZATION"
            ? (defaultOrganizationId ?? undefined)
            : undefined,
        title: "",
        message: "",
        severity: "INFO",
        scheduleType: "IMMEDIATE",
        scheduleRange: undefined,
        immediateEndsAt: null,
        active: true,
        dismissible: true,
        displayOnLogin: false,
      });
    }
  }, [open, mode, initialData, defaultScope, defaultOrganizationId, allowGlobalScope, form]);

  const handleScopeChange = (nextScope: AnnouncementScope) => {
    if (nextScope === "GLOBAL") {
      form.setFieldsValue({ organizationId: undefined });
    } else {
      form.setFieldsValue({
        displayOnLogin: false,
        organizationId: form.getFieldValue("organizationId") || defaultOrganizationId || organizations[0]?.id,
      });
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);

      const effectiveScope: AnnouncementScope = allowGlobalScope ? values.scope : "ORGANIZATION";
      const effectiveOrgId =
        effectiveScope === "ORGANIZATION" ? (values.organizationId ?? defaultOrganizationId ?? null) : null;

      let startsAt: string | null = null;
      let endsAt: string | null = null;
      if (values.scheduleType === "SCHEDULED" && values.scheduleRange) {
        startsAt = values.scheduleRange[0].toISOString();
        endsAt = values.scheduleRange[1].toISOString();
      } else if (values.scheduleType === "IMMEDIATE" && values.immediateEndsAt) {
        endsAt = values.immediateEndsAt.toISOString();
      }

      const payload: AnnouncementPayload = {
        title: values.title.trim(),
        message: values.message,
        severity: values.severity,
        scope: effectiveScope,
        scheduleType: values.scheduleType,
        organizationId: effectiveOrgId,
        startsAt,
        endsAt,
        active: values.active,
        dismissible: values.dismissible,
        displayOnLogin: effectiveScope === "GLOBAL" ? Boolean(values.displayOnLogin) : false,
      };

      if (mode === "create") {
        await createAnnouncement(payload);
        message.success("Announcement created");
      } else if (initialData) {
        const scopeOrOrgChanged =
          payload.scope !== initialData.scope || payload.organizationId !== initialData.organizationId;
        await updateAnnouncement(initialData.id, payload, {
          isSuperUser,
          includeScopeAndOrg: isSuperUser && scopeOrOrgChanged,
        });
        message.success("Announcement updated");
      }

      await onSuccess();
    } catch (err: any) {
      if (err?.errorFields) {
        return;
      }
      message.error(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title={mode === "create" ? "New Announcement" : "Edit Announcement"}
      okText={mode === "create" ? "Create" : "Save changes"}
      confirmLoading={submitting}
      onOk={() => void handleSubmit()}
      onCancel={onCancel}
      width={680}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        {allowGlobalScope && (
          <Form.Item name="scope" label="Target scope" rules={[{ required: true }]}>
            <Radio.Group onChange={(e) => handleScopeChange(e.target.value as AnnouncementScope)}>
              <Radio value="GLOBAL">All Organizations (Global)</Radio>
              <Radio value="ORGANIZATION">Specific Organization</Radio>
            </Radio.Group>
          </Form.Item>
        )}

        {allowGlobalScope && watchedScope === "ORGANIZATION" && (
          <Form.Item
            name="organizationId"
            label="Organization"
            rules={[{ required: true, message: "Select an organization" }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="Select an organization"
              options={organizations.map((org) => ({ label: org.name, value: org.id }))}
            />
          </Form.Item>
        )}

        <Form.Item
          name="title"
          label="Title"
          rules={[
            { required: true, whitespace: true, message: "Enter a title" },
            { max: 120, message: "Title must be at most 120 characters" },
          ]}
        >
          <Input maxLength={120} showCount placeholder="Scheduled database maintenance window" />
        </Form.Item>

        <Form.Item label="Message (Markdown)" required>
          <Tabs
            activeKey={messageTab}
            onChange={setMessageTab}
            size="small"
            items={[
              {
                key: "write",
                label: "Write",
                children: (
                  <Form.Item
                    name="message"
                    noStyle
                    rules={[{ required: true, whitespace: true, message: "Enter an announcement message" }]}
                  >
                    <TextArea
                      rows={4}
                      placeholder="Supports **bold**, lists, and [links](https://status.example.com). Raw HTML is stripped."
                    />
                  </Form.Item>
                ),
              },
              {
                key: "preview",
                label: "Preview",
                children: (
                  <div className="announcement-markdown-preview">
                    {watchedMessage.trim() ? (
                      <AnnouncementMarkdown content={watchedMessage} />
                    ) : (
                      <Typography.Text type="secondary">Nothing to preview yet.</Typography.Text>
                    )}
                  </div>
                ),
              },
            ]}
          />
        </Form.Item>

        <Form.Item name="severity" label="Severity" rules={[{ required: true }]}>
          <Select
            options={[
              { value: "INFO", label: "Info (Blue)" },
              { value: "WARNING", label: "Warning (Amber)" },
              { value: "CRITICAL", label: "Critical (Red)" },
            ]}
          />
        </Form.Item>

        <Form.Item name="scheduleType" label="Schedule" rules={[{ required: true }]}>
          <Radio.Group>
            <Radio value="IMMEDIATE">Immediate (active right away)</Radio>
            <Radio value="SCHEDULED">Scheduled window</Radio>
          </Radio.Group>
        </Form.Item>

        {watchedScheduleType === "SCHEDULED" ? (
          <Form.Item
            name="scheduleRange"
            label="Active window (local time)"
            rules={[
              { required: true, message: "Select start and end date/time" },
              {
                validator: async (_rule, value?: [Dayjs, Dayjs]) => {
                  if (value && value[0] && value[1] && !value[1].isAfter(value[0])) {
                    throw new Error("End time must be after start time");
                  }
                },
              },
            ]}
            extra={
              watchedRange && watchedRange[0] && watchedRange[1] ? (
                <Typography.Text type="secondary">
                  UTC: {formatUtcTimestamp(watchedRange[0].toISOString())} →{" "}
                  {formatUtcTimestamp(watchedRange[1].toISOString())}
                </Typography.Text>
              ) : (
                <Typography.Text type="secondary">Select local timestamps; stored and enforced in UTC.</Typography.Text>
              )
            }
          >
            <RangePicker showTime={{ format: "HH:mm" }} format="YYYY-MM-DD HH:mm" style={{ width: "100%" }} />
          </Form.Item>
        ) : (
          <Form.Item
            name="immediateEndsAt"
            label="Optional end time (local time)"
            extra={
              watchedImmediateEnd ? (
                <Typography.Text type="secondary">
                  UTC end: {formatUtcTimestamp(watchedImmediateEnd.toISOString())}
                </Typography.Text>
              ) : (
                <Typography.Text type="secondary">Leave blank for a perpetual announcement.</Typography.Text>
              )
            }
          >
            <DatePicker
              showTime={{ format: "HH:mm" }}
              format="YYYY-MM-DD HH:mm"
              allowClear
              placeholder="Perpetual (no end time)"
              style={{ width: "100%" }}
            />
          </Form.Item>
        )}

        <Space wrap size="large">
          <Form.Item name="active" label="Active" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="dismissible" valuePropName="checked">
            <Checkbox>Allow users to dismiss</Checkbox>
          </Form.Item>
          {allowGlobalScope && watchedScope === "GLOBAL" && (
            <Form.Item name="displayOnLogin" valuePropName="checked">
              <Checkbox>Display on login screen</Checkbox>
            </Form.Item>
          )}
        </Space>
      </Form>
    </Modal>
  );
}
