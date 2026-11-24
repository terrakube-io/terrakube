import workspaceService from "@/modules/workspaces/workspaceService";
import projectService, { DeletePreflight } from "./projectService";
import { removeWorkspacesFromProject } from "./bulkWorkspaceActions";

const PAGE = 100;

export type DeleteProgress = (step: string) => void;

/**
 * Deletes a project the way the pre-flight describes it: the workspaces go to Unassigned, then policy attachments,
 * exemptions and collection attachments are removed, and then the project itself. It stops at the first step that
 * leaves something behind, because deleting the project then would only fail (the server refuses it).
 */
export async function deleteProjectWithContents(
  orgid: string,
  projectId: string,
  preflight: DeletePreflight,
  progress: DeleteProgress = () => undefined
): Promise<void> {
  progress("Moving workspaces to Unassigned");
  let moved = 0;
  // Each pass takes the first page, which shrinks as workspaces leave. A pass that moves nothing ends the loop.
  for (;;) {
    const page = await workspaceService.listWorkspacePage(
      { organizationId: orgid, first: PAGE, after: 0, projectId, sort: "name_asc" },
      false
    );
    if (page.isError) throw new Error(page.error?.message ?? "Workspaces could not be listed");
    const ids = (page.data?.workspaces ?? []).map((workspace) => workspace.id);
    if (ids.length === 0) break;
    const outcome = await removeWorkspacesFromProject(orgid, ids);
    moved += outcome.succeeded.length;
    if (outcome.failed.length > 0) {
      throw new Error(
        `${outcome.failed.length} workspaces could not be moved: ${outcome.failed[0].reason}${movedNote(moved)}`
      );
    }
  }

  try {
    await removeWhatIsAttached(orgid, preflight, progress);
    progress("Deleting the project");
    await projectService.deleteProject(orgid, projectId);
  } catch (error: any) {
    // From here the workspaces are already out of the project, so the person must be told.
    const reason = error?.response?.data?.errors?.[0]?.detail ?? error?.message ?? "A step failed";
    const denied = error?.response?.status === 403 || error?.response?.status === 404;
    // The cause is assigned, not passed to the constructor, because the build's library target has no ErrorOptions.
    throw Object.assign(
      new Error(`${denied ? "You do not have permission for this step" : reason}${movedNote(moved)}`),
      {
        cause: error,
      }
    );
  }
}

const movedNote = (moved: number) =>
  moved > 0
    ? `. ${moved} workspace${moved === 1 ? " was" : "s were"} already moved to Unassigned and the project still exists.`
    : "";

async function removeWhatIsAttached(orgid: string, preflight: DeletePreflight, progress: DeleteProgress) {
  progress("Removing policy attachments and exemptions");
  for (const exemption of preflight.exemptions) await projectService.deletePolicyExemption(exemption.id);
  for (const attachment of preflight.attachments) {
    await projectService.deletePolicyAttachment(attachment.policySetId, attachment.id);
  }

  progress("Detaching variable collections");
  for (const collection of preflight.collections) {
    await projectService.detachCollectionFromProject(orgid, collection.id, collection.referenceId);
  }
}
