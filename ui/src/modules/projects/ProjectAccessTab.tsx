import { CheckCircleFilled, CloseCircleOutlined, DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import {
  Button,
  Checkbox,
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
} from "antd";
import { useEffect, useState } from "react";
import axiosInstance from "@/config/axiosConfig";
import projectService, { ProjectAccessModel, ProjectAccessPermissions } from "./projectService";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import "./Projects.css";

type Props = {
  orgid: string;
  projectId: string;
  canManage: boolean;
};

type AddTeamForm = {
  teamName: string;
  role: string;
  manageWorkspace?: boolean;
  manageState?: boolean;
  planJob?: boolean;
  approveJob?: boolean;
  destroyJob?: boolean;
};

type TeamOption = { id: string; name: string };

const ROLES = [
  {
    value: "admin",
    label: "Admin",
    color: "red",
    description: "Full control — manages workspaces, runs plans and approvals, controls project team access.",
  },
  {
    value: "write",
    label: "Write",
    color: "orange",
    description: "Can manage workspaces, and queue and apply plans.",
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
    description: "Choose individual permissions on the project's workspaces. Cannot manage project team access.",
  },
];

const PERMISSION_FIELDS: { key: keyof ProjectAccessPermissions; label: string; shortLabel: string }[] = [
  { key: "manageWorkspace", label: "Manage workspaces", shortLabel: "Workspaces" },
  { key: "manageState", label: "Manage state", shortLabel: "State" },
  { key: "planJob", label: "Plan runs", shortLabel: "Plan" },
  { key: "approveJob", label: "Approve runs", shortLabel: "Approve" },
  { key: "destroyJob", label: "Destroy runs", shortLabel: "Destroy" },
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

// Mirrors RbacV2Service: a preset role decides every permission, only a custom role reads the saved flags.
function effectivePermissions(record: ProjectAccessModel): ProjectAccessPermissions {
  switch (record.role) {
    case "admin":
    case "write":
      return { manageWorkspace: true, manageState: true, planJob: true, approveJob: true, destroyJob: true };
    case "plan":
      return { manageWorkspace: false, manageState: false, planJob: true, approveJob: false, destroyJob: false };
    case "read":
      return { manageWorkspace: false, manageState: false, planJob: false, approveJob: false, destroyJob: false };
    default:
      return {
        manageWorkspace: record.manageWorkspace,
        manageState: record.manageState,
        planJob: record.planJob,
        approveJob: record.approveJob,
        destroyJob: record.destroyJob,
      };
  }
}

export default function ProjectAccessTab({ orgid, projectId, canManage }: Props) {
  const [accessList, setAccessList] = useState<ProjectAccessModel[]>([]);
  const [restricted, setRestricted] = useState(false);
  const [loading, setLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const [teams, setTeams] = useState<TeamOption[]>([]);
  const [loadingTeams, setLoadingTeams] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingRole, setEditingRole] = useState<string>("");
  const [editingPermissions, setEditingPermissions] = useState<ProjectAccessPermissions>({
    manageWorkspace: false,
    manageState: false,
    planJob: false,
    approveJob: false,
    destroyJob: false,
  });
  const [savingRole, setSavingRole] = useState(false);
  const [form] = Form.useForm<AddTeamForm>();
  const addRole = Form.useWatch("role", form);

  const load = async () => {
    setLoading(true);
    try {
      const result = await projectService.listProjectTeams(orgid, projectId);
      if (!result.isError) {
        setAccessList(result.data?.teams ?? []);
        setRestricted(result.data?.restricted ?? false);
      } else {
        message.error("Failed to load team access list");
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [projectId]);

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
              destroyJob: !!values.destroyJob,
            }
          : undefined;
      await projectService.addProjectAccess(orgid, projectId, values.teamName, values.role, permissions);
      message.success(`Team "${values.teamName}" added to project`);
      form.resetFields();
      await load();
    } catch (err: any) {
      if (err?.response?.status === 403) {
        message.error("You are not authorized to manage project team access.");
      } else {
        message.error(err?.message ?? "Failed to add team access");
      }
    } finally {
      setAdding(false);
    }
  };

  const onRemove = async (accessId: string, teamName: string) => {
    try {
      await projectService.removeProjectAccess(orgid, projectId, accessId);
      message.success(`Team "${teamName}" removed from project`);
      await load();
    } catch (err: any) {
      if (err?.response?.status === 403) {
        message.error("You are not authorized to manage project team access.");
      } else {
        message.error(err?.message ?? "Failed to remove team access");
      }
    }
  };

  const onEditRole = (record: ProjectAccessModel) => {
    setEditingId(record.id);
    setEditingRole(record.role);
    setEditingPermissions(effectivePermissions(record));
  };

  const onSaveRole = async (record: ProjectAccessModel) => {
    // A custom role can keep its name and still change its permissions.
    if (editingRole === record.role && editingRole !== "custom") {
      setEditingId(null);
      return;
    }
    setSavingRole(true);
    try {
      const permissions = editingRole === "custom" ? editingPermissions : undefined;
      await projectService.updateProjectAccess(orgid, projectId, record.id, editingRole, permissions);
      message.success(`Role for "${record.name}" updated to ${roleLabel(editingRole)}`);
      setEditingId(null);
      await load();
    } catch (err: any) {
      if (err?.response?.status === 403) {
        message.error("You are not authorized to manage project team access.");
      } else {
        message.error(err?.message ?? "Failed to update role");
      }
    } finally {
      setSavingRole(false);
    }
  };

  const columns = [
    {
      title: "Team",
      dataIndex: "name",
      key: "name",
      render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
    },
    {
      title: "Role",
      dataIndex: "role",
      key: "role",
      render: (role: string, record: ProjectAccessModel) => {
        if (canManage && editingId === record.id) {
          return (
            <Space orientation="vertical" size={8}>
              <Space>
                <Select
                  size="small"
                  value={editingRole}
                  onChange={setEditingRole}
                  className="project-access-role-select"
                  options={ROLES.map((r) => ({ value: r.value, label: r.label }))}
                  optionRender={(opt) => {
                    const r = ROLES.find((x) => x.value === opt.value);
                    if (!r) return opt.label;
                    return (
                      <Space orientation="vertical" size={2} className="project-access-role-option">
                        <Tag color={r.color}>{r.label}</Tag>
                        <Typography.Text type="secondary" className="project-access-role-description">
                          {r.description}
                        </Typography.Text>
                      </Space>
                    );
                  }}
                  labelRender={(item) => {
                    const r = ROLES.find((x) => x.value === item.value);
                    return r ? <Tag color={r.color}>{r.label}</Tag> : <span>{item.label}</span>;
                  }}
                />
                <Button type="primary" size="small" loading={savingRole} onClick={() => onSaveRole(record)}>
                  Save
                </Button>
                <Button size="small" onClick={() => setEditingId(null)}>
                  Cancel
                </Button>
              </Space>
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
            </Space>
          );
        }
        return (
          <Space>
            <Tooltip title={roleDescription(role)}>
              <Tag color={roleColor(role)} className="project-access-role-tag">
                {roleLabel(role)}
              </Tag>
            </Tooltip>
            {canManage && (
              <Button
                type="link"
                size="small"
                className="project-access-change-button"
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
            <span className="project-access-permission-heading">{field.shortLabel}</span>
          </Tooltip>
        ),
        key: field.key,
        align: "center" as const,
        render: (_: any, record: ProjectAccessModel) => {
          const granted = effectivePermissions(record)[field.key];
          const label = `${granted ? "Can" : "Cannot"} ${field.label.toLowerCase()}`;
          return (
            <Tooltip title={label}>
              {granted ? (
                <CheckCircleFilled
                  aria-label={label}
                  className="project-access-permission project-access-permission--granted"
                />
              ) : (
                <CloseCircleOutlined aria-label={label} className="project-access-permission" />
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
      render: (_: any, record: ProjectAccessModel) => (
        <Popconfirm
          okButtonProps={{ danger: true }}
          title={`Remove team "${record.name}" from this project?`}
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

  return (
    <div className="project-settings-panel">
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/projects/team-access"
        title="Team Access"
        description="Grant teams access to all workspaces within this project."
      />

      <Spin spinning={loading}>
        <Table
          dataSource={accessList}
          columns={columns}
          rowKey="id"
          pagination={false}
          scroll={{ x: "max-content" }}
          locale={{
            emptyText: restricted
              ? "You can see this project but not who has access. Only users who manage project access can see team names."
              : "No teams have been granted project-level access.",
          }}
          className="project-access-table"
        />
      </Spin>

      {canManage && (
        <>
          <h2>Add Team</h2>
          <Form form={form} layout="inline" onFinish={onAdd} className="project-access-add-form">
            <Form.Item name="teamName" rules={[{ required: true, message: "Team name is required" }]}>
              <Select
                showSearch
                placeholder="Select a team"
                optionFilterProp="label"
                loading={loadingTeams}
                className="project-access-team-select"
                options={teams.map((t) => ({
                  label: t.name,
                  value: t.name,
                  disabled: accessList.some((a) => a.name === t.name),
                }))}
              />
            </Form.Item>
            <Form.Item name="role" initialValue="write" rules={[{ required: true }]}>
              <Select
                className="project-access-role-select"
                options={ROLES.map((r) => ({ value: r.value, label: r.label }))}
                optionRender={(opt) => {
                  const r = ROLES.find((x) => x.value === opt.value);
                  if (!r) return opt.label;
                  return (
                    <Space orientation="vertical" size={2} className="project-access-role-option">
                      <Tag color={r.color}>{r.label}</Tag>
                      <Typography.Text type="secondary" className="project-access-role-description">
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
            </Form.Item>
            {addRole === "custom" &&
              PERMISSION_FIELDS.map((field) => (
                <Form.Item key={field.key} name={field.key} valuePropName="checked">
                  <Checkbox>{field.label}</Checkbox>
                </Form.Item>
              ))}
            <Form.Item>
              <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={adding}>
                Add Team
              </Button>
            </Form.Item>
          </Form>
        </>
      )}

      <Typography.Text type="secondary" className="project-access-footnote">
        Teams added here can manage workspaces in this project based on their assigned role.
        <br />
        This is additive — existing org-level and workspace-level permissions are not affected.
      </Typography.Text>
    </div>
  );
}
