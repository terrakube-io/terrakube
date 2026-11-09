import { Button, Flex, Input, Modal, Select, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { ProjectSummaryModel } from "@/domain/types";
import { TagModel } from "@/modules/organizations/types";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import projectService from "./projectService";
import MoveImpact from "./MoveImpact";
import {
  BulkOutcome,
  addTagToWorkspaces,
  moveWorkspaces,
  removeWorkspacesFromProject,
  setWorkspacesLocked,
} from "./bulkWorkspaceActions";

type Props = {
  orgid: string;
  projectId: string;
  /** The selected rows. Selection only ever covers the page being shown. */
  selected: WorkspaceListItem[];
  total: number;
  tags: TagModel[];
  /** Taking a workspace out of every project needs the organization level right; a project admin cannot. */
  canRemove?: boolean;
  onClear: () => void;
  onDone: () => void;
};

function report(verb: string, outcome: BulkOutcome) {
  const done = outcome.succeeded.length;
  if (outcome.failed.length === 0) {
    message.success(`${verb} ${done} workspace${done === 1 ? "" : "s"}`);
    return;
  }
  message.warning(`${verb} ${done}, ${outcome.failed.length} failed: ${outcome.failed[0].reason}`);
}

/** Actions on the workspaces ticked in the project workspace list. Hidden until something is selected. */
export default function BulkWorkspaceBar({
  orgid,
  projectId,
  selected,
  total,
  tags,
  canRemove = false,
  onClear,
  onDone,
}: Props) {
  const [moveOpen, setMoveOpen] = useState(false);
  const [tagOpen, setTagOpen] = useState(false);
  const [targets, setTargets] = useState<ProjectSummaryModel[]>([]);
  const [target, setTarget] = useState<string>();
  const [tagId, setTagId] = useState<string>();
  const [tagValue, setTagValue] = useState("");
  const [working, setWorking] = useState(false);

  const searchTargets = (search: string) =>
    projectService.listProjectSummaries(orgid, { search, sort: "+name", page: 1, pageSize: 20 }).then((response) => {
      if (!response.isError) setTargets((response.data?.items ?? []).filter((project) => project.id !== projectId));
    });

  useEffect(() => {
    if (moveOpen) searchTargets("");
  }, [moveOpen]);

  if (selected.length === 0) return null;
  const count = selected.length;
  const noun = `${count} workspace${count === 1 ? "" : "s"}`;
  const ids = selected.map((workspace) => workspace.id);
  const anyUnlocked = selected.some((workspace) => !workspace.locked);

  const finish = (verb: string, outcome: BulkOutcome) => {
    report(verb, outcome);
    setWorking(false);
    setMoveOpen(false);
    setTagOpen(false);
    setTarget(undefined);
    setTagId(undefined);
    setTagValue("");
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
        finish("Removed", await removeWorkspacesFromProject(orgid, ids));
      },
    });

  const doLock = async () => {
    setWorking(true);
    finish(anyUnlocked ? "Locked" : "Unlocked", await setWorkspacesLocked(orgid, ids, anyUnlocked));
  };

  const doTag = async () => {
    if (!tagId) return;
    setWorking(true);
    finish("Tagged", await addTagToWorkspaces(orgid, selected, tagId, tagValue));
  };

  const doMove = async () => {
    if (!target) return;
    setWorking(true);
    finish("Moved", await moveWorkspaces(orgid, ids, target));
  };

  return (
    <Flex gap={8} align="center" wrap className="project-bulk-bar">
      <Typography.Text strong>
        {count} of {total} selected
      </Typography.Text>
      <Typography.Text type="secondary">(this page only)</Typography.Text>
      <Button onClick={() => setTagOpen(true)}>Add tag</Button>
      <Button onClick={() => setMoveOpen(true)}>Move to project</Button>
      <Button onClick={doLock} loading={working}>
        {anyUnlocked ? "Lock" : "Unlock"}
      </Button>
      {canRemove && (
        <Button danger onClick={confirmRemove} loading={working}>
          Remove from project
        </Button>
      )}
      <Button type="link" onClick={onClear}>
        Clear
      </Button>

      <Modal
        title={`Add a tag to ${noun}`}
        open={tagOpen}
        onCancel={() => setTagOpen(false)}
        onOk={doTag}
        okText="Add tag"
        okButtonProps={{ disabled: !tagId }}
        confirmLoading={working}
      >
        <Flex gap={8}>
          <Select
            showSearch
            optionFilterProp="label"
            aria-label="Tag to add"
            placeholder="Choose a tag"
            style={{ flex: 1 }}
            value={tagId}
            onChange={setTagId}
            options={tags.map((tag) => ({ value: tag.id, label: tag.name }))}
          />
          <Input
            aria-label="Tag value"
            placeholder="Value (optional)"
            maxLength={256}
            style={{ flex: 1 }}
            value={tagValue}
            onChange={(event) => setTagValue(event.target.value)}
          />
        </Flex>
        <Typography.Paragraph type="secondary" className="project-bulk-impact">
          Workspaces that already have this tag are left as they are.
        </Typography.Paragraph>
      </Modal>

      <Modal
        title={`Move ${noun}`}
        open={moveOpen}
        onCancel={() => setMoveOpen(false)}
        onOk={doMove}
        okText={`Move ${noun}`}
        okButtonProps={{ disabled: !target }}
        confirmLoading={working}
        width={560}
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
        <Typography.Paragraph type="secondary" className="project-bulk-impact">
          {selected.map((workspace) => workspace.name).join(", ")}
        </Typography.Paragraph>
        {target && (
          <MoveImpact
            orgid={orgid}
            sourceProjectId={projectId}
            targetProjectId={target}
            targetName={targets.find((project) => project.id === target)?.name ?? ""}
            workspaceCount={count}
          />
        )}
      </Modal>
    </Flex>
  );
}
