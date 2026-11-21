import { Modal, message } from "antd";
import { approveRuns, findWaitingRuns } from "@/modules/projects/bulkWorkspaceActions";
import { InboxItem } from "./inboxModel";

/** Asks before approving the run waiting on a workspace, then approves it. `onDone` runs either way, to reload. */
export function confirmApprove(item: InboxItem, onDone: () => void) {
  Modal.confirm({
    title: `Approve the run waiting on ${item.name}?`,
    content: `${item.orgName}${item.projectName ? ` / ${item.projectName}` : ""}. Approving lets the run go ahead.`,
    okText: "Approve",
    onOk: async () => {
      try {
        const runs = await findWaitingRuns(item.orgId, [item.workspaceId]);
        if (runs.length === 0) {
          message.info("That run is no longer waiting for approval.");
        } else {
          const outcome = await approveRuns(
            item.orgId,
            runs.map((run) => run.jobId)
          );
          if (outcome.failed.length) message.error(outcome.failed[0].reason);
          else message.success(`Approved the run on ${item.name}`);
        }
      } catch (err: any) {
        message.error(err?.message ?? "The run could not be approved");
      }
      onDone();
    },
  });
}
