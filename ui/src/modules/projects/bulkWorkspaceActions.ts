import { mapWithLimit } from "@/modules/utils/mapWithLimit";
import workspaceService from "@/modules/workspaces/workspaceService";

export type BulkOutcome = { succeeded: string[]; failed: { id: string; reason: string }[] };

function reasonFor(error: any): string {
  const status = error?.response?.status;
  if (status === 403 || status === 404) return "You do not have permission to change this workspace or project";
  return error?.response?.data?.errors?.[0]?.detail ?? error?.message ?? "Unknown error";
}

// A bulk action on many workspaces must not start all its requests together.
const MAX_PARALLEL_CHANGES = 5;

// Applies one change per workspace and reports each result, so one failure never hides the others.
async function runBulk(ids: string[], apply: (id: string) => Promise<void>): Promise<BulkOutcome> {
  const results = await mapWithLimit(ids, MAX_PARALLEL_CHANGES, (id) => apply(id));
  const outcome: BulkOutcome = { succeeded: [], failed: [] };
  results.forEach((result, index) => {
    if (result.status === "fulfilled") outcome.succeeded.push(ids[index]);
    else outcome.failed.push({ id: ids[index], reason: reasonFor(result.reason) });
  });
  return outcome;
}

export function moveWorkspaces(orgId: string, ids: string[], projectId: string): Promise<BulkOutcome> {
  return runBulk(ids, (id) => workspaceService.assignWorkspaceToProject(orgId, id, projectId));
}

export function removeWorkspacesFromProject(orgId: string, ids: string[]): Promise<BulkOutcome> {
  return runBulk(ids, (id) => workspaceService.removeWorkspaceFromProject(orgId, id));
}
