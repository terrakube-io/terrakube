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
    if (outcome.failed.length > 0) {
      throw new Error(`${outcome.failed.length} workspaces could not be moved: ${outcome.failed[0].reason}`);
    }
  }

  progress("Removing policy attachments and exemptions");
  for (const exemption of preflight.exemptions) await projectService.deletePolicyExemption(exemption.id);
  for (const attachment of preflight.attachments) {
    await projectService.deletePolicyAttachment(attachment.policySetId, attachment.id);
  }

  progress("Detaching variable collections");
  for (const collection of preflight.collections) {
    await projectService.detachCollectionFromProject(orgid, collection.id, collection.referenceId);
  }

  progress("Deleting the project");
  await projectService.deleteProject(orgid, projectId);
}
