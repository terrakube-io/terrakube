import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Popconfirm, Select, Space, Spin, Table, Tag, Tooltip, Typography, message } from "antd";
import { useEffect, useState } from "react";
import axiosInstance from "@/config/axiosConfig";
import projectService, { ProjectAccessModel } from "./projectService";

type Props = {
  orgid: string;
  projectId: string;
  canManage: boolean;
};

type AddTeamForm = {
  teamName: string;
  role: string;
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
];

function roleColor(role: string): string {
  return ROLES.find((r) => r.value === role)?.color ?? "default";
}

function roleDescription(role: string): string {
  return ROLES.find((r) => r.value === role)?.description ?? "";
}

export default function ProjectAccessTab({ orgid, projectId, canManage }: Props) {
  const [accessList, setAccessList] = useState<ProjectAccessModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const [teams, setTeams] = useState<TeamOption[]>([]);
  const [loadingTeams, setLoadingTeams] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingRole, setEditingRole] = useState<string>("");
  const [savingRole, setSavingRole] = useState(false);
  const [form] = Form.useForm<AddTeamForm>();

  const load = async () => {
    setLoading(true);
    try {
      const result = await projectService.listProjectAccess(orgid, projectId);
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
      await projectService.addProjectAccess(orgid, projectId, values.teamName, values.role);
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
  };

  const onSaveRole = async (record: ProjectAccessModel) => {
    if (editingRole === record.role) {
      setEditingId(null);
      return;
    }
    setSavingRole(true);
    try {
      await projectService.updateProjectAccess(orgid, projectId, record.id, editingRole);
      message.success(`Role for "${record.name}" updated to ${editingRole}`);
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
            <Space>
              <Select
                size="small"
                value={editingRole}
                onChange={setEditingRole}
                style={{ width: 200 }}
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
      title: "",
      key: "actions",
      align: "right" as const,
      render: (_: any, record: ProjectAccessModel) => (
        <Popconfirm
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
    <div style={{ width: "100%" }}>
      <h1>Team Access</h1>
      <p>Grant teams access to all workspaces within this project.</p>

      <Spin spinning={loading}>
        <Table
          dataSource={accessList}
          columns={columns}
          rowKey="id"
          pagination={false}
          locale={{
            emptyText: canManage
              ? "No teams have been granted project-level access."
              : "You don't have permission to view or manage team assignments for this project.",
          }}
          style={{ marginBottom: 32 }}
        />
      </Spin>

      {canManage && (
        <>
          <h2>Add Team</h2>
          <Form form={form} layout="inline" onFinish={onAdd} style={{ marginBottom: 8 }}>
            <Form.Item name="teamName" rules={[{ required: true, message: "Team name is required" }]}>
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
            <Form.Item name="role" initialValue="write" rules={[{ required: true }]}>
              <Select
                style={{ width: 200 }}
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
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={adding}>
                Add Team
              </Button>
            </Form.Item>
          </Form>
        </>
      )}

      <Typography.Text type="secondary" style={{ fontSize: 12, display: "block", marginTop: 16 }}>
        Teams added here can manage workspaces in this project based on their assigned role.
        <br />
        This is additive — existing org-level and workspace-level permissions are not affected.
      </Typography.Text>
    </div>
  );
}
