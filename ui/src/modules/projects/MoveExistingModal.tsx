import { Alert, Modal, Select, message } from "antd";
import { useEffect, useRef, useState } from "react";
import workspaceService from "@/modules/workspaces/workspaceService";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import { moveWorkspaces } from "./bulkWorkspaceActions";
import MoveImpact from "./MoveImpact";
import { notifyProjectChanged } from "./projectEvents";

type Props = {
  orgid: string;
  projectId: string;
  projectName: string;
  open: boolean;
  onClose: () => void;
  onMoved: () => void;
};

/** Picks workspaces that are in no project and moves them here, after showing what that changes. */
export default function MoveExistingModal({ orgid, projectId, projectName, open, onClose, onMoved }: Props) {
  const [options, setOptions] = useState<WorkspaceListItem[]>([]);
  const [chosen, setChosen] = useState<string[]>([]);
  const [working, setWorking] = useState(false);
  const [failed, setFailed] = useState(false);
  // Names of every workspace seen, so a ticked workspace keeps its name when a new search replaces the results.
  const names = useRef(new Map<string, string>());
  // Only the newest search may fill the list, and a keystroke waits a moment for the next one.
  const latest = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const search = async (text: string) => {
    const current = ++latest.current;
    const response = await workspaceService.listWorkspacePage(
      {
        organizationId: orgid,
        first: 20,
        after: 0,
        search: text,
        projectId: "__unassigned__",
        sort: "name_asc",
      },
      false
    );
    if (current !== latest.current) return;
    setFailed(response.isError);
    if (!response.isError) {
      const found = response.data?.workspaces ?? [];
      found.forEach((workspace) => names.current.set(workspace.id, workspace.name));
      setOptions(found);
    }
  };

  const searchSoon = (text: string) => {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => search(text), 250);
  };

  useEffect(() => () => clearTimeout(timer.current), []);

  useEffect(() => {
    if (open) {
      setChosen([]);
      search("");
    }
  }, [open]);

  const move = async () => {
    setWorking(true);
    const outcome = await moveWorkspaces(orgid, chosen, projectId);
    setWorking(false);
    if (outcome.failed.length)
      message.warning(
        `Moved ${outcome.succeeded.length}, ${outcome.failed.length} failed: ${outcome.failed[0].reason}`
      );
    else message.success(`Moved ${outcome.succeeded.length} workspace${outcome.succeeded.length === 1 ? "" : "s"}`);
    notifyProjectChanged();
    onMoved();
    onClose();
  };

  return (
    <Modal
      title={`Move existing workspaces into ${projectName}`}
      open={open}
      onCancel={onClose}
      onOk={move}
      okText={chosen.length ? `Move ${chosen.length} workspace${chosen.length === 1 ? "" : "s"}` : "Move"}
      okButtonProps={{ disabled: chosen.length === 0 }}
      confirmLoading={working}
      width={560}
    >
      {failed && (
        <Alert type="warning" showIcon message="Workspaces could not be loaded." className="project-overview-alert" />
      )}
      <Select
        mode="multiple"
        showSearch
        filterOption={false}
        onSearch={searchSoon}
        aria-label="Workspaces to move"
        placeholder="Search workspaces that are not in a project"
        style={{ width: "100%" }}
        value={chosen}
        onChange={setChosen}
        options={[
          ...chosen.map((id) => ({ value: id, label: names.current.get(id) ?? id })),
          ...options
            .filter((workspace) => !chosen.includes(workspace.id))
            .map((workspace) => ({ value: workspace.id, label: workspace.name })),
        ]}
        notFoundContent="No workspaces without a project"
      />
      {chosen.length > 0 && (
        <MoveImpact
          orgid={orgid}
          sourceProjectId={null}
          targetProjectId={projectId}
          targetName={projectName}
          workspaceCount={chosen.length}
        />
      )}
    </Modal>
  );
}
