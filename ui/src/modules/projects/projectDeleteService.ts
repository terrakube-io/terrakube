import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { getProjectSummary } from "./projectSummaryService";
import { listCollectionsForProject, listProjectDefaultTags } from "./projectConfigService";

export type DeletePreflight = {
  /** Null when the team list cannot be read by this caller. */
  teamCount: number | null;
  attachments: { id: string; policySetId: string }[];
  exemptions: { id: string; policySetId: string }[];
  collections: { id: string; name: string; referenceId: string }[];
  defaultTagCount: number;
  /** The reads that failed. While any did, an empty list means nothing is known, not that nothing is attached. */
  failed: ("policy sets" | "variable collections")[];
};

// Everything that points at the project besides its workspaces, so the dialog can say what the delete removes.
export async function getDeletePreflight(organizationId: string, projectId: string): Promise<DeletePreflight> {
  const body = {
    query: `query ($orgId: String!) {
      organization(ids: [$orgId]) { edges { node { policySet { edges { node {
        id
        attachments { edges { node { id project { edges { node { id } } } } } }
        exemptions { edges { node { id project { edges { node { id } } } } } }
      } } } } } }
    }`,
    variables: { orgId: organizationId },
  };
  const [policies, access, collections, tags] = await Promise.all([
    apiPost<unknown, any>("/graphql/api/v1", body, { dataWrapped: true, contentType: "application/json" }),
    getProjectSummary(organizationId, projectId),
    listCollectionsForProject(organizationId, projectId),
    listProjectDefaultTags(organizationId, projectId).catch(() => []),
  ]);
  const attachments: DeletePreflight["attachments"] = [];
  const exemptions: DeletePreflight["exemptions"] = [];
  const sets = policies.isError ? [] : (policies.data?.organization?.edges?.[0]?.node?.policySet?.edges ?? []);
  const onProject = (node: any) => (node?.project?.edges ?? []).some((edge: any) => edge.node?.id === projectId);
  sets.forEach((set: any) => {
    (set.node.attachments?.edges ?? []).forEach((edge: any) => {
      if (onProject(edge.node)) attachments.push({ id: edge.node.id, policySetId: set.node.id });
    });
    (set.node.exemptions?.edges ?? []).forEach((edge: any) => {
      if (onProject(edge.node)) exemptions.push({ id: edge.node.id, policySetId: set.node.id });
    });
  });
  const failed: DeletePreflight["failed"] = [];
  if (policies.isError) failed.push("policy sets");
  if (collections.isError) failed.push("variable collections");
  return {
    failed,
    teamCount: access.isError ? null : (access.data?.teamCount ?? null),
    attachments,
    exemptions,
    collections: (collections.data ?? [])
      .filter((item) => item.referenceId)
      .map((item) => ({ id: item.id, name: item.name, referenceId: item.referenceId as string })),
    defaultTagCount: tags.length,
  };
}

export async function deletePolicyAttachment(policySetId: string, id: string): Promise<void> {
  await axiosInstance.delete(`policy_set/${policySetId}/attachments/${id}`);
}

export async function deletePolicyExemption(id: string): Promise<void> {
  await axiosInstance.delete(`policy_exemption/${id}`);
}
