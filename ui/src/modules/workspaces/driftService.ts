import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { DRIFT_WINDOW_DAYS, createdWithin } from "./jobWindow";

export type DriftCheck = {
  id: string;
  createdDate?: string;
  result: "drift" | "clean";
  add: number | null;
  change: number | null;
  destroy: number | null;
};

/**
 * The drift checks of a workspace, newest first: the scheduled plan-only runs the API marked as checks.
 * Only the last {@link DRIFT_WINDOW_DAYS} days are read. No first/after: Job's read permission is evaluated in memory, so Elide rejects them ("Cannot paginate job").
 */
export async function listDriftChecks(
  organizationId: string,
  workspaceId: string,
  limit = 10
): Promise<ApiResponse<DriftCheck[]>> {
  const body = {
    query: `query ($orgId: String!, $filter: String) {
      organization(ids: [$orgId]) { edges { node {
        job(sort: "-createdDate", filter: $filter) {
          edges { node { id createdDate driftResult planAdd planChange planDestroy } }
        }
      } } }
    }`,
    variables: {
      orgId: organizationId,
      filter: `workspace.id=='${workspaceId}';driftResult=isnull=false;${createdWithin(DRIFT_WINDOW_DAYS)}`,
    },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: undefined as never };
  }
  const edges = result.data?.organization?.edges?.[0]?.node?.job?.edges ?? [];
  return {
    isError: false,
    responseCode: result.responseCode,
    data: edges.slice(0, limit).map((edge: any) => ({
      id: String(edge.node.id),
      createdDate: edge.node.createdDate ?? undefined,
      result: edge.node.driftResult,
      add: edge.node.planAdd ?? null,
      change: edge.node.planChange ?? null,
      destroy: edge.node.planDestroy ?? null,
    })),
  };
}
