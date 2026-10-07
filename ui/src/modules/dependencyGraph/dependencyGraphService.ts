import axiosInstance from "@/config/axiosConfig";
import { RunTrigger, RunTriggerSynchronizationMode, Workspace } from "@/domain/types";

export type DependencyGraphWorkspace = {
  id: string;
  name: string;
};

export type DependencyGraphEdge = {
  id: string;
  sourceWorkspaceId: string;
  destinationWorkspaceId: string;
  synchronizationMode: RunTriggerSynchronizationMode;
};

export type DependencyGraphData = {
  workspaces: DependencyGraphWorkspace[];
  edges: DependencyGraphEdge[];
};

/**
 * The static dependency topology for a whole organization - every workspace and every run
 * trigger between them, regardless of whether any cascade has ever actually run across one.
 * Two requests, same JSON:API client RunTriggers.tsx already uses for the per-workspace view.
 *
 * Filtered on sourceWorkspace.organization.id alone: a run trigger's own ends are always in the
 * same organization (WorkspaceRunTriggerHook enforces it on the backend), so the source side is
 * enough to select every edge touching this organization.
 */
export async function getOrganizationDependencyGraph(organizationId: string): Promise<DependencyGraphData> {
  const [workspacesResponse, triggersResponse] = await Promise.all([
    axiosInstance.get(`organization/${organizationId}/workspace`),
    axiosInstance.get("runTrigger", {
      params: {
        "filter[runTrigger]": `sourceWorkspace.organization.id==${organizationId}`,
      },
    }),
  ]);

  const workspaces: DependencyGraphWorkspace[] = (workspacesResponse.data.data ?? []).map((item: Workspace) => ({
    id: item.id,
    name: item.attributes.name,
  }));

  const edges: DependencyGraphEdge[] = (triggersResponse.data.data ?? [])
    .map((trigger: RunTrigger): DependencyGraphEdge | null => {
      const sourceWorkspaceId = trigger.relationships.sourceWorkspace?.data?.id;
      const destinationWorkspaceId = trigger.relationships.destinationWorkspace?.data?.id;
      if (!sourceWorkspaceId || !destinationWorkspaceId) {
        return null;
      }
      return {
        id: trigger.id,
        sourceWorkspaceId,
        destinationWorkspaceId,
        synchronizationMode: trigger.attributes.synchronizationMode,
      };
    })
    .filter((edge: DependencyGraphEdge | null): edge is DependencyGraphEdge => edge !== null);

  return { workspaces, edges };
}
