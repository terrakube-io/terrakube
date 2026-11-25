import { Alert, Button, Flex, Input, Modal, Popconfirm, Select, Skeleton, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { ProjectSummaryModel } from "@/domain/types";
import { TagModel } from "@/modules/organizations/types";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import projectService, { OrganizationTemplate } from "./projectService";
import DisabledReason from "./DisabledReason";
import { notifyProjectChanged } from "./projectEvents";
import MoveImpact from "./MoveImpact";
import {
  BulkOutcome,
  WaitingRun,
  addTagToWorkspaces,
  approveRuns,
  findWaitingRuns,
  planWorkspaces,
  moveWorkspaces,
  planTotals,
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
  const [planOpen, setPlanOpen] = useState(false);
  const [templates, setTemplates] = useState<OrganizationTemplate[]>([]);
  const [templateId, setTemplateId] = useState<string>();
  const [approveOpen, setApproveOpen] = useState(false);
  const [waiting, setWaiting] = useState<WaitingRun[] | null>(null);

  const searchTargets = (search: string) =>
    projectService.listProjectSummaries(orgid, { search, sort: "+name", page: 1, pageSize: 20 }).then((response) => {
      if (!response.isError) setTargets((response.data?.items ?? []).filter((project) => project.id !== projectId));
    });

  useEffect(() => {
    if (moveOpen) searchTargets("");
  }, [moveOpen]);

  useEffect(() => {
    if (!planOpen) return;
    projectService
      .listOrganizationTemplates(orgid)
      .then((all) => setTemplates(all.filter((template) => template.planOnly)))
      .catch(() => setTemplates([]));
  }, [planOpen, orgid]);

  const selectedIds = selected.map((workspace) => workspace.id);
  useEffect(() => {
    if (!approveOpen) return;
    setWaiting(null);
    // The answer for an earlier selection or a closed dialog is dropped.
    let current = true;
    findWaitingRuns(orgid, selectedIds)
      .then((runs) => current && setWaiting(runs))
      .catch((err) => {
        if (!current) return;
        message.error(err?.message ?? "Runs waiting for approval could not be listed");
        setApproveOpen(false);
      });
    return () => {
      current = false;
    };
  }, [approveOpen, orgid, selectedIds.join(",")]);

  // Keep the row's height when nothing is ticked so the table does not jump down on the first selection.
  if (selected.length === 0) return <div className="project-bulk-bar project-bulk-bar-idle" aria-hidden />;
  const count = selected.length;
  const noun = `${count} workspace${count === 1 ? "" : "s"}`;
  const ids = selected.map((workspace) => workspace.id);
  const anyUnlocked = selected.some((workspace) => !workspace.locked);

  const finish = (verb: string, outcome: BulkOutcome) => {
    report(verb, outcome);
    setWorking(false);
    setMoveOpen(false);
    setTagOpen(false);
    setPlanOpen(false);
    setApproveOpen(false);
    setTemplateId(undefined);
    setTarget(undefined);
    setTagId(undefined);
    setTagValue("");
    onClear();
    onDone();
    notifyProjectChanged();
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

  const doPlan = async () => {
    if (!templateId) return;
    setWorking(true);
    finish("Started a run on", await planWorkspaces(orgid, ids, templateId));
  };

  // A run whose policy check found violations is never approved from here: it is looked at on its own page.
  const clear = (waiting ?? []).filter((run) => run.hardViolations + run.softViolations === 0);
  const heldBack = (waiting ?? []).filter((run) => run.hardViolations + run.softViolations > 0);
  const nameOf = (run: WaitingRun) => selected.find((workspace) => workspace.id === run.workspaceId)?.name;
  const totals = planTotals(clear);
  const describePlan = (run: WaitingRun) =>
    run.add === null || run.change === null || run.destroy === null
      ? "no plan summary"
      : `${run.add} to add, ${run.change} to change, ${run.destroy} to destroy`;

  const doApprove = async () => {
    if (clear.length === 0) return;
    setWorking(true);
    finish(
      "Approved a run on",
      await approveRuns(
        orgid,
        clear.map((run) => run.jobId)
      )
    );
  };

  const doMove = async () => {
    if (!target) return;
    setWorking(true);
    finish("Moved", await moveWorkspaces(orgid, ids, target));
  };

  return (
    <Flex gap={8} align="center" wrap className="project-bulk-bar project-bulk-bar-active">
      <Typography.Text strong>
        {count} of {total} selected
      </Typography.Text>
      <Typography.Text type="secondary">(this page only)</Typography.Text>
      <Button onClick={() => setPlanOpen(true)}>Plan</Button>
      <DisabledReason
        disabled={!selected.some((item) => item.lastStatus === "waitingApproval")}
        reason="None of the selected workspaces has a run waiting for approval."
      >
        <Button
          onClick={() => setApproveOpen(true)}
          disabled={!selected.some((item) => item.lastStatus === "waitingApproval")}
        >
          Approve
        </Button>
      </DisabledReason>
      <Button onClick={() => setTagOpen(true)}>Add tag</Button>
      <Button onClick={() => setMoveOpen(true)}>Move to project</Button>
      <Popconfirm
        title={`${anyUnlocked ? "Lock" : "Unlock"} ${noun}?`}
        description={
          anyUnlocked
            ? "No new run can start on a locked workspace until it is unlocked."
            : "Runs waiting on these workspaces can start again."
        }
        okText={anyUnlocked ? "Lock" : "Unlock"}
        onConfirm={doLock}
      >
        <Button loading={working}>{anyUnlocked ? "Lock" : "Unlock"}</Button>
      </Popconfirm>
      {canRemove && (
        <Button danger onClick={confirmRemove} loading={working}>
          Remove from project
        </Button>
      )}
      <Button type="link" onClick={onClear}>
        Clear
      </Button>

      <Modal
        title={`Start a run on ${noun}`}
        open={planOpen}
        onCancel={() => setPlanOpen(false)}
        onOk={doPlan}
        okText={`Start ${count === 1 ? "1 run" : `${count} runs`}`}
        okButtonProps={{ disabled: !templateId }}
        confirmLoading={working}
      >
        <Select
          showSearch
          optionFilterProp="label"
          aria-label="Template"
          placeholder="Choose a template"
          style={{ width: "100%" }}
          value={templateId}
          onChange={setTemplateId}
          options={templates.map((template) => ({ value: template.id, label: template.name }))}
        />
        <Typography.Paragraph type="secondary" className="project-bulk-impact">
          {selected.map((workspace) => workspace.name).join(", ")}
        </Typography.Paragraph>
        <Alert
          type="info"
          showIcon
          message="One plan starts on each workspace, using the workspace's own branch."
          description="Only templates that plan are offered here, so nothing is applied. To apply, start the run on each workspace."
        />
      </Modal>

      <Modal
        title="Approve runs"
        open={approveOpen}
        onCancel={() => setApproveOpen(false)}
        onOk={doApprove}
        okText={clear.length > 0 ? `Approve ${clear.length} run${clear.length === 1 ? "" : "s"}` : "Approve"}
        okButtonProps={{ disabled: clear.length === 0 }}
        confirmLoading={working}
      >
        {waiting === null ? (
          <Skeleton active paragraph={{ rows: 2 }} />
        ) : waiting.length === 0 ? (
          <Alert type="info" showIcon message="None of the selected workspaces has a run waiting for approval." />
        ) : (
          <>
            <Typography.Paragraph>
              {waiting.length} run{waiting.length === 1 ? " is" : "s are"} waiting for approval on{" "}
              {new Set(waiting.map((run) => run.workspaceId)).size} of the selected workspaces. Approving lets{" "}
              {waiting.length === 1 ? "it" : "them"} go ahead.
            </Typography.Paragraph>
            <ul className="project-bulk-runs" aria-label="Runs to approve">
              {clear.map((run) => (
                <li key={run.jobId}>
                  <strong>{nameOf(run) ?? run.workspaceId}</strong>{" "}
                  <Typography.Text type="secondary">{describePlan(run)}</Typography.Text>
                </li>
              ))}
            </ul>
            {clear.length > 0 && (
              <Alert
                type={totals.destroy > 0 || totals.withoutSummary > 0 ? "warning" : "info"}
                showIcon
                message={
                  totals.destroy > 0
                    ? `Approving will destroy ${totals.withoutSummary > 0 ? "at least " : ""}${totals.destroy} resource${totals.destroy === 1 ? "" : "s"}`
                    : totals.withoutSummary > 0
                      ? "What approving destroys is not known for every run"
                      : "Approving will not destroy anything"
                }
                description={`In all: ${totals.add} to add, ${totals.change} to change, ${totals.destroy} to destroy.${
                  totals.withoutSummary > 0
                    ? ` ${totals.withoutSummary} run${totals.withoutSummary === 1 ? " has" : "s have"} no plan summary and ${totals.withoutSummary === 1 ? "is" : "are"} not counted.`
                    : ""
                }`}
              />
            )}
            {heldBack.length > 0 && (
              <Alert
                type="warning"
                showIcon
                message={`${heldBack.length} run${heldBack.length === 1 ? " is" : "s are"} held back: the policy check found violations`}
                description={`${heldBack.map(nameOf).filter(Boolean).join(", ")}. Open the run to review the findings and approve it there.`}
              />
            )}
          </>
        )}
      </Modal>

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
