import { ExclamationCircleOutlined, InfoCircleOutlined } from "@ant-design/icons";
import { Alert, Button, Form, Input, Modal, Typography, message } from "antd";
import { useState } from "react";
import axiosInstance from "../../../config/axiosConfig";
import { Workspace } from "../../types";

const { Text } = Typography;

type Props = {
  workspace: Workspace;
  manageWorkspace: boolean;
  onWorkspaceUpdate: () => void;
};

export const WorkspaceLocking = ({ workspace, manageWorkspace, onWorkspaceUpdate }: Props) => {
  const organizationId = workspace.relationships.organization.data.id;
  const workspaceId = workspace.id;
  const workspaceName = workspace.attributes.name;
  const isLocked = workspace.attributes.locked;
  const lockDescription = workspace.attributes.lockDescription;

  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const handleLock = () => {
    setLoading(true);
    const description = form.getFieldValue("lockDescription") || "";
    const body = {
      data: {
        type: "workspace",
        id: workspaceId,
        attributes: {
          locked: true,
          lockDescription: description,
        },
      },
    };
    axiosInstance
      .patch(`organization/${organizationId}/workspace/${workspaceId}`, body, {
        headers: { "Content-Type": "application/vnd.api+json" },
      })
      .then(() => {
        message.success("Workspace locked successfully");
        onWorkspaceUpdate();
      })
      .catch((error) => {
        const detail = error?.response?.data?.errors?.[0]?.detail || error.message;
        message.error("Failed to lock workspace: " + detail);
      })
      .finally(() => setLoading(false));
  };

  const handleUnlock = () => {
    Modal.confirm({
      title: `Unlock workspace ${workspaceName}`,
      icon: <ExclamationCircleOutlined style={{ color: "#ff4d4f" }} />,
      content: (
        <div>
          <p>
            Unlocking this workspace will allow other users to run Terraform. Be careful: if a remote Terraform run is
            still using the lock, this may lead to inconsistent state.
          </p>
          <p>
            <Text strong>This operation cannot be undone.</Text> Are you sure?
          </p>
        </div>
      ),
      okText: "Yes, unlock workspace",
      okType: "danger",
      cancelText: "Cancel",
      onOk() {
        return new Promise<void>((resolve, reject) => {
          const body = {
            data: {
              type: "workspace",
              id: workspaceId,
              attributes: {
                locked: false,
                lockDescription: "",
              },
            },
          };
          axiosInstance
            .patch(`organization/${organizationId}/workspace/${workspaceId}`, body, {
              headers: { "Content-Type": "application/vnd.api+json" },
            })
            .then(() => {
              message.success("Workspace unlocked successfully");
              onWorkspaceUpdate();
              resolve();
            })
            .catch((error) => {
              const detail = error?.response?.data?.errors?.[0]?.detail || error.message;
              message.error("Failed to unlock workspace: " + detail);
              reject();
            });
        });
      },
    });
  };

  return (
    <div className="generalSettings">
      <h1>Locking</h1>

      {isLocked ? (
        <>
          <Alert
            message={
              <span>
                This workspace is <Text strong>currently locked</Text>.
                {lockDescription
                  ? ` Reason: ${lockDescription}`
                  : " No reason was provided for locking this workspace."}
              </span>
            }
            type="info"
            showIcon
            icon={<InfoCircleOutlined />}
            style={{ marginBottom: 24 }}
          />
          <p>
            <Text type="secondary">
              If you've finished making changes, you can manually unlock this workspace to allow Terraform runs to
              proceed.
            </Text>
          </p>
          <Button type="primary" onClick={handleUnlock} loading={loading} disabled={!manageWorkspace}>
            Unlock {workspaceName}
          </Button>
        </>
      ) : (
        <>
          <p>
            <Text type="secondary">
              This workspace is not currently locked. All operations can proceed normally. You can lock this workspace
              to prevent Terraform runs.
            </Text>
          </p>

          <Form form={form} layout="vertical" style={{ maxWidth: 600, marginBottom: 24 }}>
            <Form.Item
              name="lockDescription"
              label="Lock reason (optional)"
              extra="Provide a reason for locking this workspace so other team members understand why."
            >
              <Input.TextArea rows={3} placeholder="Lock description details" disabled={!manageWorkspace} />
            </Form.Item>
          </Form>

          <Button type="primary" onClick={handleLock} loading={loading} disabled={!manageWorkspace}>
            Lock {workspaceName}
          </Button>
        </>
      )}
    </div>
  );
};
