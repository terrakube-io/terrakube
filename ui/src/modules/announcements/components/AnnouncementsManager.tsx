import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Input, Segmented, Space, Switch, Table, Tag, Tooltip, Typography, message } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useCallback, useContext, useEffect, useMemo, useState } from "react";
import { EmptyState } from "@/components/feedback/EmptyState";
import { Loading } from "@/components/feedback/Loading";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import SettingsPageHeader from "@/components/settings/SettingsPageHeader/SettingsPageHeader";
import { getErrorMessage } from "@/config/axiosConfig";
import { AnnouncementContext } from "../AnnouncementStore";
import {
  deleteAnnouncement,
  listAllAnnouncements,
  listOrgAnnouncements,
  updateAnnouncement,
} from "../announcementService";
import { formatUtcTimestamp, getAnnouncementLifecycleStatus, sortAnnouncements } from "../announcementUtils";
import type { AnnouncementItem, AnnouncementLifecycleStatus, AnnouncementSeverity } from "../types";
import { AnnouncementFormModal } from "./AnnouncementFormModal";
import "./AnnouncementsManager.css";

interface Props {
  mode: "organization" | "platform";
  organizationId?: string;
  canManage: boolean;
  isSuperUser: boolean;
  hideHeader?: boolean;
}

type StatusFilter = "ALL" | AnnouncementLifecycleStatus;

const SEVERITY_TAG_COLOR: Record<AnnouncementSeverity, string> = {
  CRITICAL: "error",
  WARNING: "warning",
  INFO: "processing",
};

const STATUS_TAG_CONFIG: Record<AnnouncementLifecycleStatus, { color: string; label: string }> = {
  ACTIVE: { color: "success", label: "Active" },
  SCHEDULED: { color: "blue", label: "Scheduled" },
  EXPIRED: { color: "default", label: "Expired" },
  INACTIVE: { color: "default", label: "Inactive" },
};

export function AnnouncementsManager({ mode, organizationId, canManage, isSuperUser, hideHeader = false }: Props) {
  const announcementContext = useContext(AnnouncementContext);
  const [items, setItems] = useState<AnnouncementItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("ALL");
  const [modalOpen, setModalOpen] = useState(false);
  const [modalMode, setModalMode] = useState<"create" | "edit">("create");
  const [editingItem, setEditingItem] = useState<AnnouncementItem | null>(null);
  const [pendingDelete, setPendingDelete] = useState<AnnouncementItem | null>(null);
  const [deleting, setDeleting] = useState(false);

  const loadAnnouncements = useCallback(async () => {
    if (mode === "organization" && !organizationId) {
      setItems([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const data =
        mode === "platform" ? await listAllAnnouncements() : await listOrgAnnouncements(organizationId as string);
      setItems(sortAnnouncements(data));
    } catch (err: any) {
      message.error(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [mode, organizationId]);

  useEffect(() => {
    void loadAnnouncements();
  }, [loadAnnouncements]);

  const handleSaved = async () => {
    setModalOpen(false);
    setEditingItem(null);
    await loadAnnouncements();
    await announcementContext?.refreshAnnouncements();
  };

  const handleToggleActive = async (record: AnnouncementItem, nextActive: boolean) => {
    try {
      await updateAnnouncement(record.id, { active: nextActive }, { isSuperUser, includeScopeAndOrg: false });
      message.success(nextActive ? "Announcement enabled" : "Announcement disabled");
      await loadAnnouncements();
      await announcementContext?.refreshAnnouncements();
    } catch (err: any) {
      message.error(getErrorMessage(err));
    }
  };

  const handleConfirmDelete = async () => {
    if (!pendingDelete) {
      return;
    }
    setDeleting(true);
    try {
      await deleteAnnouncement(pendingDelete.id);
      message.success("Announcement deleted");
      setPendingDelete(null);
      await loadAnnouncements();
      await announcementContext?.refreshAnnouncements();
    } catch (err: any) {
      message.error(getErrorMessage(err));
    } finally {
      setDeleting(false);
    }
  };

  const filteredItems = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    const nowMs = Date.now();
    return items.filter((item) => {
      if (statusFilter !== "ALL" && getAnnouncementLifecycleStatus(item, nowMs) !== statusFilter) {
        return false;
      }
      if (!query) {
        return true;
      }
      return (
        item.title.toLowerCase().includes(query) ||
        item.message.toLowerCase().includes(query) ||
        (item.organizationName ?? "").toLowerCase().includes(query)
      );
    });
  }, [items, searchQuery, statusFilter]);

  const columns: ColumnsType<AnnouncementItem> = [
    {
      title: "Announcement",
      key: "title",
      render: (_value, record) => (
        <div className="announcements-table-title">
          <Typography.Text strong>{record.title}</Typography.Text>
          <span className="announcements-table-excerpt">{record.message}</span>
        </div>
      ),
    },
    {
      title: "Severity",
      dataIndex: "severity",
      key: "severity",
      width: 110,
      render: (severity: AnnouncementSeverity) => <Tag color={SEVERITY_TAG_COLOR[severity]}>{severity}</Tag>,
    },
    ...(mode === "platform"
      ? [
          {
            title: "Target",
            key: "scope",
            width: 180,
            render: (_value: unknown, record: AnnouncementItem) =>
              record.scope === "GLOBAL" ? (
                <Tag color="purple">All Organizations (Global)</Tag>
              ) : (
                <Tag>{record.organizationName || record.organizationId}</Tag>
              ),
          },
        ]
      : []),
    {
      title: "Status",
      key: "status",
      width: 120,
      render: (_value, record) => {
        const status = getAnnouncementLifecycleStatus(record);
        const cfg = STATUS_TAG_CONFIG[status];
        return <Tag color={cfg.color}>{cfg.label}</Tag>;
      },
    },
    {
      title: "Window (UTC)",
      key: "window",
      width: 210,
      render: (_value, record) => (
        <Typography.Text type="secondary">
          {record.scheduleType === "IMMEDIATE" && !record.endsAt
            ? "Immediate (perpetual)"
            : `${record.startsAt ? formatUtcTimestamp(record.startsAt) : "Now"} → ${
                record.endsAt ? formatUtcTimestamp(record.endsAt) : "No end"
              }`}
        </Typography.Text>
      ),
    },
    {
      title: "Enabled",
      key: "active",
      width: 90,
      render: (_value, record) => (
        <Switch
          size="small"
          checked={record.active}
          disabled={!canManage}
          aria-label={`Toggle ${record.title}`}
          onChange={(checked) => void handleToggleActive(record, checked)}
        />
      ),
    },
    {
      title: "Actions",
      key: "actions",
      align: "right",
      width: 110,
      render: (_value, record) => (
        <Space size="small">
          <Tooltip title={canManage ? "Edit" : "Admin permissions required"}>
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              disabled={!canManage}
              aria-label={`Edit ${record.title}`}
              onClick={() => {
                setModalMode("edit");
                setEditingItem(record);
                setModalOpen(true);
              }}
            />
          </Tooltip>
          <Tooltip title={canManage ? "Delete" : "Admin permissions required"}>
            <Button
              type="text"
              danger
              size="small"
              icon={<DeleteOutlined />}
              disabled={!canManage}
              aria-label={`Delete ${record.title}`}
              onClick={() => setPendingDelete(record)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ];

  const newButton = (
    <Button
      type="primary"
      icon={<PlusOutlined />}
      disabled={!canManage}
      onClick={() => {
        setModalMode("create");
        setEditingItem(null);
        setModalOpen(true);
      }}
    >
      New Announcement
    </Button>
  );

  return (
    <div className="setting">
      {!hideHeader && (
        <SettingsPageHeader
          title={mode === "platform" ? "Platform announcements" : "Announcements"}
          description={
            mode === "platform"
              ? "Broadcast global notices across all organizations and the sign-in page, or target a specific organization."
              : "Publish operational alerts and scheduled maintenance banners for members of this organization."
          }
          actions={newButton}
        />
      )}

      <div className="announcements-toolbar">
        <Space wrap>
          <Segmented
            value={statusFilter}
            onChange={(value) => setStatusFilter(value as StatusFilter)}
            options={[
              { label: "All", value: "ALL" },
              { label: "Active", value: "ACTIVE" },
              { label: "Scheduled", value: "SCHEDULED" },
              { label: "Expired", value: "EXPIRED" },
              { label: "Inactive", value: "INACTIVE" },
            ]}
          />
          <Input.Search
            className="announcements-search"
            placeholder="Search announcements..."
            allowClear
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </Space>
        {hideHeader && newButton}
      </div>

      {loading ? (
        <Loading loading description="Loading announcements..." />
      ) : filteredItems.length === 0 ? (
        <EmptyState
          description={
            items.length === 0 ? "No announcements configured yet." : "No announcements match the current filter."
          }
        >
          {items.length === 0 && canManage && newButton}
        </EmptyState>
      ) : (
        <Table
          rowKey="id"
          columns={columns}
          dataSource={filteredItems}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
        />
      )}

      <AnnouncementFormModal
        open={modalOpen}
        mode={modalMode}
        initialData={editingItem}
        defaultScope={mode === "platform" ? "GLOBAL" : "ORGANIZATION"}
        defaultOrganizationId={organizationId ?? null}
        allowGlobalScope={mode === "platform" && isSuperUser}
        isSuperUser={isSuperUser}
        onSuccess={handleSaved}
        onCancel={() => {
          setModalOpen(false);
          setEditingItem(null);
        }}
      />

      <DeleteConfirmationModal
        open={pendingDelete !== null}
        title="Delete announcement"
        message={
          pendingDelete
            ? `Are you sure you want to delete "${pendingDelete.title}"? This removes the banner immediately.`
            : ""
        }
        confirmLoading={deleting}
        onConfirm={() => void handleConfirmDelete()}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
}
