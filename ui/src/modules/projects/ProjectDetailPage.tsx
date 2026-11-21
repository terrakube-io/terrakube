import { DeleteOutlined } from "@ant-design/icons";
import { Alert, Button, Flex, Form, Input, Select, Space, Spin, Typography, message } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import PageWrapper from "@/components/layout/PageWrapper/PageWrapper";
import projectService from "./projectService";
import { useProjectPermissions } from "@/modules/permissions/useProjectPermissions";
import ProjectWorkspaces from "./ProjectWorkspaces";
import ProjectAccessTab from "./ProjectAccessTab";
import ProjectDefaultTags from "./ProjectDefaultTags";
import ProjectDeleteDialog from "./ProjectDeleteDialog";
import ProjectEffectiveVariables from "./ProjectEffectiveVariables";
import ProjectOverview from "./ProjectOverview";
import ProjectPoliciesTab from "./ProjectPoliciesTab";
import ProjectVariablesTab from "./ProjectVariablesTab";
import { PROJECT_TAB_LABELS, ProjectTab, resolveLandingTab } from "./projectLandingTab";
import { SECTION_LABELS, projectPath, sectionForTab, sectionFromParam } from "./projectNav";
import useApiRequest from "@/modules/api/useApiRequest";
import { ProjectModel, ProjectSummaryModel } from "@/domain/types";
import { ORGANIZATION_NAME } from "../../config/actionTypes";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import { PermissionErrorMessage } from "@/components/feedback/PermissionErrorMessage";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import "./Projects.css";

type Props = {
  organizationName: string;
  setOrganizationName: React.Dispatch<React.SetStateAction<string>>;
};

type ProjectForm = {
  name: string;
  description?: string;
  landingTab?: string;
  defaultTerraformVersion?: string;
  defaultExecutionMode?: string;
};

type GeneralProps = {
  orgid: string;
  projectId: string;
  project: ProjectModel | null;
  assignedWorkspacesCount: number;
  manageWorkspace: boolean;
  canUpdateProject: boolean;
  canManageCollection: boolean;
  onSaved: () => void;
};

function ProjectGeneralSettings({
  orgid,
  projectId,
  project,
  assignedWorkspacesCount,
  manageWorkspace,
  canUpdateProject,
  canManageCollection,
  onSaved,
}: GeneralProps) {
  const [waiting, setWaiting] = useState(false);
  const [form] = Form.useForm<ProjectForm>();
  const navigate = useNavigate();
  const [deleteOpen, setDeleteOpen] = useState(false);

  useEffect(() => {
    if (project) {
      form.setFieldsValue({
        name: project.name,
        description: project.description,
        landingTab: project.landingTab ?? "default",
        defaultTerraformVersion: project.defaultTerraformVersion,
        defaultExecutionMode: project.defaultExecutionMode ?? "default",
      });
    }
  }, [project, form]);

  const onFinish = async (values: ProjectForm) => {
    setWaiting(true);
    try {
      await projectService.updateProject(orgid, projectId, {
        ...values,
        landingTab: !values.landingTab || values.landingTab === "default" ? null : values.landingTab,
        defaultTerraformVersion: values.defaultTerraformVersion?.trim() || null,
        defaultExecutionMode:
          !values.defaultExecutionMode || values.defaultExecutionMode === "default"
            ? null
            : values.defaultExecutionMode,
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
          <Typography.Title level={5}>Defaults for new workspaces</Typography.Title>
          <Typography.Paragraph type="secondary">
            Copied onto a workspace when it is created in this project. Existing workspaces are not changed.
          </Typography.Paragraph>
          <Form.Item
            name="defaultTerraformVersion"
            label="Terraform version"
            extra={
              project?.defaultTerraformVersion
                ? "Defaulted from project"
                : "Not set: the create form suggests the latest"
            }
          >
            <Input disabled={!canUpdateProject} placeholder="For example 1.9.8" maxLength={32} />
          </Form.Item>
          <Form.Item
            name="defaultExecutionMode"
            label="Execution mode"
            extra={
              project?.defaultExecutionMode
                ? "Defaulted from project"
                : `Defaulted from organisation${project?.organizationExecutionMode ? ` (${project.organizationExecutionMode})` : ""}`
            }
          >
            <Select
              disabled={!canUpdateProject}
              options={[
                { value: "default", label: "Organization default" },
                { value: "remote", label: "Remote" },
                { value: "local", label: "Local" },
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

      <ProjectVariablesTab orgid={orgid} projectId={projectId} canManage={canManageCollection} />

      <ProjectEffectiveVariables orgid={orgid} projectId={projectId} />

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
        </div>
        <Button
          type="primary"
          danger
          className="project-delete-button"
          disabled={!manageWorkspace}
          onClick={() => setDeleteOpen(true)}
        >
          <Space>
            <DeleteOutlined />
            Delete Project
          </Space>
        </Button>
        <ProjectDeleteDialog
          orgid={orgid}
          projectId={projectId}
          projectName={project?.name ?? ""}
          workspaceCount={assignedWorkspacesCount}
          open={deleteOpen}
          onClose={() => setDeleteOpen(false)}
          onDeleted={() => navigate(`/organizations/${orgid}/projects`)}
        />
      </div>
    </div>
  );
}

export default function ProjectDetailPage({ organizationName, setOrganizationName }: Props) {
  const { orgid, id, section: sectionParam } = useParams();
  const navigate = useNavigate();
  const section = sectionFromParam(sectionParam);
  const [project, setProject] = useState<ProjectModel | null>(null);
  const landedOn = useRef<string | undefined>(undefined);
  const [summary, setSummary] = useState<ProjectSummaryModel | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [summaryFailed, setSummaryFailed] = useState(false);
  const { permissions } = useOrgPermissions();
  const { permissions: projectPermissions } = useProjectPermissions(orgid, id);
  const canManageProject = permissions.manageWorkspace || projectPermissions.manageProject;

  const { loading, execute, error } = useApiRequest({
    action: () => projectService.getProject(orgid!, id!),
    onReturn: (data) => {
      setProject(data);
      // A bare project address opens on the right tab once, so reloading after a save does not move the user.
      if (landedOn.current !== data.id) {
        landedOn.current = data.id;
        if (!sectionParam) {
          const target = sectionForTab(resolveLandingTab(data.landingTab, data.organizationLandingTab));
          if (target !== "overview") navigate(projectPath(orgid!, data.id, target), { replace: true });
        }
      }
      const stored = sessionStorage.getItem(ORGANIZATION_NAME);
      if (stored) setOrganizationName(stored);
    },
  });

  useEffect(() => {
    execute();
  }, [id]);

  const loadSummary = useCallback(() => {
    if (!orgid || !id) return;
    setSummaryLoading(true);
    projectService
      .getProjectSummary(orgid, id)
      .then((res) => {
        setSummaryFailed(res.isError);
        setSummary(res.isError ? null : (res.data ?? null));
      })
      .finally(() => setSummaryLoading(false));
  }, [orgid, id]);

  useEffect(loadSummary, [loadSummary]);

  // Counts shown on Overview and in Settings change when something is moved or edited elsewhere.
  useEffect(() => {
    if (section === "overview" || section === "settings") loadSummary();
  }, [section, loadSummary]);

  const openTab = (tab: ProjectTab) => navigate(projectPath(orgid!, id!, sectionForTab(tab)));

  const renderContent = () => {
    switch (section) {
      case "workspaces":
        return (
          <ProjectWorkspaces
            key={`${orgid}:${id}`}
            orgid={orgid!}
            projectId={id!}
            projectName={project?.name}
            canManage={canManageProject}
          />
        );
      case "policies":
        return <ProjectPoliciesTab orgid={orgid!} projectId={id!} />;
      case "teams":
        return <ProjectAccessTab orgid={orgid!} projectId={id!} canManage={canManageProject} />;
      case "settings":
        return (
          <ProjectGeneralSettings
            orgid={orgid!}
            projectId={id!}
            project={project}
            assignedWorkspacesCount={summary?.workspaceCount ?? 0}
            manageWorkspace={permissions.manageWorkspace}
            canUpdateProject={canManageProject}
            canManageCollection={permissions.manageCollection}
            onSaved={execute}
          />
        );
      case "overview":
      default:
        return (
          <ProjectOverview
            orgid={orgid!}
            summary={summary}
            loading={summaryLoading}
            failed={summaryFailed}
            onRetry={loadSummary}
            onOpenTab={openTab}
          />
        );
    }
  };

  const level = projectPermissions.accessLevel;
  const through = projectPermissions.grantedThrough;

  return (
    <PageWrapper
      title={project?.name ?? "Project"}
      subTitle={project?.description || `Project in the ${organizationName} organization`}
      loadingText="Loading project..."
      loading={loading}
      error={error}
      breadcrumbs={[
        { label: organizationName, path: "/" },
        { label: "Projects", path: `/organizations/${orgid}/projects` },
        { label: project?.name ?? "Project", path: projectPath(orgid!, id!) },
        ...(section === "overview"
          ? []
          : [{ label: SECTION_LABELS[section], path: projectPath(orgid!, id!, section) }]),
      ]}
      actions={
        <Space>
          <Button disabled={!canManageProject} onClick={() => navigate(projectPath(orgid!, id!, "settings"))}>
            Edit
          </Button>
          <Button
            type="primary"
            disabled={!permissions.manageWorkspace && !projectPermissions.manageWorkspace}
            onClick={() => navigate(`/organizations/${orgid}/workspaces/create?projectId=${id}`)}
          >
            New workspace
          </Button>
        </Space>
      }
    >
      {level && (
        <Alert
          type="info"
          showIcon={false}
          className="project-access-banner"
          message={
            <>
              Your access in this project: <strong>{level.charAt(0).toUpperCase() + level.slice(1)}</strong>
              {through.length > 0 && (
                <>
                  , through {through.length === 1 ? "team" : "teams"} <strong>{through.join(", ")}</strong>
                </>
              )}
              .{" "}
              {canManageProject
                ? "You can manage access and delete the project."
                : "You cannot manage access or delete the project."}
            </>
          }
        />
      )}
      {renderContent()}
    </PageWrapper>
  );
}
