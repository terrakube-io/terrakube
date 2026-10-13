import axios from "axios";
import axiosInstance, { axiosAdmin, getErrorMessage } from "@/config/axiosConfig";
import { RunCascade, RunCascadeEdge, RunCascadeNode, RunCascadeRow, RunCascadeStatus } from "@/domain/types";

type IncludedResource = CascadeNodesResponse["included"][number];

/** Follow the API's pagination links so graphs and histories never silently lose rows. */
async function readCollection<T>(path: string, config?: Parameters<typeof axiosInstance.get>[1]) {
  let response = config ? await axiosInstance.get(path, config) : await axiosInstance.get(path);
  const rows: T[] = [];
  const included: IncludedResource[] = [];
  const visited = new Set<string>();
  while (true) {
    rows.push(...(response.data.data ?? []));
    included.push(...(response.data.included ?? []));
    const next = response.data.links?.next;
    if (!next) break;
    if (typeof next !== "string" || visited.has(next)) throw new Error("Invalid cascade pagination link");
    visited.add(next);
    response = await axiosInstance.get(next);
  }
  return { rows, included };
}

/**
 * Every cascade whose origin job ran on this workspace, most recent first. JSON:API, same
 * client/pattern as RunTriggers.tsx - there is no GraphQL precedent for this entity yet and the
 * nested runCascadeNode/runCascadeEdge routes below only exist on the JSON:API side.
 */
export async function listCascadesForWorkspace(workspaceId: string): Promise<RunCascadeRow[]> {
  const response = await readCollection<RunCascade>("runCascade", {
    params: {
      "filter[runCascade]": `originJob.workspace.id==${workspaceId},runCascadeNode.workspace.id==${workspaceId}`,
      sort: "-createdDate",
    },
  });
  const cascades = response.rows;
  return cascades.map((cascade) => ({
    id: cascade.id,
    status: cascade.attributes.status,
    originJobId: cascade.relationships.originJob?.data?.id,
    createdDate: cascade.attributes.createdDate,
  }));
}

export type CascadeNodesResponse = {
  nodes: RunCascadeNode[];
  included: {
    type: string;
    id: string;
    attributes: { name?: string; attemptNumber?: number; outcome?: string };
    relationships?: { job?: { data?: { id: string } } };
  }[];
};

export async function getCascadeNodes(cascadeId: string): Promise<CascadeNodesResponse> {
  const response = await readCollection<RunCascadeNode>(`runCascade/${cascadeId}/runCascadeNode`, {
    params: { include: "workspace,runCascadeNodeAttempt" },
  });
  return { nodes: response.rows, included: response.included };
}

export async function getCascadeEdges(cascadeId: string): Promise<RunCascadeEdge[]> {
  const response = await readCollection<RunCascadeEdge>(`runCascade/${cascadeId}/runCascadeEdge`);
  return response.rows;
}

export type CascadeActionResult = {
  nodeId?: string;
  status: string;
  jobId?: number;
};

export async function cancelCascade(cascadeId: string): Promise<void> {
  await axiosAdmin.post(`/admin/v1/cascades/${cascadeId}/cancel`, {});
}

export async function retryNode(nodeId: string): Promise<CascadeActionResult> {
  const response = await axiosAdmin.post<CascadeActionResult>(`/admin/v1/cascades/nodes/${nodeId}/retry`, {});
  return response.data;
}

export async function resumeNode(nodeId: string): Promise<CascadeActionResult> {
  const response = await axiosAdmin.post<CascadeActionResult>(`/admin/v1/cascades/nodes/${nodeId}/resume`, {});
  return response.data;
}

/**
 * Same intent as axiosConfig's getErrorMessage, but reads the admin controller's own plain-text
 * (not JSON:API) 404/409 bodies first - "No such cascade node: ..." / "Only a failed or skipped
 * node can be retried, not RUNNING" are the whole point of surfacing these to the operator.
 */
export function getAdminErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data;
    if (typeof body === "string" && body.trim() !== "") {
      return body;
    }
  }
  return getErrorMessage(error);
}

export type CascadeCapabilities = {
  /** May retry, resume and cancel cascades (and replay trigger events) in the given organization. */
  operate: boolean;
  /** May use the instance-wide admin pages. */
  instanceAdmin: boolean;
};

/**
 * Capability checks use the same server-side rule as the action endpoints: an instance admin, or
 * the manage job permission in the given organization.
 */
export async function getCascadeCapabilities(organizationId?: string): Promise<CascadeCapabilities> {
  const response = await axiosAdmin.get("/admin/v1/cascades/capabilities", {
    params: organizationId ? { organizationId } : undefined,
  });
  return { operate: response.data.operate === true, instanceAdmin: response.data.instanceAdmin === true };
}

/** Most recent cascades shown on the organization page; older ones are reachable from their workspace. */
const ORGANIZATION_CASCADE_LIMIT = 100;

/**
 * The organization's most recent cascades, optionally limited to some statuses, with the workspace
 * each one started from. One page only: this is an operator's view of what needs attention, not an
 * archive.
 */
export async function listCascadesForOrganization(
  organizationId: string,
  statuses?: RunCascadeStatus[]
): Promise<RunCascadeRow[]> {
  const filter = [`organization.id==${organizationId}`];
  if (statuses && statuses.length > 0) {
    filter.push(`status=in=(${statuses.join(",")})`);
  }
  const response = await axiosInstance.get("runCascade", {
    params: {
      "filter[runCascade]": filter.join(";"),
      sort: "-createdDate",
      include: "originJob.workspace",
      "page[size]": ORGANIZATION_CASCADE_LIMIT,
    },
  });
  const included: { type: string; id: string; attributes?: { name?: string }; relationships?: Record<string, any> }[] =
    response.data.included ?? [];
  const jobs = new Map(included.filter((item) => item.type === "job").map((item) => [item.id, item]));
  const workspaces = new Map(included.filter((item) => item.type === "workspace").map((item) => [item.id, item]));
  return (response.data.data as RunCascade[]).map((cascade) => {
    const originJobId = cascade.relationships.originJob?.data?.id;
    const workspaceId = originJobId ? jobs.get(originJobId)?.relationships?.workspace?.data?.id : undefined;
    return {
      id: cascade.id,
      status: cascade.attributes.status,
      originJobId,
      createdDate: cascade.attributes.createdDate,
      originWorkspaceId: workspaceId,
      originWorkspaceName: workspaceId ? workspaces.get(workspaceId)?.attributes?.name : undefined,
    };
  });
}

export async function getCascade(cascadeId: string): Promise<RunCascade> {
  const response = await axiosInstance.get(`runCascade/${cascadeId}`);
  return response.data.data;
}
