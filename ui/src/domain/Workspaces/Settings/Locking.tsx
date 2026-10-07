import { UNLOCK_CONFIRM_TEXT } from "./lockingCopy";
import { Alert, Button, Form, Input, Modal, message } from "antd";
import { useState } from "react";
import axiosInstance, { getErrorMessage } from "../../../config/axiosConfig";
import { Workspace } from "../../types";
import { SettingsPageHeader } from "@/components/settings/SettingsPageHeader";
import { SettingsForm } from "@/components/settings/SettingsForm";
import "../Workspaces.css";

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
  // Hook-based modal so the confirmation follows the app theme (light/dark).
  const [modal, contextHolder] = Modal.useModal();

  const setLock = (locked: boolean, description: string) =>
    axiosInstance.patch(
      `organization/${organizationId}/workspace/${workspaceId}`,
      { data: { type: "workspace", id: workspaceId, attributes: { locked, lockDescription: description } } },
      { headers: { "Content-Type": "application/vnd.api+json" } }
    );

  const handleLock = (values: { lockDescription?: string }) => {
    setLoading(true);
    setLock(true, values.lockDescription || "")
      .then(() => {
        message.success("Workspace locked");
        onWorkspaceUpdate();
      })
      .catch((error) => message.error(`Could not lock the workspace: ${getErrorMessage(error)}`))
      .finally(() => setLoading(false));
  };

  const handleUnlock = () => {
    modal.confirm({
      title: `Unlock ${workspaceName}?`,
      content: UNLOCK_CONFIRM_TEXT,
      okText: "Unlock workspace",
      cancelText: "Cancel",
      onOk: () =>
        setLock(false, "")
          .then(() => {
            message.success("Workspace unlocked");
            onWorkspaceUpdate();
          })
          .catch((error) => {
            message.error(`Could not unlock the workspace: ${getErrorMessage(error)}`);
            throw error;
          }),
    });
  };

  return (
    <div className="generalSettings">
      {contextHolder}
      <SettingsPageHeader
        title="Locking"
        description="Prevent new runs from starting on this workspace while it is locked."
        divider={false}
      />

      {isLocked ? (
        <SettingsForm showSave={false}>
          <Alert
            type="warning"
            showIcon
            title="This workspace is locked. New runs will not start until it is unlocked."
            description={lockDescription ? `Reason: ${lockDescription}` : "No reason was given."}
            className="workspace-locked-alert"
          />
          <Button onClick={handleUnlock} disabled={!manageWorkspace}>
            Unlock workspace
          </Button>
        </SettingsForm>
      ) : (
        <SettingsForm
          onFinish={handleLock}
          disabled={!manageWorkspace}
          saveDisabled={!manageWorkspace}
          saveLabel="Lock workspace"
          saving={loading}
        >
          <Form.Item
            name="lockDescription"
            label="Lock reason"
            extra="Optional. Shown on the workspace overview while it is locked."
          >
            <Input.TextArea rows={3} placeholder="e.g. Upgrading the provider, do not run" />
          </Form.Item>
        </SettingsForm>
      )}
    </div>
  );
};
