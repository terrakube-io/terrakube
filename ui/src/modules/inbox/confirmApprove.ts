import { Modal, message } from "antd";
import { approveRuns, findWaitingRuns } from "@/modules/projects/bulkWorkspaceActions";
import { InboxItem } from "./inboxModel";

export type ApproveOutcome = { approved: number; skipped: number; heldBack: number; failures: string[] };

/**
 * Approves the run waiting on each workspace, one organization at a time. A run that is no longer waiting is skipped
 * and counted, not treated as a failure. A run with a policy violation is held back and counted: it is for a person
 * to read on its own page, never approved from a list.
 */
export async function approveWaiting(items: InboxItem[]): Promise<ApproveOutcome> {
  const byOrganization = new Map<string, string[]>();
  items.forEach((item) =>
    byOrganization.set(item.orgId, [...(byOrganization.get(item.orgId) ?? []), item.workspaceId])
  );
  const outcome: ApproveOutcome = { approved: 0, skipped: 0, heldBack: 0, failures: [] };
  for (const [orgId, workspaceIds] of byOrganization) {
    try {
      const runs = await findWaitingRuns(orgId, workspaceIds);
      outcome.skipped += workspaceIds.length - new Set(runs.map((run) => run.workspaceId)).size;
      const clear = runs.filter((run) => !run.hardViolations && !run.softViolations);
      outcome.heldBack += runs.length - clear.length;
      if (clear.length === 0) continue;
      const result = await approveRuns(
        orgId,
        clear.map((run) => run.jobId)
      );
      outcome.approved += result.succeeded.length;
      outcome.failures.push(...result.failed.map((failure) => failure.reason));
    } catch (err: any) {
      outcome.failures.push(err?.message ?? "The runs could not be approved");
    }
  }
  return outcome;
}

/** Asks before approving the run waiting on a workspace, then approves it. `onDone` runs either way, to reload. */
export function confirmApprove(item: InboxItem, onDone: () => void) {
  Modal.confirm({
    title: `Approve the run waiting on ${item.name}?`,
    content: `${item.orgName}${item.projectName ? ` / ${item.projectName}` : ""}. Approving lets the run go ahead.`,
    okText: "Approve",
    onOk: async () => {
      const { approved, heldBack, failures } = await approveWaiting([item]);
      if (failures.length) message.error(failures[0]);
      else if (heldBack > 0)
        message.warning(`The run on ${item.name} has policy violations. Open it to review before approving.`);
      else if (approved === 0) message.info("That run is no longer waiting for approval.");
      else message.success(`Approved the run on ${item.name}`);
      onDone();
    },
  });
}

/** Asks before approving the runs waiting on several workspaces, then approves them. `onDone` runs either way. */
export function confirmApproveMany(items: InboxItem[], onDone: () => void) {
  const count = items.length;
  Modal.confirm({
    title: `Approve the runs waiting on ${count} workspace${count === 1 ? "" : "s"}?`,
    content: "Approving lets each run go ahead. Runs that are no longer waiting are left alone.",
    okText: `Approve ${count}`,
    onOk: async () => {
      const { approved, skipped, heldBack, failures } = await approveWaiting(items);
      const parts = [`Approved ${approved}`];
      if (skipped > 0) parts.push(`${skipped} no longer waiting`);
      if (heldBack > 0) parts.push(`${heldBack} held back for policy violations, open them to review`);
      if (failures.length > 0) parts.push(`${failures.length} failed: ${failures[0]}`);
      if (failures.length > 0 || heldBack > 0) message.warning(parts.join(", "));
      // Nothing approved and nothing wrong: say what happened, not "success".
      else if (approved === 0) message.info(parts.join(", "));
      else message.success(parts.join(", "));
      onDone();
    },
  });
}
