import { Button, Form, Select, Spin, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { SshKey, Workspace } from "../../types";
import { atomicHeader } from "../Workspaces";

const { Text } = Typography;

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
};

export const WorkspaceSSHKey = ({ workspace, manageWorkspace }: Props) => {
  const organizationId = workspace.relationships.organization.data.id;
  const id = workspace.id;
  const Option = Select;
  const [sshKeys, setSSHKeys] = useState<SshKey[]>([]);
  const [waiting, setWaiting] = useState(false);

  const loadSSHKeys = () => {
    axiosInstance.get(`organization/${organizationId}/ssh`).then((response) => {
      setSSHKeys(response.data.data);
    });
  };

  useEffect(() => {
    loadSSHKeys();
  }, []);

  const onFinish = (values: { moduleSshKey?: string }) => {
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
              moduleSshKey: values.moduleSshKey || "",
            },
          },
        },
      ],
    };

    axiosInstance
      .post("/operations", body, atomicHeader)
      .then((response) => {
        if (response.status === 200) {
          message.success("SSH key updated successfully");
        } else {
          message.error("SSH key update failed");
        }
        setWaiting(false);
      })
      .catch(() => {
        message.error("SSH key update failed");
        setWaiting(false);
      });
  };

  return (
    <div className="generalSettings">
      <h1>SSH Key</h1>
      <Text type="secondary">
        Optionally choose a private SSH key for downloading Terraform modules from Git-based module sources. This key is
        not used for cloning the workspace VCS repository or for provisioner connections.
      </Text>
      <p style={{ marginTop: 4 }}>
        <Link to={`/organizations/${organizationId}/settings`}>Manage SSH keys for this organization.</Link>
      </p>

      <Spin spinning={waiting}>
        <Form
          onFinish={onFinish}
          requiredMark={false}
          initialValues={{
            moduleSshKey: workspace.attributes?.moduleSshKey || "",
          }}
          layout="vertical"
        >
          <Form.Item name="moduleSshKey" label="SSH key" style={{ marginTop: 16 }}>
            <Select
              defaultValue={workspace.attributes?.moduleSshKey || ""}
              placeholder="(No SSH key)"
              disabled={!manageWorkspace}
            >
              <Option key="" value="">
                (No SSH key)
              </Option>
              {sshKeys.map(function (sshKey) {
                return (
                  <Option key={sshKey?.id} value={sshKey?.id}>
                    {sshKey?.attributes?.name}
                  </Option>
                );
              })}
            </Select>
          </Form.Item>

          <Form.Item>
            <Button type="primary" htmlType="submit" disabled={!manageWorkspace}>
              Update SSH key
            </Button>
          </Form.Item>
        </Form>
      </Spin>
    </div>
  );
};
