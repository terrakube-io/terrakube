import { Alert, Input, Modal, Skeleton, Typography, message } from "antd";
import { useEffect, useState } from "react";
import projectService, { DeletePreflight } from "./projectService";
import { deleteProjectWithContents } from "./projectDelete";

type Props = {
  orgid: string;
  projectId: string;
  projectName: string;
  workspaceCount: number;
  /** Deleting removes what is attached, which needs these rights. Without them the workspaces would move for nothing. */
  canManagePolicies?: boolean;
  canManageCollection?: boolean;
  open: boolean;
  onClose: () => void;
  onDeleted: () => void;
};

const UNREAD: DeletePreflight = {
  teamCount: null,
  attachments: [],
  exemptions: [],
  collections: [],
  defaultTagCount: 0,
  failed: [],
};

const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? "" : "s"}`;

function explain(error: any): string {
  const status = error?.response?.status;
  if (status === 403 || status === 404) return "You do not have permission to remove everything this project contains.";
  return error?.response?.data?.errors?.[0]?.detail ?? error?.message ?? "The project could not be deleted.";
}

/** What deleting a project removes, and a way to do it in one go once the project name has been typed. */
export default function ProjectDeleteDialog({
  orgid,
  projectId,
  projectName,
  workspaceCount,
  canManagePolicies = true,
  canManageCollection = true,
  open,
  onClose,
  onDeleted,
}: Props) {
  const [preflight, setPreflight] = useState<DeletePreflight | null>(null);
  const [typed, setTyped] = useState("");
  const [step, setStep] = useState<string>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (!open) return;
    setTyped("");
    setError(undefined);
    setPreflight(null);
    let current = true;
    projectService
      .getDeletePreflight(orgid, projectId)
      .then((found) => current && setPreflight(found))
      .catch(() => current && setPreflight({ ...UNREAD, failed: ["policy sets", "variable collections"] }));
    return () => {
      current = false;
    };
  }, [open, orgid, projectId]);

  const confirm = async () => {
    if (!preflight) return;
    setError(undefined);
    try {
      await deleteProjectWithContents(orgid, projectId, preflight, setStep);
      message.success("Project deleted successfully");
      onDeleted();
    } catch (err) {
      setError(explain(err));
    } finally {
      setStep(undefined);
    }
  };

  const policyCount = (preflight?.attachments.length ?? 0) + (preflight?.exemptions.length ?? 0);
  // An empty name must never match an empty box while the project is still loading.
  const matches = Boolean(projectName) && typed.trim() === projectName;
  // What stops the delete before anything is moved, since the workspaces move first and the later steps need these rights.
  const blocked = (() => {
    if (!preflight) return undefined;
    if (preflight.failed.length > 0) {
      return `${preflight.failed.join(" and ")} could not be read, so what the delete would remove is not known.`;
    }
    if (workspaceCount === 0) return undefined;
    if (!canManagePolicies) {
      return "Deleting a project that has workspaces also removes the policy sets attached to it, which needs Manage Policies. Ask someone who has it.";
    }
    if (!canManageCollection) {
      return "Deleting a project that has workspaces also detaches its variable collections, which needs Manage Collections. Ask someone who has it.";
    }
    return undefined;
  })();

  return (
    <Modal
      title={`Delete project "${projectName}"?`}
      open={open}
      onCancel={step ? undefined : onClose}
      onOk={confirm}
      okText={workspaceCount > 0 ? "Move to Unassigned, then delete" : "Delete project"}
      okButtonProps={{ danger: true, disabled: !matches || !preflight || Boolean(blocked) }}
      confirmLoading={Boolean(step)}
      closable={!step}
      maskClosable={!step}
      width={560}
    >
      {workspaceCount > 0 ? (
        <Typography.Paragraph>
          This project still has <strong>{plural(workspaceCount, "workspace")}</strong>, so it cannot be deleted yet.
        </Typography.Paragraph>
      ) : (
        <Typography.Paragraph>This permanently deletes the project. It cannot be undone.</Typography.Paragraph>
      )}
      {!preflight ? (
        <Skeleton active paragraph={{ rows: 3 }} />
      ) : (
        <dl className="project-move-impact">
          <div className="project-move-impact-row">
            <dt>Contains</dt>
            <dd>
              {plural(workspaceCount, "workspace")}
              {preflight.teamCount === null ? "" : `, ${plural(preflight.teamCount, "team access row")}`}
            </dd>
          </div>
          <div className="project-move-impact-row">
            <dt>Policy</dt>
            <dd>
              {policyCount === 0
                ? "Nothing attached"
                : `${plural(preflight.attachments.length, "attachment")}, ${plural(preflight.exemptions.length, "exemption")} would be removed`}
            </dd>
          </div>
          <div className="project-move-impact-row">
            <dt>Variables</dt>
            <dd>
              {preflight.collections.length === 0
                ? "No project-scoped collections"
                : `${plural(preflight.collections.length, "project-scoped collection")} would be detached`}
            </dd>
          </div>
          {preflight.defaultTagCount > 0 && (
            <div className="project-move-impact-row">
              <dt>Tags</dt>
              <dd>
                {plural(preflight.defaultTagCount, "default tag")} would be removed. Tags already on workspaces stay.
              </dd>
            </div>
          )}
        </dl>
      )}
      {blocked && <Alert type="warning" showIcon className="project-bulk-impact" message={blocked} />}
      {workspaceCount > 0 && (
        <Typography.Paragraph type="secondary" className="project-bulk-impact">
          The workspaces are moved to Unassigned first, not deleted.
        </Typography.Paragraph>
      )}
      <Typography.Paragraph>
        Type the project name to confirm: <strong>{projectName}</strong>
      </Typography.Paragraph>
      <Input
        aria-label="Project name"
        value={typed}
        onChange={(event) => setTyped(event.target.value)}
        disabled={Boolean(step)}
      />
      {step && <Alert type="info" showIcon className="project-bulk-impact" message={`${step}...`} />}
      {error && <Alert type="error" showIcon className="project-bulk-impact" message={error} />}
    </Modal>
  );
}
