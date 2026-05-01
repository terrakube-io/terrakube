import { DeleteOutlined } from "@ant-design/icons";
import { Button, Form, Input, Layout, Menu, Popconfirm, Space, Spin, Typography, message, theme } from "antd";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import PageWrapper from "@/modules/layout/PageWrapper/PageWrapper";
import projectService from "./projectService";
import ProjectWorkspaces from "./ProjectWorkspaces";
import workspaceService from "@/modules/workspaces/workspaceService";
import useApiRequest from "@/modules/api/useApiRequest";
import { ProjectModel } from "@/domain/types";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import type { MenuProps } from "antd";

const { Content, Sider } = Layout;
type MenuItem = Required<MenuProps>["items"][number];

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

type ProjectForm = {
  name: string;
  description?: string;
};

type GeneralProps = {
  orgid: string;
  projectId: string;
  project: ProjectModel | null;
  assignedWorkspacesCount: number;
};

function ProjectGeneralSettings({ orgid, projectId, project, assignedWorkspacesCount }: GeneralProps) {
  const [waiting, setWaiting] = useState(false);
  const [form] = Form.useForm<ProjectForm>();
  const navigate = useNavigate();

  useEffect(() => {
    if (project) {
      form.setFieldsValue({ name: project.name, description: project.description });
    }
  }, [project, form]);

  const onFinish = async (values: ProjectForm) => {
    setWaiting(true);
    try {
      await projectService.updateProject(orgid, projectId, values);
      message.success("Project updated successfully");
    } catch (err: any) {
      if (err?.response?.status === 403) {
        message.error(
          <span>
            You are not authorized to update projects. <br /> Please contact your administrator and request the{" "}
            <b>Manage Workspaces</b> permission. <br /> For more information, visit the{" "}
            <a
              target="_blank"
              href="https://docs.terrakube.io/user-guide/organizations/team-management"
              rel="noreferrer"
            >
              Terrakube documentation
            </a>
            .
          </span>
        );
      } else {
        message.error(err?.message ?? "Project update failed");
      }
    } finally {
      setWaiting(false);
    }
  };

  const onDelete = async () => {
    try {
      await projectService.deleteProject(orgid, projectId);
      message.success("Project deleted successfully");
      navigate(`/organizations/${orgid}/projects`);
    } catch (err: any) {
      const statusCode = err?.response?.status;
      if (statusCode === 403) {
        message.error(
          <span>
            You are not authorized to delete projects. <br /> Please contact your administrator and request the{" "}
            <b>Manage Workspaces</b> permission. <br /> For more information, visit the{" "}
            <a
              target="_blank"
              href="https://docs.terrakube.io/user-guide/organizations/team-management"
              rel="noreferrer"
            >
              Terrakube documentation
            </a>
            .
          </span>
        );
      } else if (statusCode === 423) {
        message.error(
          "Cannot delete project: Workspaces are still associated with this project. Please remove all workspaces first and try again."
        );
      } else {
        message.error(err?.message ?? "Project deletion failed");
      }
    }
  };

  return (
    <div style={{ width: "100%" }}>
      <h1>General Settings</h1>
      <p>Adjust the name and description for this project.</p>
      <Spin spinning={waiting}>
        <Form form={form} layout="vertical" name="project-general-settings" onFinish={onFinish} requiredMark={false}>
          <Form.Item name="name" label="Name" rules={[{ required: true, message: "Name is required" }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="Description" extra="Optional">
            <Input.TextArea rows={5} placeholder="Project description" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit">
              Save settings
            </Button>
          </Form.Item>
        </Form>
      </Spin>

      <div style={{ marginTop: "40px" }}>
        <Typography.Text type="secondary">
          Deleting this project permanently removes all of its settings and history from Terrakube.
        </Typography.Text>
        <h3 style={{ marginBottom: "16px", marginTop: "24px" }}>Delete this Project</h3>
        <div style={{ marginBottom: "16px" }}>
          <Typography.Text type="secondary">
            <Typography.Text strong>Warning!</Typography.Text> This action cannot be undone.
          </Typography.Text>
          {assignedWorkspacesCount > 0 && (
            <div style={{ marginTop: "8px" }}>
              <Typography.Text type="warning">
                This project has {assignedWorkspacesCount} workspace{assignedWorkspacesCount > 1 ? "s" : ""} assigned.
                Please remove all workspaces before deleting this project.
              </Typography.Text>
            </div>
          )}
        </div>
        <Popconfirm
          title={
            <p>
              Project will be permanently deleted
              <br />
              from this organization.
              <br />
              Are you sure?
            </p>
          }
          onConfirm={onDelete}
          okText="Yes"
          cancelText="No"
          placement="bottom"
          disabled={assignedWorkspacesCount > 0}
        >
          <Button
            type="primary"
            danger
            style={{ width: "fit-content", padding: "8px 24px", height: "auto" }}
            disabled={assignedWorkspacesCount > 0}
          >
            <Space>
              <DeleteOutlined />
              Delete Project
            </Space>
          </Button>
        </Popconfirm>
      </div>
    </div>
  );
}

export default function ProjectDetailPage({ organizationName, setOrganizationName }: Props) {
  const { orgid, id } = useParams();
  const [project, setProject] = useState<ProjectModel | null>(null);
  const [activeKey, setActiveKey] = useState("general");
  const [assignedWorkspacesCount, setAssignedWorkspacesCount] = useState(0);
  const { token } = theme.useToken();

  const { loading, execute, error } = useApiRequest({
    action: () => projectService.getProject(orgid!, id!),
    onReturn: (data) => {
      setProject(data);
      const stored = sessionStorage.getItem(ORGANIZATION_NAME);
      if (stored) setOrganizationName(stored);
    },
  });

  useEffect(() => {
    execute();
  }, [id]);

  useEffect(() => {
    async function loadWorkspaces() {
      try {
        const result = await workspaceService.listWorkspaces(orgid!);
        if (!result.isError) {
          const assignedCount = result.data.workspaces.filter((ws) => ws.projectId === id).length;
          setAssignedWorkspacesCount(assignedCount);
        }
      } catch {
        // Silently fail if workspaces cannot be loaded
      }
    }

    if (orgid && id) {
      loadWorkspaces();
    }
  }, [orgid, id]);

  // Reload workspace count when switching tabs
  const handleTabChange = (key: string) => {
    setActiveKey(key);
    if (key === "general" && orgid && id) {
      // Reload workspace count when returning to general tab
      workspaceService.listWorkspaces(orgid).then((result) => {
        if (!result.isError) {
          const assignedCount = result.data.workspaces.filter((ws) => ws.projectId === id).length;
          setAssignedWorkspacesCount(assignedCount);
        }
      });
    }
  };

  const menuItems: MenuItem[] = [
    {
      type: "group",
      label: "Project Settings",
      key: "project-settings",
      children: [
        { key: "general", label: "General" },
        { key: "workspaces", label: "Workspaces" },
      ],
    },
  ];

  const renderContent = () => {
    switch (activeKey) {
      case "workspaces":
        return <ProjectWorkspaces orgid={orgid!} projectId={id!} />;
      case "general":
      default:
        return (
          <ProjectGeneralSettings
            orgid={orgid!}
            projectId={id!}
            project={project}
            assignedWorkspacesCount={assignedWorkspacesCount}
          />
        );
    }
  };

  return (
    <PageWrapper
      title={project?.name ?? "Project"}
      subTitle={`Project in the ${organizationName} organization`}
      loadingText="Loading project..."
      loading={loading}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Projects", path: `/organizations/${orgid}/projects` },
        { label: project?.name ?? "Project", path: `/organizations/${orgid}/projects/${id}` },
      ]}
      fluid
    >
      <Layout style={{ background: token.colorBgContainer }}>
        <Sider
          width={200}
          style={{
            background: token.colorBgContainer,
            borderRight: `1px solid ${token.colorBorderSecondary}`,
            height: "100%",
            overflow: "auto",
          }}
        >
          <Menu
            mode="inline"
            selectedKeys={[activeKey]}
            style={{ height: "100%" }}
            items={menuItems}
            onClick={(e) => handleTabChange(e.key)}
          />
        </Sider>
        <Content style={{ padding: "0 24px", minHeight: 280, maxWidth: 900 }}>{renderContent()}</Content>
      </Layout>
    </PageWrapper>
  );
}
