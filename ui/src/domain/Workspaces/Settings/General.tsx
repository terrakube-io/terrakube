import { Button, Divider, Form, Input, Select, Spin, Typography, message } from "antd";
import { useEffect, useState } from "react";
import axiosInstance from "../../../config/axiosConfig";
import { Agent, Template, TofuRelease, Workspace } from "../../types";
import { atomicHeader, compareVersions, genericHeader, getIaCIconById, getIaCNameById, iacTypes } from "../Workspaces";

const { Text } = Typography;

type Props = {
  workspaceData: Workspace;
  orgTemplates: Template[];
  manageWorkspace: boolean;
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
};

export const WorkspaceGeneral = ({ workspaceData, orgTemplates, manageWorkspace }: Props) => {
  const organizationId = workspaceData.relationships.organization.data.id;
  const id = workspaceData.id;
  const Option = Select;
  const [selectedIac, setSelectedIac] = useState("");
  const [terraformVersions, setTerraformVersions] = useState<string[]>([]);
  const [agentList, setAgentList] = useState<Agent[]>([]);
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

    // Parallel load: versions and agent list
    Promise.all([axiosInstance.get(versionsApi), axiosInstance.get(`organization/${organizationId}/agent`)]).then(
      ([versionsRes, agentsRes]) => {
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
        setWaiting(false);
      }
    );
  }, [organizationId, workspaceData.attributes?.iacType]);

  const handleIacChange = (iac: string) => {
    setSelectedIac(iac);
    loadVersions(iac);
  };
  const onFinish = (values: UpdateWorkspaceForm) => {
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

    try {
      axiosInstance.post("/operations", body, atomicHeader).then((response) => {
        if (response.status === 200) {
          message.success("workspace updated successfully");
        } else {
          message.error("workspace update failed");
        }
        setWaiting(false);
      });
    } catch (error) {
      console.error("error updating workspace:", error);
      message.error("workspace update failed");
      setWaiting(false);
    }

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

    axiosInstance
      .patch(`/organization/${organizationId}/workspace/${id}/relationships/agent`, bodyAgent, genericHeader)
      .then((response) => {
        if (response.status === 204) {
          console.log("Workspace agent updated successfully");
        } else {
          console.log("Workspace agent update failed");
        }
      });
  };

  return (
    <div style={{ width: "100%" }} className="generalSettings">
      <h1>General Settings</h1>
      <p>
        Adjust the settings for this workspace. These settings control how the workspace behaves, including execution
        mode, IaC configuration, and security options.
      </p>
      <Spin spinning={waiting}>
        <Form
          onFinish={onFinish}
          requiredMark={false}
          initialValues={{
            name: workspaceData.attributes?.name,
            description: workspaceData.attributes?.description,
            folder: workspaceData.attributes?.folder,
            executionMode: workspaceData.attributes?.executionMode,
            iacType: workspaceData.attributes?.iacType,
            branch: workspaceData.attributes?.branch,
            defaultTemplate: workspaceData.attributes?.defaultTemplate,
            executorAgent:
              workspaceData.relationships.agent?.data?.id == null
                ? "default"
                : workspaceData.relationships.agent.data?.id,
          }}
          layout="vertical"
          name="form-settings"
        >
          {/* Section 1: Identity */}
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

          <Form.Item valuePropName="value" name="description" label="Description" extra="Optional">
            <Input.TextArea rows={5} placeholder="Workspace description" disabled={!manageWorkspace} />
          </Form.Item>

          {/* Section 2: Execution Mode */}
          <h2>Execution Mode</h2>
          <Text type="secondary">
            Select the execution mode for this workspace. Remote indicates Terrakube will run plans and applies. Local
            indicates users should run locally with remote state.
          </Text>

          <Form.Item
            name="executionMode"
            label="Execution Mode"
            extra={
              "Local indicates users should run " +
              getIaCNameById(selectedIac || workspaceData.attributes?.iacType) +
              " " +
              "locally with remote state/cloud block and just upload the state to Terrakube. Remote " +
              "indicates Terrakube will run plans and apply. Informational only."
            }
            style={{ marginTop: 16 }}
          >
            <Select defaultValue={workspaceData.attributes.executionMode} disabled={!manageWorkspace}>
              <Option key="remote">remote</Option>
              <Option key="local">local</Option>
            </Select>
          </Form.Item>
          <Form.Item
            name="executorAgent"
            label="Executor agent to run the job"
            extra="Use this option to select which executor agent will run the job remotely"
          >
            <Select
              defaultValue={workspaceData.attributes.moduleSshKey}
              placeholder="select Job Agent"
              disabled={!manageWorkspace}
            >
              {agentList.map(function (agentKey) {
                return <Option key={agentKey?.id}>{agentKey?.attributes?.name}</Option>;
              })}
              <Option key="default">default</Option>
            </Select>
          </Form.Item>

          <Divider />

          {/* Section 3: IaC Configuration */}
          <h2>IaC Configuration</h2>
          <Text type="secondary">Configure the Infrastructure as Code tool and version used for this workspace.</Text>

          <Form.Item
            name="iacType"
            label="Select IaC type "
            extra="IaC type when running the workspace (Example: terraform or tofu) "
            style={{ marginTop: 16 }}
          >
            <Select
              defaultValue={workspaceData.attributes?.iacType}
              onChange={handleIacChange}
              disabled={!manageWorkspace}
            >
              {iacTypes.map(function (iacType) {
                return (
                  <Option key={iacType.id}>
                    {getIaCIconById(iacType.id)} {iacType.name}{" "}
                  </Option>
                );
              })}
            </Select>
          </Form.Item>
          <Form.Item
            name="terraformVersion"
            label={getIaCNameById(selectedIac || workspaceData.attributes?.iacType) + " Version"}
            extra={
              "The version of " +
              getIaCNameById(selectedIac || workspaceData.attributes?.iacType) +
              " to use for this workspace. Upon creating this workspace, the latest version was selected and will be used until it is changed manually. It will not upgrade automatically."
            }
          >
            <Select defaultValue={workspaceData.attributes?.terraformVersion} disabled={!manageWorkspace}>
              {terraformVersions.map(function (name) {
                return <Option key={name}>{name}</Option>;
              })}
            </Select>
          </Form.Item>
          <Form.Item
            name="folder"
            label={getIaCNameById(selectedIac || workspaceData.attributes?.iacType) + " Working Directory"}
            extra={
              "The directory that " +
              getIaCNameById(selectedIac || workspaceData.attributes?.iacType) +
              " will execute within. This defaults to the root of your repository and is typically set to a subdirectory matching the environment when multiple environments exist within the same repository."
            }
          >
            <Input disabled={!manageWorkspace} />
          </Form.Item>
          <Form.Item
            name="branch"
            label="Default Branch"
            tooltip="The branch from which the runs are kicked off, this is used for runs issued from the UI."
            extra="Don't update the value when using CLI Driven workflows. This is only used in VCS driven workflow."
          >
            <Input disabled={!manageWorkspace} />
          </Form.Item>

          <Divider />

          {/* Section 4: Default Template */}
          <h2>Default Template</h2>
          <Text type="secondary">Configure the default template used when a git push event triggers a run.</Text>

          <Form.Item
            name="defaultTemplate"
            label="Default template when doing a git push to the repository"
            extra="Default template when doing a git push to the repository"
            style={{ marginTop: 16 }}
          >
            <Select
              defaultValue={workspaceData.attributes.defaultTemplate}
              placeholder="select default template"
              disabled={!manageWorkspace}
            >
              {orgTemplates.map(function (template) {
                return <Option key={template?.id}>{template?.attributes?.name}</Option>;
              })}
            </Select>
          </Form.Item>

          <Divider />

          <Form.Item>
            <Button type="primary" htmlType="submit" disabled={!manageWorkspace}>
              Save settings
            </Button>
          </Form.Item>
        </Form>
      </Spin>
    </div>
  );
};
