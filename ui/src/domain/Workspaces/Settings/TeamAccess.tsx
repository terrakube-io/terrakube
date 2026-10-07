import {
  CheckCircleFilled,
  CloseCircleOutlined,
  DeleteOutlined,
  PlusOutlined,
  TeamOutlined,
  UsergroupAddOutlined,
} from "@ant-design/icons";
import { Button, Card, Checkbox, Form, Select, Space, Spin, Table, Tag, Tooltip, Typography, message } from "antd";
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";
import { useEffect, useRef, useState } from "react";
import axiosInstance from "@/config/axiosConfig";
import workspaceAccessService, {
  WorkspaceAccessModel,
  WorkspaceAccessPermissions,
} from "@/modules/workspaces/workspaceAccessService";
import { Workspace } from "../../types";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { EmptyState } from "@/components/feedback/EmptyState";
import "./TeamAccess.css";

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
};

type AddTeamForm = {
  teamName: string;
  role: string;
  manageWorkspace?: boolean;
  manageState?: boolean;
  planJob?: boolean;
  approveJob?: boolean;
};

type TeamOption = { id: string; name: string };

const ROLES = [
  {
    value: "admin",
    label: "Admin",
    color: "red",
    description: "Full control — manages the workspace, runs plans and approvals, controls workspace team access.",
  },
  {
    value: "write",
    label: "Write",
    color: "magenta",
    description: "Can manage the workspace, and queue and apply plans.",
  },
  {
    value: "plan",
    label: "Plan",
    color: "blue",
    description: "Can queue plans to propose changes but cannot approve or apply them.",
  },
  {
    value: "read",
    label: "Read",
    color: "default",
    description: "Read-only access. Cannot make any changes.",
  },
  {
    value: "custom",
    label: "Custom",
    color: "default",
    description: "Choose individual permissions for this team.",
  },
];

const PERMISSION_FIELDS: { key: keyof WorkspaceAccessPermissions; label: string; shortLabel: string }[] = [
  { key: "manageWorkspace", label: "Manage workspace", shortLabel: "Workspace" },
  { key: "manageState", label: "Manage state", shortLabel: "State" },
  { key: "planJob", label: "Plan runs", shortLabel: "Plan" },
  { key: "approveJob", label: "Approve runs", shortLabel: "Approve" },
];

function roleColor(role: string): string {
  return ROLES.find((r) => r.value === role)?.color ?? "default";
}

function roleLabel(role: string): string {
  return ROLES.find((r) => r.value === role)?.label ?? "Custom";
}

function roleDescription(role: string): string {
  return ROLES.find((r) => r.value === role)?.description ?? "";
}

function effectivePermissions(record: WorkspaceAccessModel): WorkspaceAccessPermissions {
  switch (record.role) {
    case "admin":
    case "write":
      return { manageWorkspace: true, manageState: true, planJob: true, approveJob: true };
    case "plan":
      return { manageWorkspace: false, manageState: false, planJob: true, approveJob: false };
    case "read":
      return { manageWorkspace: false, manageState: false, planJob: false, approveJob: false };
    default:
      return {
        manageWorkspace: record.manageWorkspace,
        manageState: record.manageState,
        planJob: record.planJob,
        approveJob: record.approveJob,
      };
  }
}

export const WorkspaceTeamAccess = ({ workspace, manageWorkspace }: Props) => {
  const orgid = workspace.relationships.organization.data.id;
  const workspaceId = workspace.id;
  const canManage = manageWorkspace;

  const [accessList, setAccessList] = useState<WorkspaceAccessModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const [teams, setTeams] = useState<TeamOption[]>([]);
  const [loadingTeams, setLoadingTeams] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingRole, setEditingRole] = useState<string>("");
  const [editingPermissions, setEditingPermissions] = useState<WorkspaceAccessPermissions>({
    manageWorkspace: false,
    manageState: false,
    planJob: false,
    approveJob: false,
  });
  const [savingRole, setSavingRole] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<WorkspaceAccessModel | null>(null);
  const [form] = Form.useForm<AddTeamForm>();
  const addRole = Form.useWatch("role", form);
  const addFormRef = useRef<HTMLDivElement>(null);

  const scrollToAddForm = () => {
    addFormRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  const load = async () => {
    setLoading(true);
    try {
      const result = await workspaceAccessService.listWorkspaceAccess(orgid, workspaceId);
      if (!result.isError) {
        setAccessList(result.data);
      } else {
        message.error("Failed to load team access list");
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [workspaceId]);

  useEffect(() => {
    setLoadingTeams(true);
    axiosInstance
      .get(`organization/${orgid}/team`)
      .then((res) => {
        const list = (res.data?.data ?? []).map((t: any) => ({
          id: t.id,
          name: t.attributes.name,
        }));
        setTeams(list);
      })
      .finally(() => setLoadingTeams(false));
  }, [orgid]);

  const onAdd = async (values: AddTeamForm) => {
    setAdding(true);
    try {
      const permissions =
        values.role === "custom"
          ? {
              manageWorkspace: !!values.manageWorkspace,
              manageState: !!values.manageState,
              planJob: !!values.planJob,
              approveJob: !!values.approveJob,
            }
          : undefined;
      await workspaceAccessService.addWorkspaceAccess(orgid, workspaceId, values.teamName, values.role, permissions);
      message.success(`Team "${values.teamName}" added to workspace`);
      form.resetFields();
      await load();
    } catch (err: any) {
      if (err?.response?.status === 403) {
        message.error("You are not authorized to manage workspace team access.");
      } else {
        message.error(err?.message ?? "Failed to add team access");
      }
    } finally {
      setAdding(false);
    }
  };

  const onRemove = async (accessId: string, teamName: string) => {
    try {
      await workspaceAccessService.removeWorkspaceAccess(orgid, workspaceId, accessId);
      message.success(`Team "${teamName}" removed from workspace`);
      await load();
    } catch (err: any) {
      if (err?.response?.status === 403) {
        message.error("You are not authorized to manage workspace team access.");
      } else {
        message.error(err?.message ?? "Failed to remove team access");
      }
    }
  };

  const onEditRole = (record: WorkspaceAccessModel) => {
    setEditingId(record.id);
    setEditingRole(record.role);
    setEditingPermissions(effectivePermissions(record));
  };

  const onSaveRole = async (record: WorkspaceAccessModel) => {
    setSavingRole(true);
    try {
      const permissions = editingRole === "custom" ? editingPermissions : undefined;
      await workspaceAccessService.updateWorkspaceAccess(orgid, workspaceId, record.id, editingRole, permissions);
      message.success(`Role for "${record.name}" updated to ${roleLabel(editingRole)}`);
      setEditingId(null);
      await load();
    } catch (err: any) {
      if (err?.response?.status === 403) {
        message.error("You are not authorized to manage workspace team access.");
      } else {
        message.error(err?.message ?? "Failed to update role");
      }
    } finally {
      setSavingRole(false);
    }
  };

  const renderRoleSelect = (value: string, onChange: (value: string) => void, compact = false) => (
    <Select
      size={compact ? "small" : undefined}
      value={value}
      onChange={onChange}
      className={compact ? "team-access-role-select team-access-role-select--compact" : "team-access-role-select"}
      popupMatchSelectWidth={320}
      options={ROLES.map((r) => ({ value: r.value, label: r.label }))}
      optionRender={(opt) => {
        const r = ROLES.find((x) => x.value === opt.value);
        if (!r) return opt.label;
        return (
          <Space orientation="vertical" size={2} className="team-access-role-option">
            <Tag color={r.color}>{r.label}</Tag>
            <Typography.Text type="secondary" className="team-access-role-option-description">
              {r.description}
            </Typography.Text>
          </Space>
        );
      }}
      labelRender={(item) => {
        const r = ROLES.find((x) => x.value === item.value);
        return r ? <Tag color={r.color}>{r.label}</Tag> : <span>{String(item.label ?? "")}</span>;
      }}
    />
  );

  const columns = [
    {
      title: "Team",
      dataIndex: "name",
      key: "name",
      render: (name: string) => (
        <Space size={8}>
          <TeamOutlined className="team-access-icon" />
          <Typography.Text strong>{name}</Typography.Text>
        </Space>
      ),
    },
    {
      title: "Role",
      dataIndex: "role",
      key: "role",
      width: 176,
      render: (role: string, record: WorkspaceAccessModel) => {
        if (canManage && editingId === record.id) {
          return (
            <Space orientation="vertical" size={8}>
              {renderRoleSelect(editingRole, setEditingRole, true)}
              {editingRole === "custom" && (
                <Space orientation="vertical" size={4}>
                  {PERMISSION_FIELDS.map((field) => (
                    <Checkbox
                      key={field.key}
                      checked={editingPermissions[field.key]}
                      onChange={(e) => setEditingPermissions((prev) => ({ ...prev, [field.key]: e.target.checked }))}
                    >
                      {field.label}
                    </Checkbox>
                  ))}
                </Space>
              )}
              <Space>
                <Button type="primary" size="small" loading={savingRole} onClick={() => onSaveRole(record)}>
                  Save
                </Button>
                <Button size="small" onClick={() => setEditingId(null)}>
                  Cancel
                </Button>
              </Space>
            </Space>
          );
        }
        return (
          <Space>
            <Tooltip title={roleDescription(role)}>
              <Tag color={roleColor(role)} className="team-access-role-tag">
                {roleLabel(role)}
              </Tag>
            </Tooltip>
            {canManage && (
              <Button type="link" size="small" className="team-access-change-role" onClick={() => onEditRole(record)}>
                Change
              </Button>
            )}
          </Space>
        );
      },
    },
    {
      title: "Permissions",
      key: "permissions",
      children: PERMISSION_FIELDS.map((field) => ({
        title: (
          <Tooltip title={field.label}>
            <span className="team-access-permission-heading">{field.shortLabel}</span>
          </Tooltip>
        ),
        key: field.key,
        align: "center" as const,
        width: 96,
        render: (_: any, record: WorkspaceAccessModel) => {
          const granted = effectivePermissions(record)[field.key];
          const label = `${granted ? "Can" : "Cannot"} ${field.label.toLowerCase()}`;
          return (
            <Tooltip title={label}>
              {granted ? (
                <CheckCircleFilled
                  aria-label={label}
                  className="team-access-permission team-access-permission--granted"
                />
              ) : (
                <CloseCircleOutlined aria-label={label} className="team-access-permission" />
              )}
            </Tooltip>
          );
        },
      })),
    },
    {
      title: "",
      key: "actions",
      align: "right" as const,
      width: 56,
      render: (_: any, record: WorkspaceAccessModel) => (
        <Tooltip title="Remove access">
          <Button
            danger
            icon={<DeleteOutlined />}
            size="small"
            disabled={!canManage}
            aria-label={`Remove access for team ${record.name}`}
            onClick={() => setPendingDelete(record)}
          />
        </Tooltip>
      ),
    },
  ];

  const teamCountLabel = accessList.length === 1 ? "1 team has access" : `${accessList.length} teams have access`;

  return (
    <div className="team-access">
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/organizations/team-management"
        title="Team access"
        description={
          <>
            Grant teams permissions on this workspace, here or with the{" "}
            <Typography.Text code>terrakube_workspace_access</Typography.Text> resource. Teams keep any organization or
            project permissions they already have.
          </>
        }
      />

      <SettingsSection maxWidth="100%">
        {!loading && accessList.length === 0 ? (
          <EmptyState
            simple
            description={
              <Typography.Text type="secondary">
                {canManage
                  ? "No teams have been granted access to this workspace yet."
                  : "You don't have permission to view or manage team assignments for this workspace."}
              </Typography.Text>
            }
          >
            {canManage && (
              <Button icon={<PlusOutlined />} onClick={scrollToAddForm}>
                Add a team
              </Button>
            )}
          </EmptyState>
        ) : (
          <>
            <Space align="center" className="team-access-count">
              <TeamOutlined className="team-access-icon" />
              <Typography.Text type="secondary">{teamCountLabel}</Typography.Text>
            </Space>
            <Spin spinning={loading}>
              <Table
                dataSource={accessList}
                columns={columns}
                rowKey="id"
                pagination={false}
                size="middle"
                tableLayout="fixed"
                scroll={{ x: "max-content" }}
                className="team-access-table"
              />
            </Spin>
          </>
        )}

        {canManage && (
          <Card
            ref={addFormRef}
            size="small"
            title={
              <Space>
                <UsergroupAddOutlined />
                <span>Grant access</span>
              </Space>
            }
            className="team-access-grant"
          >
            <Form form={form} layout="vertical" onFinish={onAdd}>
              <Form.Item name="teamName" label="Team" rules={[{ required: true, message: "Team name is required" }]}>
                <Select
                  showSearch
                  placeholder="Select a team"
                  optionFilterProp="label"
                  loading={loadingTeams}
                  options={teams.map((t) => ({
                    label: t.name,
                    value: t.name,
                    disabled: accessList.some((a) => a.name === t.name),
                  }))}
                />
              </Form.Item>
              <Form.Item name="role" label="Role" initialValue="write" rules={[{ required: true }]}>
                {renderRoleSelect(addRole ?? "write", (value) => form.setFieldsValue({ role: value }))}
              </Form.Item>

              {addRole === "custom" && (
                <Space wrap size={16} className="team-access-custom-permissions">
                  {PERMISSION_FIELDS.map((field) => (
                    <Form.Item
                      key={field.key}
                      name={field.key}
                      valuePropName="checked"
                      className="team-access-flush-item"
                    >
                      <Checkbox>{field.label}</Checkbox>
                    </Form.Item>
                  ))}
                </Space>
              )}

              <Form.Item className="team-access-flush-item">
                <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={adding}>
                  Add team
                </Button>
              </Form.Item>
            </Form>
          </Card>
        )}
      </SettingsSection>

      <DeleteConfirmationModal
        open={pendingDelete !== null}
        title="Remove team access"
        message={`Team "${pendingDelete?.name}" will lose the access granted on this workspace. Organization and project permissions are not changed.`}
        okText="Remove access"
        onConfirm={() => {
          if (pendingDelete) {
            onRemove(pendingDelete.id, pendingDelete.name);
          }
          setPendingDelete(null);
        }}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
};
