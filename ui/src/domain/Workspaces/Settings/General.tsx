import { AutoComplete, Form, Input, Select, Space, Spin, message } from "antd";
import { GlobalOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import axiosInstance, { getErrorMessage } from "../../../config/axiosConfig";
import { Agent, Template, TofuRelease, VcsModel, Workspace } from "../../types";
import {
  atomicHeader,
  compareVersions,
  genericHeader,
  getIaCIconById,
  getIaCNameById,
  iacTypes,
  validateTerraformVersion,
} from "../Workspaces";
import projectService from "@/modules/projects/projectService";
import { ProjectModel } from "@/domain/types";
import { useOrgPermissions } from "@/modules/permissions/useOrgPermissions";
import SettingsSection from "@/components/settings/SettingsSection/SettingsSection";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
import { RadioChoices } from "@/components/settings/RadioChoices";
import { IdField } from "@/components/settings/IdField";
import VcsLogo from "@/components/display/VcsLogo";

type Props = {
  workspaceData: Workspace;
  orgTemplates: Template[];
  manageWorkspace: boolean;
  onWorkspaceUpdate?: () => void;
};

type UpdateWorkspaceForm = {
  name: string;
  description?: string;
  folder?: string;
  executionMode: string;
  terraformVersion: string;
  iacType: string;
  branch: string;
  defaultTemplate?: string;
  executorAgent?: string;
  project?: string;
  vcs?: string;
};

export const WorkspaceGeneral = ({ workspaceData, orgTemplates, manageWorkspace, onWorkspaceUpdate }: Props) => {
  const organizationId = workspaceData.relationships.organization.data.id;
  const id = workspaceData.id;
  const { Option } = Select;
  const [selectedIac, setSelectedIac] = useState("");
  const { permissions: orgPermissions } = useOrgPermissions();
  const [terraformVersions, setTerraformVersions] = useState<string[]>([]);
  const [agentList, setAgentList] = useState<Agent[]>([]);
  const [projectList, setProjectList] = useState<ProjectModel[]>([]);
  const [vcsList, setVcsList] = useState<VcsModel[]>([]);
  const [waiting, setWaiting] = useState(false);

  const loadVersions = (iacType: string) => {
    const versionsApi = `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/${iacType}/index.json`;
    axiosInstance.get(versionsApi).then((resp) => {
      const tfVersions = [];
      if (iacType === "tofu") {
        resp.data.forEach((release: TofuRelease) => {
          if (!release.tag_name.includes("-")) tfVersions.push(release.tag_name.replace("v", ""));
        });
      } else {
        for (const version in resp.data.versions) {
          if (!version.includes("-")) tfVersions.push(version);
        }
      }
      setTerraformVersions(tfVersions.sort(compareVersions).reverse());
    });
  };

  useEffect(() => {
    setWaiting(true);
    const iacType = workspaceData.attributes?.iacType;
    const versionsApi = `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/${iacType}/index.json`;

    // Parallel load: versions, agent list, projects, and VCS providers
    Promise.all([
      axiosInstance.get(versionsApi),
      axiosInstance.get(`organization/${organizationId}/agent`),
      projectService.listProjects(organizationId),
      axiosInstance.get(`organization/${organizationId}/vcs`),
    ])
      .then(([versionsRes, agentsRes, projectsRes, vcsRes]) => {
        const tfVersions: string[] = [];
        if (iacType === "tofu") {
          versionsRes.data.forEach((release: TofuRelease) => {
            if (!release.tag_name.includes("-")) tfVersions.push(release.tag_name.replace("v", ""));
          });
        } else {
          for (const version in versionsRes.data.versions) {
            if (!version.includes("-")) tfVersions.push(version);
          }
        }
        setTerraformVersions(tfVersions.sort(compareVersions).reverse());
        setAgentList(agentsRes.data.data);
        if (!projectsRes.isError) setProjectList(projectsRes.data);
        setVcsList(vcsRes.data?.data ?? []);
        setWaiting(false);
      })
      .catch((error) => {
        console.error("Failed to load workspace configuration data:", error);
        message.error(getErrorMessage(error));
        setWaiting(false);
      });
  }, [organizationId, workspaceData.attributes?.iacType]);

  const handleIacChange = (iac: string) => {
    setSelectedIac(iac);
    loadVersions(iac);
  };
  const onFinish = async (values: UpdateWorkspaceForm) => {
    setWaiting(true);
    const body = {
      "atomic:operations": [
        {
          op: "update",
          href: `/organization/${organizationId}/workspace/${id}`,
          data: {
            type: "workspace",
            id: id,
            attributes: {
              name: values.name,
              description: values.description,
              folder: values.folder,
              executionMode: values.executionMode,
              terraformVersion: values.terraformVersion,
              iacType: values.iacType,
              branch: values.branch,
              defaultTemplate: values.defaultTemplate,
            },
          },
        },
      ],
    };

    const requests: Promise<any>[] = [axiosInstance.post("/operations", body, atomicHeader)];

    let bodyAgent;

    if (values.executorAgent === "default") {
      bodyAgent = {
        data: null,
      };
    } else {
      bodyAgent = {
        data: {
          type: "agent",
          id: values.executorAgent,
        },
      };
    }

    requests.push(
      axiosInstance.patch(
        `/organization/${organizationId}/workspace/${id}/relationships/agent`,
        bodyAgent,
        genericHeader
      )
    );

    const bodyProject =
      values.project && values.project !== "none" ? { data: { type: "project", id: values.project } } : { data: null };

    requests.push(
      axiosInstance.patch(
        `/organization/${organizationId}/workspace/${id}/relationships/project`,
        bodyProject,
        genericHeader
      )
    );

    const initialVcsId = workspaceData.relationships?.vcs?.data?.id ?? "public";
    if (values.vcs !== undefined && values.vcs !== initialVcsId) {
      const bodyVcs =
        values.vcs && values.vcs !== "public"
          ? {
              data: {
                type: "vcs",
                id: values.vcs,
              },
            }
          : {
              data: null,
            };

      requests.push(
        axiosInstance.patch(`/organization/${organizationId}/workspace/${id}/relationships/vcs`, bodyVcs, genericHeader)
      );
    }

    try {
      const responses = await Promise.all(requests);
      const operationsRes = responses[0];
      if (operationsRes.status === 200) {
        message.success("Workspace updated successfully");
        onWorkspaceUpdate?.();
      } else {
        message.error("Workspace update failed");
      }
    } catch (error) {
      console.error("Error updating workspace:", error);
      message.error(getErrorMessage(error));
    } finally {
      setWaiting(false);
    }
  };

  const iacName = getIaCNameById(selectedIac || workspaceData.attributes?.iacType);

  return (
    <div className="generalSettings">
      <SettingsPageHeader
        docUrl="https://docs.terrakube.io/user-guide/workspaces"
        title="General settings"
        description="Adjust how this workspace behaves: identity, execution mode, IaC tool and version, VCS provider, default template and project."
      />
      <Spin spinning={waiting}>
        <SettingsForm
          onFinish={onFinish}
          saveDisabled={!manageWorkspace}
          initialValues={{
            name: workspaceData.attributes?.name,
            description: workspaceData.attributes?.description,
            folder: workspaceData.attributes?.folder,
            executionMode: workspaceData.attributes?.executionMode,
            terraformVersion: workspaceData.attributes?.terraformVersion,
            iacType: workspaceData.attributes?.iacType,
            branch: workspaceData.attributes?.branch,
            defaultTemplate: workspaceData.attributes?.defaultTemplate,
            executorAgent:
              workspaceData.relationships.agent?.data?.id == null
                ? "default"
                : workspaceData.relationships.agent.data?.id,
            project: workspaceData.relationships.project?.data?.id ?? "none",
            vcs: workspaceData.relationships?.vcs?.data?.id ?? "public",
          }}
          name="form-settings"
        >
          <SettingsSection title="Identity">
            <IdField id="workspace-id" value={id} copiedMessage="Workspace ID copied" />
            <Form.Item
              name="name"
              rules={[
                { required: true },
                {
                  pattern: /^[A-Za-z0-9_-]+$/,
                  message: "Only dashes, underscores, and alphanumeric characters are permitted.",
                },
              ]}
              label="Name"
            >
              <Input disabled={!manageWorkspace} />
            </Form.Item>
            <Form.Item name="description" label="Description" extra="Optional">
              <Input.TextArea
                autoSize={{ minRows: 2, maxRows: 5 }}
                placeholder="Workspace description"
                disabled={!manageWorkspace}
              />
            </Form.Item>
          </SettingsSection>

          <SettingsSection
            title="Execution mode"
            description="Informational only: it tells users where plans and applies are expected to run."
          >
            <Form.Item name="executionMode" label="Execution mode">
              <RadioChoices
                disabled={!manageWorkspace}
                options={[
                  { value: "remote", label: "Remote", help: "Terrakube runs plans and applies." },
                  {
                    value: "local",
                    label: "Local",
                    help: `Users run ${iacName} locally with the remote state or cloud block and upload the state to Terrakube.`,
                  },
                ]}
              />
            </Form.Item>
            <Form.Item
              name="executorAgent"
              label="Executor agent"
              extra="The agent that runs remote jobs for this workspace."
            >
              <Select placeholder="Select an executor agent" disabled={!manageWorkspace}>
                {agentList.map(function (agentKey) {
                  return <Option key={agentKey?.id}>{agentKey?.attributes?.name}</Option>;
                })}
                <Option key="default">default</Option>
              </Select>
            </Form.Item>
          </SettingsSection>

          <SettingsSection
            title="IaC configuration"
            description="Configure the Infrastructure as Code tool and version used for this workspace."
          >
            <Form.Item name="iacType" label="IaC type">
              <Select onChange={handleIacChange} disabled={!manageWorkspace}>
                {iacTypes.map(function (iacType) {
                  return (
                    <Option key={iacType.id}>
                      {getIaCIconById(iacType.id)} {iacType.name}
                    </Option>
                  );
                })}
              </Select>
            </Form.Item>
            <Form.Item
              name="terraformVersion"
              label={`${iacName} version`}
              rules={[{ validator: validateTerraformVersion(terraformVersions) }]}
              extra="It will not upgrade automatically. Version constraints are supported (e.g. ~>1.11.0, >=1.5.7 <1.9.0)."
            >
              <AutoComplete
                disabled={!manageWorkspace}
                options={terraformVersions.map((v) => ({ value: v }))}
                filterOption={(input, option) => (option?.value ?? "").includes(input)}
                placeholder="e.g. 1.11.0 or ~>1.11.0"
              />
            </Form.Item>
            <Form.Item
              name="folder"
              label="Working directory"
              extra={`The directory ${iacName} runs in, relative to the repository root. Use a subdirectory when one repository holds several environments.`}
            >
              <Input disabled={!manageWorkspace} />
            </Form.Item>
            <Form.Item
              name="branch"
              label="Default branch"
              extra="The branch runs started from the UI use. Only applies to the VCS-driven workflow; leave it as is for CLI-driven workspaces."
            >
              <Input disabled={!manageWorkspace} />
            </Form.Item>
          </SettingsSection>

          <SettingsSection
            title="Version control system"
            description="Choose Public if this workspace clones a public Git repository without credentials."
          >
            <Form.Item
              name="vcs"
              label="VCS provider"
              extra={
                vcsList.length === 0 ? (
                  <>
                    No VCS providers configured.{" "}
                    {orgPermissions.manageVcs && (
                      <Link to={`/organizations/${organizationId}/settings/vcs`}>Configure a VCS provider</Link>
                    )}
                  </>
                ) : (
                  <>
                    {orgPermissions.manageVcs && (
                      <Link to={`/organizations/${organizationId}/settings/vcs`}>
                        Manage organization VCS providers
                      </Link>
                    )}
                  </>
                )
              }
            >
              <Select placeholder="Select VCS Provider" disabled={!manageWorkspace}>
                <Option key="public" value="public">
                  <Space>
                    <GlobalOutlined />
                    <span>Public (No VCS connection)</span>
                  </Space>
                </Option>
                {vcsList.map((vcsItem) => (
                  <Option key={vcsItem.id} value={vcsItem.id}>
                    <Space>
                      <VcsLogo type={vcsItem.attributes.vcsType} size={16} />
                      <span>{vcsItem.attributes.name}</span>
                    </Space>
                  </Option>
                ))}
              </Select>
            </Form.Item>
          </SettingsSection>

          <SettingsSection title="Default template">
            <Form.Item
              name="defaultTemplate"
              label="Template"
              extra={
                <>
                  Used by the <code>terrakube apply</code> PR comment command and pre-filled when you start a run.
                </>
              }
            >
              <Select placeholder="Select a default template" disabled={!manageWorkspace}>
                {orgTemplates.map(function (template) {
                  return <Option key={template?.id}>{template?.attributes?.name}</Option>;
                })}
              </Select>
            </Form.Item>
          </SettingsSection>

          <SettingsSection title="Project">
            <Form.Item name="project" label="Project" extra="Optional. Projects group and filter workspaces.">
              <Select placeholder="No project" disabled={!manageWorkspace}>
                {orgPermissions.manageWorkspace && <Option key="none">(No project)</Option>}
                {projectList.map((p) => (
                  <Option key={p.id}>{p.name}</Option>
                ))}
              </Select>
            </Form.Item>
          </SettingsSection>
        </SettingsForm>
      </Spin>
    </div>
  );
};
