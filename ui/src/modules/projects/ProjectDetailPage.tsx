import { DeleteOutlined } from "@ant-design/icons";
import { Button, Flex, Form, Input, Layout, Menu, Popconfirm, Select, Space, Spin, Typography, message } from "antd";
import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import projectService from "./projectService";
import { useProjectPermissions } from "@/modules/permissions/useProjectPermissions";
import ProjectWorkspaces from "./ProjectWorkspaces";
import ProjectAccessTab from "./ProjectAccessTab";
import ProjectDefaultTags from "./ProjectDefaultTags";
import ProjectOverview from "./ProjectOverview";
import ProjectPoliciesTab from "./ProjectPoliciesTab";
import ProjectVariablesTab from "./ProjectVariablesTab";
import { PROJECT_TAB_LABELS, ProjectTab, resolveLandingTab } from "./projectLandingTab";
import useApiRequest from "@/modules/api/useApiRequest";
import { ProjectModel, ProjectSummaryModel } from "@/domain/types";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import type { MenuProps } from "antd";
import { PermissionErrorMessage } from "@/components/feedback/PermissionErrorMessage";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import "./Projects.css";

const { Content, Sider } = Layout;
type MenuItem = Required<MenuProps>["items"][number];

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

type ProjectForm = {
  name: string;
  description?: string;
  landingTab?: string;
};

type GeneralProps = {
  orgid: string;
  projectId: string;
  project: ProjectModel | null;
  assignedWorkspacesCount: number;
  manageWorkspace: boolean;
  canUpdateProject: boolean;
  onSaved: () => void;
};

function ProjectGeneralSettings({
  orgid,
  projectId,
  project,
  assignedWorkspacesCount,
  manageWorkspace,
  canUpdateProject,
  onSaved,
}: GeneralProps) {
  const [waiting, setWaiting] = useState(false);
  const [form] = Form.useForm<ProjectForm>();
  const navigate = useNavigate();

  useEffect(() => {
    if (project) {
      form.setFieldsValue({
        name: project.name,
        description: project.description,
        landingTab: project.landingTab ?? "default",
      });
    }
  }, [project, form]);

  const onFinish = async (values: ProjectForm) => {
    setWaiting(true);
    try {
      await projectService.updateProject(orgid, projectId, {
        ...values,
        landingTab: !values.landingTab || values.landingTab === "default" ? null : values.landingTab,
      });
      message.success("Project updated successfully");
      onSaved();
    } catch (err: any) {
      if (err?.response?.status === 403) {
        message.error(<PermissionErrorMessage action="update projects" permission="Manage Workspaces" />);
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
        message.error(<PermissionErrorMessage action="delete projects" permission="Manage Workspaces" />);
      } else if (statusCode === 409 || statusCode === 423) {
        message.error(
          err?.response?.data?.errors?.[0]?.detail ??
            err?.response?.data?.errors?.[0] ??
            "Cannot delete project: workspaces are still associated with this project."
        );
      } else {
        message.error(err?.message ?? "Project deletion failed");
      }
    }
  };

  return (
    <div className="project-settings-panel">
      <SettingsPageHeader title="General Settings" description="Adjust the name and description for this project." />
      <Spin spinning={waiting}>
        <Form form={form} layout="vertical" name="project-general-settings" onFinish={onFinish} requiredMark={false}>
          <Form.Item name="name" label="Name" rules={[{ required: true, message: "Name is required" }]}>
            <Input disabled={!canUpdateProject} />
          </Form.Item>
          <Form.Item name="description" label="Description" extra="Optional">
            <Input.TextArea rows={5} placeholder="Project description" disabled={!canUpdateProject} />
          </Form.Item>
          <Form.Item
            name="landingTab"
            label="Opens on"
            extra="The tab this project opens on. People can still choose their own."
          >
            <Select
              disabled={!canUpdateProject}
              options={[
                { value: "default", label: "Organization default" },
                ...Object.entries(PROJECT_TAB_LABELS).map(([value, label]) => ({ value, label })),
              ]}
            />
          </Form.Item>
          <Form.Item>
            <Flex justify="flex-end">
              <Button type="primary" htmlType="submit" disabled={!canUpdateProject}>
                Save settings
              </Button>
            </Flex>
          </Form.Item>
        </Form>
      </Spin>

      <ProjectDefaultTags orgid={orgid} projectId={projectId} canManage={canUpdateProject} />

      <div className="project-delete-section">
        <Typography.Text type="secondary">
          Deleting this project permanently removes all of its settings and history from Terrakube.
        </Typography.Text>
        <h3 className="project-delete-title">Delete this Project</h3>
        <div className="project-delete-notice">
          <Typography.Text type="secondary">
            <Typography.Text strong>Warning!</Typography.Text> This action cannot be undone.
          </Typography.Text>
          {assignedWorkspacesCount > 0 && (
            <div className="project-delete-workspaces-warning">
              <Typography.Text type="warning">
                This project has {assignedWorkspacesCount} workspace{assignedWorkspacesCount > 1 ? "s" : ""} assigned.
                Please remove all workspaces before deleting this project.
              </Typography.Text>
            </div>
          )}
        </div>
        <Popconfirm
          okButtonProps={{ danger: true }}
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
          disabled={assignedWorkspacesCount > 0 || !manageWorkspace}
        >
          <Button
            type="primary"
            danger
            className="project-delete-button"
            disabled={assignedWorkspacesCount > 0 || !manageWorkspace}
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
  const [activeKey, setActiveKey] = useState<ProjectTab>("overview");
  const landedOn = useRef<string | undefined>(undefined);
  const [summary, setSummary] = useState<ProjectSummaryModel | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const { permissions } = useOrgPermissions();
  const { permissions: projectPermissions } = useProjectPermissions(orgid, id);
  const canManageProject = permissions.manageWorkspace || projectPermissions.manageProject;

  const { loading, execute, error } = useApiRequest({
    action: () => projectService.getProject(orgid!, id!),
    onReturn: (data) => {
      setProject(data);
      // Open on the right tab once per project, so reloading after a save does not move the user.
      if (landedOn.current !== data.id) {
        landedOn.current = data.id;
        setActiveKey(resolveLandingTab(data.landingTab, data.organizationLandingTab));
      }
      const stored = sessionStorage.getItem(ORGANIZATION_NAME);
      if (stored) setOrganizationName(stored);
    },
  });

  useEffect(() => {
    execute();
  }, [id]);

  const loadSummary = () => {
    if (!orgid || !id) return;
    setSummaryLoading(true);
    projectService
      .getProjectSummary(orgid, id)
      .then((res) => setSummary(res.isError ? null : (res.data ?? null)))
      .finally(() => setSummaryLoading(false));
  };

  useEffect(loadSummary, [orgid, id]);

  const handleTabChange = (key: ProjectTab) => {
    setActiveKey(key);
    if (key === "general" || key === "overview") {
      execute();
      loadSummary();
    }
  };

  const menuItems: MenuItem[] = [
    {
      type: "group",
      label: "Project",
      key: "project-settings",
      children: [
        { key: "overview", label: "Overview" },
        { key: "general", label: "General" },
        { key: "workspaces", label: "Workspaces" },
        { key: "variables", label: "Variables" },
        { key: "policies", label: "Policies" },
        { key: "teams", label: "Teams" },
      ],
    },
  ];

  const renderContent = () => {
    switch (activeKey) {
      case "overview":
        return (
          <ProjectOverview orgid={orgid!} summary={summary} loading={summaryLoading} onOpenTab={handleTabChange} />
        );
      case "workspaces":
        return (
          <ProjectWorkspaces
            key={`${orgid}:${id}`}
            orgid={orgid!}
            projectId={id!}
            canManage={canManageProject}
            canRemove={permissions.manageWorkspace}
          />
        );
      case "variables":
        return <ProjectVariablesTab orgid={orgid!} projectId={id!} canManage={permissions.manageCollection} />;
      case "policies":
        return <ProjectPoliciesTab orgid={orgid!} projectId={id!} />;
      case "teams":
        return <ProjectAccessTab orgid={orgid!} projectId={id!} canManage={canManageProject} />;
      case "general":
      default:
        return (
          <ProjectGeneralSettings
            orgid={orgid!}
            projectId={id!}
            project={project}
            assignedWorkspacesCount={summary?.workspaceCount ?? 0}
            manageWorkspace={permissions.manageWorkspace}
            canUpdateProject={canManageProject}
            onSaved={execute}
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
    >
      <Layout className="project-detail-layout">
        <Sider width={200} className="project-detail-sider">
          <Menu
            mode="inline"
            selectedKeys={[activeKey]}
            className="project-detail-menu"
            items={menuItems}
            onClick={(e) => handleTabChange(e.key as ProjectTab)}
          />
        </Sider>
        <Content className="project-detail-content">{renderContent()}</Content>
      </Layout>
    </PageWrapper>
  );
}
