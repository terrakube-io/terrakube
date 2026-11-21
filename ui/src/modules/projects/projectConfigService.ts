import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { isPlanOnlyTemplate } from "@/domain/Jobs/planOnlyTemplate";
import quoteRsql from "@/modules/workspaces/utils/quoteRsql";

export type ProjectCollection = {
  id: string;
  name: string;
  description?: string;
  priority: number;
  variableCount: number;
  referenceId?: string;
};

// Every collection in the organization, marked with the reference that attaches it to this project (if any).
export async function listCollectionsForProject(
  organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectCollection[]>> {
  const body = {
    query: `query ($orgId: String!, $filter: String) {
      organization(ids: [$orgId]) {
        edges { node { collection {
          edges { node {
            id name description priority
            item(first: "1", after: "0") { pageInfo { totalRecords } }
            reference(filter: $filter) { edges { node { id } } }
          } }
        } } }
      }
    }`,
    variables: { orgId: organizationId, filter: `scope=='PROJECT';project.id==${quoteRsql(projectId)}` },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: [] };
  }
  const edges = result.data?.organization?.edges?.[0]?.node?.collection?.edges ?? [];
  return {
    isError: false,
    responseCode: result.responseCode,
    data: edges.map((edge: any) => ({
      id: edge.node.id,
      name: edge.node.name,
      description: edge.node.description ?? undefined,
      priority: edge.node.priority ?? 0,
      variableCount: edge.node.item?.pageInfo?.totalRecords ?? 0,
      referenceId: edge.node.reference?.edges?.[0]?.node?.id,
    })),
  };
}

export async function attachCollectionToProject(
  organizationId: string,
  projectId: string,
  collectionId: string
): Promise<void> {
  await axiosInstance.post(
    `organization/${organizationId}/collection/${collectionId}/reference`,
    {
      data: {
        type: "reference",
        attributes: { description: `Reference to project ${projectId}`, scope: "PROJECT" },
        relationships: { project: { data: { type: "project", id: projectId } } },
      },
    },
    { headers: { "Content-Type": "application/vnd.api+json" } }
  );
}

export async function detachCollectionFromProject(
  organizationId: string,
  collectionId: string,
  referenceId: string
): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/collection/${collectionId}/reference/${referenceId}`);
}

export type ProjectDefaultTag = { id: string; tagId: string; value?: string };

export type OrganizationTag = { id: string; name: string };

export async function listProjectDefaultTags(organizationId: string, projectId: string): Promise<ProjectDefaultTag[]> {
  const response = await axiosInstance.get(`organization/${organizationId}/project/${projectId}/projectTag`);
  return (response.data?.data ?? []).map((item: any) => ({
    id: item.id,
    tagId: item.attributes?.tagId,
    value: item.attributes?.value ?? undefined,
  }));
}

export async function listOrganizationTags(organizationId: string): Promise<OrganizationTag[]> {
  const response = await axiosInstance.get(`organization/${organizationId}/tag`);
  return (response.data?.data ?? []).map((item: any) => ({
    id: item.id,
    name: item.attributes?.name,
  }));
}

export async function addProjectDefaultTag(
  organizationId: string,
  projectId: string,
  tagId: string,
  value?: string
): Promise<void> {
  await axiosInstance.post(
    `organization/${organizationId}/project/${projectId}/projectTag`,
    { data: { type: "project_tag", attributes: { tagId, value: value || null } } },
    { headers: { "Content-Type": "application/vnd.api+json" } }
  );
}

export async function removeProjectDefaultTag(organizationId: string, projectId: string, id: string): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/project/${projectId}/projectTag/${id}`);
}

export type ProjectPolicySet = {
  id: string;
  name: string;
  description?: string;
  enforcementLevel: string;
  global: boolean;
  attachedToProject: boolean;
};

// Policy sets that apply to the project: global ones (every workspace) and ones attached to this project.
// Tag and workspace attachments are not listed here because they do not follow the project.
export async function listProjectPolicySets(
  organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectPolicySet[]>> {
  const body = {
    query: `query ($orgId: String!) {
      organization(ids: [$orgId]) {
        edges { node { policySet {
          edges { node {
            id name description enforcementLevel global
            attachments { edges { node { project { edges { node { id } } } } } }
          } }
        } } }
      }
    }`,
    variables: { orgId: organizationId },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: [] };
  }
  const edges = result.data?.organization?.edges?.[0]?.node?.policySet?.edges ?? [];
  const sets: ProjectPolicySet[] = edges.map((edge: any) => ({
    id: edge.node.id,
    name: edge.node.name,
    description: edge.node.description ?? undefined,
    enforcementLevel: edge.node.enforcementLevel,
    global: !!edge.node.global,
    attachedToProject: (edge.node.attachments?.edges ?? []).some((attachment: any) =>
      (attachment.node?.project?.edges ?? []).some((project: any) => project.node?.id === projectId)
    ),
  }));
  return {
    isError: false,
    responseCode: result.responseCode,
    data: sets.filter((set) => set.global || set.attachedToProject),
  };
}

export type ApplyDefaultTagsResult = { workspaces: number; workspacesChanged: number; tagsAdded: number };

// dryRun only counts what would change, so the UI can show the impact before anything is written.
export async function applyProjectDefaultTags(
  organizationId: string,
  projectId: string,
  dryRun: boolean
): Promise<ApplyDefaultTagsResult> {
  const url = `${
    new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
  }/access-token/v1/projects/organization/${organizationId}/project/${projectId}/default-tags/apply`;
  const response = await axiosInstance.post(url, null, { params: { dryRun } });
  return response.data;
}

export type OrganizationTemplate = { id: string; name: string; planOnly: boolean };

export async function listOrganizationTemplates(organizationId: string): Promise<OrganizationTemplate[]> {
  const response = await axiosInstance.get(`organization/${organizationId}/template`);
  // The template body is base64, which isPlanOnlyTemplate decodes. Only templates that just plan are offered.
  return (response.data?.data ?? []).map((item: any) => ({
    id: item.id,
    name: item.attributes?.name,
    planOnly: isPlanOnlyTemplate(item.attributes?.tcl),
  }));
}
