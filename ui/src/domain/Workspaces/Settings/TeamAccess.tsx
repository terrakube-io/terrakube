import {
  CheckCircleFilled,
  CloseCircleOutlined,
  DeleteOutlined,
  PlusOutlined,
  TeamOutlined,
  UsergroupAddOutlined,
} from "@ant-design/icons";
import {
  Button,
  Card,
  Checkbox,
  Empty,
  Form,
  Popconfirm,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
  theme,
} from "antd";
import { useEffect, useRef, useState } from "react";
import axiosInstance from "@/config/axiosConfig";
import workspaceAccessService, {
  WorkspaceAccessModel,
  WorkspaceAccessPermissions,
} from "@/modules/workspaces/workspaceAccessService";
import { Workspace } from "../../types";

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
    color: "orange",
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
    color: "purple",
    description: "Choose individual permissions for this team.",
  },
];

const PERMISSION_FIELDS: { key: keyof WorkspaceAccessPermissions; label: string; shortLabel: string }[] = [
  { key: "manageWorkspace", label: "Manage Workspace", shortLabel: "Workspace" },
  { key: "manageState", label: "Manage State", shortLabel: "State" },
  { key: "planJob", label: "Plan Runs", shortLabel: "Plan" },
  { key: "approveJob", label: "Approve Runs", shortLabel: "Approve" },
];

function roleColor(role: string): string {
  return ROLES.find((r) => r.value === role)?.color ?? "default";
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
  const [form] = Form.useForm<AddTeamForm>();
  const addRole = Form.useWatch("role", form);
  const addFormRef = useRef<HTMLDivElement>(null);
  const { token } = theme.useToken();

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
      message.success(`Role for "${record.name}" updated to ${editingRole}`);
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

  const renderRoleSelect = (value: string, onChange: (value: string) => void) => (
    <Select
      size="small"
      value={value}
      onChange={onChange}
      style={{ width: 160 }}
      options={ROLES.map((r) => ({ value: r.value, label: r.label }))}
      optionRender={(opt) => {
        const r = ROLES.find((x) => x.value === opt.value);
        if (!r) return opt.label;
        return (
          <Space direction="vertical" size={2} style={{ paddingTop: 4, paddingBottom: 4 }}>
            <Tag color={r.color}>{r.label}</Tag>
            <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: "normal" }}>
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
      width: 220,
      render: (name: string) => (
        <Space size={8}>
          <TeamOutlined style={{ color: token.colorTextSecondary }} />
          <Typography.Text strong>{name}</Typography.Text>
        </Space>
      ),
    },
    {
      title: "Role",
      dataIndex: "role",
      key: "role",
      width: 320,
      render: (role: string, record: WorkspaceAccessModel) => {
        if (canManage && editingId === record.id) {
          return (
            <Space direction="vertical" size={8}>
              {renderRoleSelect(editingRole, setEditingRole)}
              {editingRole === "custom" && (
                <Space wrap size={12}>
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
              <Tag color={roleColor(role)} style={{ cursor: "default" }}>
                {role ?? "custom"}
              </Tag>
            </Tooltip>
            {canManage && (
              <Button
                type="link"
                size="small"
                style={{ padding: 0, height: "auto" }}
                onClick={() => onEditRole(record)}
              >
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
            <span>{field.shortLabel}</span>
          </Tooltip>
        ),
        key: field.key,
        align: "center" as const,
        width: 84,
        render: (_: any, record: WorkspaceAccessModel) => {
          const granted = effectivePermissions(record)[field.key];
          return granted ? (
            <Tooltip title={`Can ${field.label.toLowerCase()}`}>
              <CheckCircleFilled style={{ color: token.colorSuccess, fontSize: 16 }} />
            </Tooltip>
          ) : (
            <Tooltip title={`Cannot ${field.label.toLowerCase()}`}>
              <CloseCircleOutlined style={{ color: token.colorTextQuaternary, fontSize: 16 }} />
            </Tooltip>
          );
        },
      })),
    },
    {
      title: "",
      key: "actions",
      align: "right" as const,
      width: 120,
      render: (_: any, record: WorkspaceAccessModel) => (
        <Popconfirm
          title={`Remove team "${record.name}" from this workspace?`}
          onConfirm={() => onRemove(record.id, record.name)}
          okText="Yes"
          cancelText="No"
          placement="left"
          disabled={!canManage}
        >
          <Button danger icon={<DeleteOutlined />} size="small" disabled={!canManage}>
            Remove
          </Button>
        </Popconfirm>
      ),
    },
  ];

  const teamCountLabel =
    accessList.length === 0
      ? "No teams have access yet"
      : `${accessList.length} team${accessList.length === 1 ? "" : "s"} have access`;

  return (
    <div style={{ width: "100%" }}>
      <h1>Team Access</h1>
      <p>Teams granted access to this workspace via the Terrakube UI or the terrakube_workspace_access resource.</p>

      <Space align="center" style={{ marginBottom: 12 }}>
        <TeamOutlined style={{ color: token.colorTextSecondary }} />
        <Typography.Text type="secondary">{teamCountLabel}</Typography.Text>
      </Space>

      <Spin spinning={loading}>
        <Table
          dataSource={accessList}
          columns={columns}
          rowKey="id"
          pagination={false}
          tableLayout="fixed"
          scroll={{ x: 996 }}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={
                  canManage
                    ? "No teams have been granted workspace-level access."
                    : "You don't have permission to view or manage team assignments for this workspace."
                }
              >
                {canManage && (
                  <Button type="primary" icon={<PlusOutlined />} onClick={scrollToAddForm}>
                    Add a team
                  </Button>
                )}
              </Empty>
            ),
          }}
          style={{ marginBottom: 32 }}
        />
      </Spin>

      {canManage && (
        <Card
          ref={addFormRef}
          size="small"
          title={
            <Space>
              <UsergroupAddOutlined />
              <span>Grant Access</span>
            </Space>
          }
          style={{ maxWidth: 640, marginBottom: 16 }}
        >
          <Form form={form} layout="vertical" onFinish={onAdd}>
            <Space align="start" wrap>
              <Form.Item name="teamName" label="Team" rules={[{ required: true, message: "Team name is required" }]}>
                <Select
                  showSearch
                  placeholder="Select a team"
                  optionFilterProp="label"
                  loading={loadingTeams}
                  style={{ minWidth: 220 }}
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
            </Space>

            {addRole === "custom" && (
              <Space wrap size={16} style={{ marginBottom: 8 }}>
                {PERMISSION_FIELDS.map((field) => (
                  <Form.Item key={field.key} name={field.key} valuePropName="checked" style={{ marginBottom: 0 }}>
                    <Checkbox>{field.label}</Checkbox>
                  </Form.Item>
                ))}
              </Space>
            )}

            <Form.Item style={{ marginBottom: 0 }}>
              <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={adding}>
                Add Team
              </Button>
            </Form.Item>
          </Form>
        </Card>
      )}

      <Typography.Text type="secondary" style={{ fontSize: 12, display: "block", marginTop: 16 }}>
        Teams added here can access this workspace based on their assigned role, in addition to any organization-level
        or project-level permissions they already have.
      </Typography.Text>
    </div>
  );
};
