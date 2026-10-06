import axios from "axios";
import axiosInstance, { axiosAdmin, getErrorMessage } from "@/config/axiosConfig";
import { RunCascade, RunCascadeEdge, RunCascadeNode, RunCascadeRow } from "@/domain/types";

/**
 * Every cascade whose origin job ran on this workspace, most recent first. JSON:API, same
 * client/pattern as RunTriggers.tsx - there is no GraphQL precedent for this entity yet and the
 * nested runCascadeNode/runCascadeEdge routes below only exist on the JSON:API side.
 */
export async function listCascadesForWorkspace(workspaceId: string): Promise<RunCascadeRow[]> {
  const response = await axiosInstance.get("runCascade", {
    params: {
      "filter[runCascade]": `originJob.workspace.id==${workspaceId}`,
      sort: "-createdDate",
    },
  });
  const cascades: RunCascade[] = response.data.data ?? [];
  return cascades.map((cascade) => ({
    id: cascade.id,
    status: cascade.attributes.status,
    originJobId: cascade.relationships.originJob?.data?.id,
    createdDate: cascade.attributes.createdDate,
  }));
}

export type CascadeNodesResponse = {
  nodes: RunCascadeNode[];
  included: { type: string; id: string; attributes: { name: string } }[];
};

export async function getCascadeNodes(cascadeId: string): Promise<CascadeNodesResponse> {
  const response = await axiosInstance.get(`runCascade/${cascadeId}/runCascadeNode`, {
    params: { include: "workspace" },
  });
  return { nodes: response.data.data ?? [], included: response.data.included ?? [] };
}

export async function getCascadeEdges(cascadeId: string): Promise<RunCascadeEdge[]> {
  const response = await axiosInstance.get(`runCascade/${cascadeId}/runCascadeEdge`);
  return response.data.data ?? [];
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
