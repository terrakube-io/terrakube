import { AutoComplete, Checkbox, Form, Input, Select, Space, Spin, message } from "antd";
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
  gitRepoUrlPattern,
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
import DeleteConfirmationModal from "@/components/modals/DeleteConfirmationModal/DeleteConfirmationModal";

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
  restrictApplyToConfiguredBranch?: boolean;
  requireApproveToApply?: boolean;
  executorAgent?: string;
  project?: string;
  vcs?: string;
  source?: string;
};

export const WorkspaceGeneral = ({ workspaceData, orgTemplates, manageWorkspace, onWorkspaceUpdate }: Props) => {
  const organizationId = workspaceData.relationships.organization.data.id;
  const id = workspaceData.id;
  const { Option } = Select;
  const [selectedIac, setSelectedIac] = useState("");
  const { permissions: orgPermissions } = useOrgPermissions();
  // Terrakube before 2.34.0 does not know the setting and does not report it.
  const supportsBranchRestriction = workspaceData.attributes?.restrictApplyToConfiguredBranch !== undefined;
  const supportsApproveRule = workspaceData.attributes?.requireApproveToApply !== undefined;
  const [terraformVersions, setTerraformVersions] = useState<string[]>([]);
  const [agentList, setAgentList] = useState<Agent[]>([]);
  const [projectList, setProjectList] = useState<ProjectModel[]>([]);
  const [vcsList, setVcsList] = useState<VcsModel[]>([]);
  const [waiting, setWaiting] = useState(false);
  // Values held back until the user confirms that the webhook on the old repository gets deleted.
  const [pendingValues, setPendingValues] = useState<UpdateWorkspaceForm | null>(null);
  const webhookId = workspaceData.relationships?.webhook?.data?.id;
  const initialVcsId = workspaceData.relationships?.vcs?.data?.id ?? "public";
  // CLI-driven workspaces have no repository to point at.
  const vcsDriven = workspaceData.attributes?.source !== "empty";

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
  const onFinish = (values: UpdateWorkspaceForm) => {
    const repoChanged = values.source !== undefined && values.source !== workspaceData.attributes?.source;
    const vcsChanged = values.vcs !== undefined && values.vcs !== initialVcsId;
    // The webhook lives on the old repository and was registered with the old connection, so it would keep
    // starting runs from the wrong repository. Delete it first: the API removes the remote hook using the
    // workspace's current repository and connection.
    if (webhookId && (repoChanged || vcsChanged)) {
      setPendingValues(values);
      return;
    }
    return saveWorkspace(values, false);
  };

  const saveWorkspace = async (values: UpdateWorkspaceForm, deleteWebhook: boolean) => {
    setWaiting(true);
    if (deleteWebhook) {
      try {
        await axiosInstance.delete(`organization/${organizationId}/workspace/${id}/webhook/${webhookId}`, {
          headers: { "Content-Type": undefined },
        });
      } catch (error: any) {
        // 404 means the row is already gone; remote cleanup may still have failed.
        if (error?.response?.status !== 404) {
          message.error(`Couldn't delete the webhook, the workspace was not changed: ${getErrorMessage(error)}`);
          setWaiting(false);
          return;
        }
      }
      onWorkspaceUpdate?.();
    }
    const updateFailureMessage = deleteWebhook
      ? "Workspace update failed after the webhook was deleted. Set it up again under Webhook."
      : "Workspace update failed";
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
              source: values.source,
              defaultTemplate: values.defaultTemplate,
              // Only sent to an API that has the setting (it reports it), so a newer UI still saves against an older
              // API. A manager limited to this workspace cannot change it, and the API refuses a change from them.
              ...(supportsBranchRestriction && orgPermissions.manageWorkspace
                ? { restrictApplyToConfiguredBranch: Boolean(values.restrictApplyToConfiguredBranch) }
                : {}),
              ...(supportsApproveRule && orgPermissions.manageWorkspace
                ? { requireApproveToApply: Boolean(values.requireApproveToApply) }
                : {}),
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
        message.error(updateFailureMessage);
      }
    } catch (error) {
      console.error("Error updating workspace:", error);
      message.error(deleteWebhook ? `${updateFailureMessage} ${getErrorMessage(error)}` : getErrorMessage(error));
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
        description="Adjust how this workspace behaves: identity, execution mode, IaC tool and version, VCS provider and repository, default template and project."
      />
      <Spin spinning={waiting}>
        <SettingsForm
          onFinish={onFinish}
          saveDisabled={!manageWorkspace}
          initialValues={{
            name: workspaceData.attributes?.name,
            description: workspaceData.attributes?.description,
            folder: workspaceData.attributes?.folder,
            executionMode: workspaceData.attributes?.executionMode || "remote",
            terraformVersion: workspaceData.attributes?.terraformVersion,
            iacType: workspaceData.attributes?.iacType,
            branch: workspaceData.attributes?.branch,
            defaultTemplate: workspaceData.attributes?.defaultTemplate,
            restrictApplyToConfiguredBranch: workspaceData.attributes?.restrictApplyToConfiguredBranch ?? false,
            requireApproveToApply: workspaceData.attributes?.requireApproveToApply ?? false,
            executorAgent:
              workspaceData.relationships.agent?.data?.id == null
                ? "default"
                : workspaceData.relationships.agent.data?.id,
            project: workspaceData.relationships.project?.data?.id ?? "none",
            vcs: workspaceData.relationships?.vcs?.data?.id ?? "public",
            source: workspaceData.attributes?.source,
          }}
          name="form-settings"
        >
          <SettingsSection title="Identity">
            <IdField id="workspace-id" value={id} copiedMessage="Workspace ID copied" />
            <Form.Item
              name="name"
              rules={[
                { required: true },
                { max: 128 },
                {
                  pattern: /^[A-Za-z0-9_-]+$/,
                  message: "Only dashes, underscores, and alphanumeric characters are permitted.",
                },
              ]}
              label="Name"
            >
              <Input disabled={!manageWorkspace} maxLength={128} />
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
            {supportsBranchRestriction && (
              <Form.Item
                name="restrictApplyToConfiguredBranch"
                valuePropName="checked"
                extra={
                  vcsDriven
                    ? "Plan-only runs and terraform plan from the CLI still work on any branch. A pull request or a push to another branch can only run a plan-only template, and a release only applies commits that are on the default branch. Only a manager of every workspace in the organization can change this."
                    : "This workspace is CLI-driven: its configuration is uploaded by the Terraform CLI, not read from a branch, so there is no branch to restrict. Connect a repository to use this setting."
                }
              >
                <Checkbox disabled={!manageWorkspace || !orgPermissions.manageWorkspace || !vcsDriven}>
                  Only run apply and destroy from the default branch
                </Checkbox>
              </Form.Item>
            )}
            {supportsApproveRule && (
              <Form.Item
                name="requireApproveToApply"
                valuePropName="checked"
                extra="A team that can only plan can still queue a run whose template asks for an approval first, and cannot start an apply that has no approval step. Starting a destroy with no approval step always needs the destroy permission. Only a manager of every workspace in the organization can change this."
              >
                <Checkbox disabled={!manageWorkspace || !orgPermissions.manageWorkspace}>
                  Require the approve permission to start an apply with no approval step
                </Checkbox>
              </Form.Item>
            )}
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
            {vcsDriven && (
              <Form.Item
                name="source"
                label="Repository URL"
                normalize={(value) => value?.trim()}
                extra="The Git repository this workspace clones, over https or ssh. The configured branch must exist in the new repository. State, variables and run history are kept when it changes."
                rules={[
                  { required: true, pattern: gitRepoUrlPattern, message: "Enter a valid https or ssh Git URL." },
                  // "empty" marks a CLI-driven workspace; it is not a repository.
                  {
                    validator: (_, value) =>
                      value?.trim() === "empty" ? Promise.reject(new Error("Enter a Git URL.")) : Promise.resolve(),
                  },
                ]}
              >
                <Input disabled={!manageWorkspace} placeholder="https://github.com/org/repo.git" />
              </Form.Item>
            )}
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
      <DeleteConfirmationModal
        open={pendingValues !== null}
        title="Delete webhook"
        message="This workspace's webhook is set up on the current repository and VCS connection. Saving deletes it, and repository events stop starting runs until you set it up again under Webhook."
        okText="Delete webhook and save"
        onConfirm={() => {
          if (pendingValues) saveWorkspace(pendingValues, true);
          setPendingValues(null);
        }}
        onCancel={() => setPendingValues(null)}
      />
    </div>
  );
};
