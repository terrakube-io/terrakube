import { InfoCircleOutlined } from "@ant-design/icons";
import { Button, Form, Input, Select, Spin, Switch, Typography, message } from "antd";
import { useEffect, useState } from "react";
import axiosInstance from "../../../config/axiosConfig";
import { Agent, SshKey, Template, TofuRelease, Workspace } from "../../types";
import { atomicHeader, compareVersions, genericHeader, getIaCIconById, getIaCNameById, iacTypes } from "../Workspaces";

type Props = {
  workspaceData: Workspace;
  orgTemplates: Template[];
  manageWorkspace: boolean;
};

type UpdateWorkspaceForm = {
  name: string;
  description?: string;
  folder?: string;
  locked: boolean;
  lockDescription?: string;
  executionMode: string;
  moduleSshKey?: string;
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
  const [sshKeys, setSSHKeys] = useState<SshKey[]>([]);
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
  const loadSSHKeys = () => {
    axiosInstance.get(`organization/${organizationId}/ssh`).then((response) => {
      setSSHKeys(response.data.data);
    });
  };
  const loadAgentlist = () => {
    axiosInstance.get(`organization/${organizationId}/agent`).then((response) => {
      setAgentList(response.data.data);
    });
  };

  useEffect(() => {
    setWaiting(true);
    loadVersions(workspaceData.attributes?.iacType);
    loadSSHKeys();
    loadAgentlist();
    setWaiting(false);
  }, []);

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
              locked: values.locked,
              lockDescription: values.lockDescription,
              executionMode: values.executionMode,
              moduleSshKey: values.moduleSshKey,
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
      <Spin spinning={waiting}>
        <Form
          onFinish={onFinish}
          initialValues={{
            name: workspaceData.attributes?.name,
            description: workspaceData.attributes?.description,
            folder: workspaceData.attributes?.folder,
            locked: workspaceData.attributes?.locked,
            lockDescription: workspaceData.attributes.lockDescription,
            moduleSshKey: workspaceData.attributes?.moduleSshKey,
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

          <Form.Item valuePropName="value" name="description" label="Description">
            <Input.TextArea placeholder="Workspace description" disabled={!manageWorkspace} />
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
            <Select
              defaultValue={workspaceData.attributes?.terraformVersion}
              style={{ width: 250 }}
              disabled={!manageWorkspace}
            >
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
          <Form.Item
            name="locked"
            valuePropName="checked"
            label="Lock Workspace"
            tooltip={{
              title: "Lock Workspace",
              icon: <InfoCircleOutlined />,
            }}
          >
            <Switch disabled={!manageWorkspace} />
          </Form.Item>
          <Form.Item valuePropName="value" name="lockDescription" label="Setup custom lock description message">
            <Input.TextArea placeholder="Lock description details" disabled={!manageWorkspace} />
          </Form.Item>
          <Form.Item
            name="iacType"
            label="Select IaC type "
            extra="IaC type when running the workspace (Example: terraform or tofu) "
          >
            <Select
              defaultValue={workspaceData.attributes?.iacType}
              style={{ width: 250 }}
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
            name="executionMode"
            label="Execution Mode"
            extra={
              "Local indicates users should run " + getIaCNameById(selectedIac || workspaceData.attributes?.iacType) + " " +
              "locally with remote state/cloud block and just upload the state to Terrakube. Remote " +
              "indicates Terrakube will run plans and apply. Informational only."
            }
          >
            <Select
              defaultValue={workspaceData.attributes.executionMode}
              style={{ width: 250 }}
              disabled={!manageWorkspace}
            >
              <Option key="remote">remote</Option>
              <Option key="local">local</Option>
            </Select>
          </Form.Item>
          <Form.Item
            name="defaultTemplate"
            label="Default template when doing a git push to the repository"
            extra="Default template when doing a git push to the repository"
          >
            <Select
              defaultValue={workspaceData.attributes.defaultTemplate}
              placeholder="select default template"
              style={{ width: 250 }}
              disabled={!manageWorkspace}
            >
              {orgTemplates.map(function (template) {
                return <Option key={template?.id}>{template?.attributes?.name}</Option>;
              })}
            </Select>
          </Form.Item>
          <Form.Item
            name="moduleSshKey"
            label="Download modules SSH Keys"
            extra="Use this option to add a SSH key to allow module downloads"
          >
            <Select
              defaultValue={workspaceData.attributes.moduleSshKey}
              placeholder="select SSH Key"
              style={{ width: 250 }}
              disabled={!manageWorkspace}
            >
              {sshKeys.map(function (sshKey) {
                return <Option key={sshKey?.id}>{sshKey?.attributes?.name}</Option>;
              })}
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
              style={{ width: 250 }}
              disabled={!manageWorkspace}
            >
              {agentList.map(function (agentKey) {
                return <Option key={agentKey?.id}>{agentKey?.attributes?.name}</Option>;
              })}
              <Option key="default">default</Option>
            </Select>
          </Form.Item>
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
