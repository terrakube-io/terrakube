import { useEffect, useState, useCallback } from "react";
import { useParams } from "react-router-dom";
import { Button, Table, Tag, Switch, Space, Popconfirm, message, Typography } from "antd";
import { PlusOutlined, EditOutlined, DeleteOutlined, NotificationOutlined } from "@ant-design/icons";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { EmptyState } from "@/components/feedback/EmptyState";
import { Loading } from "@/components/feedback/Loading";
import axiosInstance from "@/config/axiosConfig";
import { fetchAnnouncements } from "@/modules/announcements/announcementService";
import { Announcement } from "@/modules/announcements/announcementTypes";
import { formatSchedule } from "@/modules/announcements/announcementStorage";
import AnnouncementModal, { AnnouncementFormData } from "./AnnouncementModal";

const { Text } = Typography;

export interface OrganizationAnnouncementsProps {
  managePermission?: boolean;
}

export const OrganizationAnnouncements = ({ managePermission = true }: OrganizationAnnouncementsProps) => {
  const { orgid } = useParams<{ orgid: string }>();
  const [announcements, setAnnouncements] = useState<Announcement[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalVisible, setModalVisible] = useState(false);
  const [modalMode, setModalMode] = useState<"create" | "edit">("create");
  const [editingAnnouncement, setEditingAnnouncement] = useState<Announcement | null>(null);
  const [saving, setSaving] = useState(false);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const all = await fetchAnnouncements();
      // Filter for this organization and platform announcements
      const filtered = all.filter(
        (a) => a.scope === "GLOBAL" || (a.scope === "ORGANIZATION" && a.organization?.id === orgid)
      );
      setAnnouncements(filtered);
    } catch (err: any) {
      message.error(err.message || "Failed to load announcements");
    } finally {
      setLoading(false);
    }
  }, [orgid]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleOpenCreate = () => {
    setEditingAnnouncement(null);
    setModalMode("create");
    setModalVisible(true);
  };

  const handleOpenEdit = (record: Announcement) => {
    setEditingAnnouncement(record);
    setModalMode("edit");
    setModalVisible(true);
  };

  const handleDelete = async (id: string) => {
    try {
      await axiosInstance.delete(`/announcement/${id}`, {
        headers: { "Content-Type": "application/vnd.api+json" },
      });
      message.success("Announcement deleted successfully");
      loadData();
    } catch (err: any) {
      message.error(err.response?.data?.errors?.[0]?.detail || "Failed to delete announcement");
    }
  };

  const handleToggleActive = async (record: Announcement, checked: boolean) => {
    try {
      await axiosInstance.patch(
        `/announcement/${record.id}`,
        {
          data: {
            type: "announcement",
            id: record.id,
            attributes: {
              active: checked,
            },
          },
        },
        { headers: { "Content-Type": "application/vnd.api+json" } }
      );
      message.success(`Announcement ${checked ? "activated" : "deactivated"}`);
      loadData();
    } catch (err: any) {
      message.error(err.response?.data?.errors?.[0]?.detail || "Failed to update announcement status");
    }
  };

  const handleSave = async (values: AnnouncementFormData) => {
    setSaving(true);
    try {
      const attributes: Record<string, any> = {
        title: values.title,
        message: values.message,
        severity: values.severity,
        scope: values.scope,
        active: values.active,
        dismissible: values.dismissible,
        displayOnLogin: values.scope === "GLOBAL" ? values.displayOnLogin : false,
        startsAt: values.schedule?.[0] ? values.schedule[0].toISOString() : null,
        endsAt: values.schedule?.[1] ? values.schedule[1].toISOString() : null,
      };

      if (modalMode === "create") {
        const payload: Record<string, any> = {
          data: {
            type: "announcement",
            attributes,
          },
        };

        if (values.scope === "ORGANIZATION" && orgid) {
          payload.data.relationships = {
            organization: {
              data: {
                type: "organization",
                id: orgid,
              },
            },
          };
        }

        await axiosInstance.post("/announcement", payload, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });
        message.success("Announcement created successfully");
      } else if (editingAnnouncement) {
        const payload = {
          data: {
            type: "announcement",
            id: editingAnnouncement.id,
            attributes,
          },
        };

        await axiosInstance.patch(`/announcement/${editingAnnouncement.id}`, payload, {
          headers: { "Content-Type": "application/vnd.api+json" },
        });
        message.success("Announcement updated successfully");
      }

      setModalVisible(false);
      loadData();
    } catch (err: any) {
      message.error(err.response?.data?.errors?.[0]?.detail || "Failed to save announcement");
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    {
      title: "Title",
      dataIndex: "title",
      key: "title",
      render: (text: string, record: Announcement) => (
        <div>
          <Text strong>{text}</Text>
          <div style={{ fontSize: 12, color: "var(--tk-text-secondary, #8c8c8c)", marginTop: 2 }}>
            {formatSchedule(record.startsAt, record.endsAt) ?? "Always active"}
          </div>
        </div>
      ),
    },
    {
      title: "Scope",
      dataIndex: "scope",
      key: "scope",
      render: (scope: string) => (
        <Tag color={scope === "GLOBAL" ? "blue" : "purple"}>{scope === "GLOBAL" ? "Platform" : "Organization"}</Tag>
      ),
    },
    {
      title: "Severity",
      dataIndex: "severity",
      key: "severity",
      render: (severity: string) => (
        <Tag color={severity === "critical" ? "error" : severity === "warning" ? "warning" : "default"}>
          {severity.toUpperCase()}
        </Tag>
      ),
    },
    {
      title: "Active",
      dataIndex: "active",
      key: "active",
      render: (active: boolean, record: Announcement) => (
        <Switch
          checked={active}
          disabled={!managePermission}
          onChange={(checked) => handleToggleActive(record, checked)}
        />
      ),
    },
    {
      title: "Dismissible",
      dataIndex: "dismissible",
      key: "dismissible",
      render: (dismissible: boolean) => (
        <Tag color={dismissible ? "green" : "default"}>{dismissible ? "Yes" : "No"}</Tag>
      ),
    },
    {
      title: "Actions",
      key: "actions",
      render: (_: any, record: Announcement) => (
        <Space size="small">
          <Button
            type="text"
            icon={<EditOutlined />}
            disabled={!managePermission}
            onClick={() => handleOpenEdit(record)}
            aria-label={`Edit ${record.title}`}
          />
          <Popconfirm
            title="Delete announcement"
            description="Are you sure you want to delete this announcement?"
            onConfirm={() => handleDelete(record.id)}
            okText="Delete"
            okType="danger"
            cancelText="Cancel"
            disabled={!managePermission}
          >
            <Button
              type="text"
              danger
              icon={<DeleteOutlined />}
              disabled={!managePermission}
              aria-label={`Delete ${record.title}`}
            />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  if (loading) {
    return <Loading />;
  }

  return (
    <div>
      <SettingsPageHeader
        title="Announcements"
        description="Broadcast notices, scheduled maintenance banners, and operational alerts to users."
        action={
          managePermission ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={handleOpenCreate}>
              New Announcement
            </Button>
          ) : undefined
        }
      />

      {announcements.length === 0 ? (
        <EmptyState
          title="No announcements"
          description="Create announcement banners to notify members of scheduled maintenance or operational news."
          actionText={managePermission ? "New Announcement" : undefined}
          onAction={handleOpenCreate}
          icon={<NotificationOutlined />}
        />
      ) : (
        <Table dataSource={announcements} columns={columns} rowKey="id" pagination={{ pageSize: 10 }} />
      )}

      <AnnouncementModal
        visible={modalVisible}
        mode={modalMode}
        initialData={editingAnnouncement}
        onCancel={() => setModalVisible(false)}
        onSave={handleSave}
        loading={saving}
      />
    </div>
  );
};

export default OrganizationAnnouncements;
