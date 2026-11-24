import { Alert, Button, Flex, Modal, Select, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { ProjectSummaryModel } from "@/domain/types";
import projectService from "./projectService";
import { BulkOutcome, moveWorkspaces, removeWorkspacesFromProject } from "./bulkWorkspaceActions";

type Props = {
  orgid: string;
  projectId: string;
  selectedIds: string[];
  /** Taking a workspace out of every project needs the organization level right; a project admin cannot. */
  canRemove?: boolean;
  onClear: () => void;
  onDone: () => void;
};

function report(verb: string, outcome: BulkOutcome) {
  if (outcome.failed.length === 0) {
    message.success(`${verb} ${outcome.succeeded.length} workspace${outcome.succeeded.length === 1 ? "" : "s"}`);
    return;
  }
  const first = outcome.failed[0].reason;
  message.warning(`${verb} ${outcome.succeeded.length}, ${outcome.failed.length} failed: ${first}`);
}

/** Actions on the workspaces ticked in the project workspace list. Hidden until something is selected. */
export default function BulkWorkspaceBar({ orgid, projectId, selectedIds, canRemove = false, onClear, onDone }: Props) {
  const [moveOpen, setMoveOpen] = useState(false);
  const [targets, setTargets] = useState<ProjectSummaryModel[]>([]);
  const [target, setTarget] = useState<string>();
  const [working, setWorking] = useState(false);

  const searchTargets = (search: string) =>
    projectService.listProjectSummaries(orgid, { search, sort: "+name", page: 1, pageSize: 20 }).then((response) => {
      if (!response.isError) setTargets((response.data?.items ?? []).filter((project) => project.id !== projectId));
    });

  useEffect(() => {
    if (moveOpen) searchTargets("");
  }, [moveOpen]);

  if (selectedIds.length === 0) return null;
  const count = selectedIds.length;
  const noun = `${count} workspace${count === 1 ? "" : "s"}`;

  const finish = (verb: string, outcome: BulkOutcome) => {
    report(verb, outcome);
    setWorking(false);
    setMoveOpen(false);
    setTarget(undefined);
    onClear();
    onDone();
  };

  const confirmRemove = () =>
    Modal.confirm({
      title: `Remove ${noun} from this project?`,
      content:
        "The workspaces stay in the organization but are no longer in a project. Teams that only had access through this project lose it.",
      okText: "Remove",
      okButtonProps: { danger: true },
      onOk: async () => {
        setWorking(true);
        finish("Removed", await removeWorkspacesFromProject(orgid, selectedIds));
      },
    });

  const doMove = async () => {
    if (!target) return;
    setWorking(true);
    finish("Moved", await moveWorkspaces(orgid, selectedIds, target));
  };

  return (
    <Flex gap={8} align="center" className="project-bulk-bar">
      <Typography.Text strong>{count} selected</Typography.Text>
      <Button onClick={() => setMoveOpen(true)}>Move to project</Button>
      {canRemove && (
        <Button danger onClick={confirmRemove} loading={working}>
          Remove from project
        </Button>
      )}
      <Button type="link" onClick={onClear}>
        Clear
      </Button>
      <Modal
        title={`Move ${noun}`}
        open={moveOpen}
        onCancel={() => setMoveOpen(false)}
        onOk={doMove}
        okText="Move"
        okButtonProps={{ disabled: !target }}
        confirmLoading={working}
      >
        <Select
          showSearch
          filterOption={false}
          onSearch={searchTargets}
          placeholder="Search for a project"
          aria-label="Destination project"
          style={{ width: "100%" }}
          value={target}
          onChange={setTarget}
          options={targets.map((project) => ({ value: project.id, label: project.name }))}
        />
        <Alert
          type="info"
          showIcon
          className="project-bulk-impact"
          message="Access changes"
          description="Teams on this project lose project-level access to these workspaces and teams on the destination project gain it. Workspace-level access is kept."
        />
      </Modal>
    </Flex>
  );
}
