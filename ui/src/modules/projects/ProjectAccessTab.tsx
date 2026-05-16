import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { Button, Form, Popconfirm, Select, Spin, Table, Tag, Typography, message } from "antd";
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
  { value: "admin", label: "Admin", color: "red" },
  { value: "write", label: "Write", color: "orange" },
  { value: "plan", label: "Plan", color: "blue" },
  { value: "read", label: "Read", color: "default" },
];

function roleColor(role: string): string {
  return ROLES.find((r) => r.value === role)?.color ?? "default";
}

export default function ProjectAccessTab({ orgid, projectId, canManage }: Props) {
  const [accessList, setAccessList] = useState<ProjectAccessModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const [teams, setTeams] = useState<TeamOption[]>([]);
  const [loadingTeams, setLoadingTeams] = useState(false);
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
      render: (role: string) => <Tag color={roleColor(role)}>{role ?? "custom"}</Tag>,
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
          <Form form={form} layout="inline" onFinish={onAdd} style={{ marginBottom: 16 }}>
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
              <Select style={{ width: 140 }}>
                {ROLES.map((r) => (
                  <Select.Option key={r.value} value={r.value}>
                    <Tag color={r.color}>{r.label}</Tag>
                  </Select.Option>
                ))}
              </Select>
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={adding}>
                Add Team
              </Button>
            </Form.Item>
          </Form>
        </>
      )}

      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        Teams added here can manage workspaces in this project based on their assigned role.
        <br />
        This is additive — existing org-level and workspace-level permissions are not affected.
      </Typography.Text>
    </div>
  );
}
