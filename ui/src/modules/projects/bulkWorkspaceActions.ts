import axiosInstance from "@/config/axiosConfig";
import workspaceService from "@/modules/workspaces/workspaceService";

export type BulkOutcome = { succeeded: string[]; failed: { id: string; reason: string }[] };

function reasonFor(error: any): string {
  const status = error?.response?.status;
  if (status === 403 || status === 404) return "You do not have permission to change this workspace or project";
  return error?.response?.data?.errors?.[0]?.detail ?? error?.message ?? "Unknown error";
}

// Applies one change per workspace and reports each result, so one failure never hides the others.
async function runBulk(ids: string[], apply: (id: string) => Promise<void>): Promise<BulkOutcome> {
  const results = await Promise.allSettled(ids.map((id) => apply(id)));
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

const JSON_API = { "Content-Type": "application/vnd.api+json" };

// Workspaces that already carry the tag are skipped, so applying it twice never duplicates a binding.
export function addTagToWorkspaces(
  orgId: string,
  workspaces: { id: string; tags?: { tagId: string }[] }[],
  tagId: string,
  value?: string
): Promise<BulkOutcome> {
  const needed = workspaces.filter((workspace) => !workspace.tags?.some((binding) => binding.tagId === tagId));
  return runBulk(
    needed.map((workspace) => workspace.id),
    async (id) => {
      await axiosInstance.post(
        `organization/${orgId}/workspace/${id}/workspaceTag`,
        { data: { type: "workspacetag", attributes: { tagId, value: value?.trim() || null } } },
        { headers: JSON_API }
      );
    }
  );
}

export function setWorkspacesLocked(orgId: string, ids: string[], locked: boolean): Promise<BulkOutcome> {
  return runBulk(ids, async (id) => {
    await axiosInstance.patch(
      `organization/${orgId}/workspace/${id}`,
      { data: { type: "workspace", id, attributes: { locked } } },
      { headers: JSON_API }
    );
  });
}
