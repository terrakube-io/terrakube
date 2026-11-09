import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import containsFilter from "@/modules/workspaces/utils/containsFilter";
import quoteRsql from "@/modules/workspaces/utils/quoteRsql";
import { ProjectModel, ProjectSummaryModel } from "@/domain/types";

export type ProjectAccessModel = {
  id: string;
  name: string;
  role: string;
  manageWorkspace: boolean;
  manageState: boolean;
  manageJob: boolean;
  planJob: boolean;
  approveJob: boolean;
  destroyJob: boolean;
};

// The individual permissions a team gets on every workspace of the project when its role is "custom".
export type ProjectAccessPermissions = {
  manageWorkspace: boolean;
  manageState: boolean;
  planJob: boolean;
  approveJob: boolean;
  destroyJob: boolean;
};

async function listProjects(organizationId: string): Promise<ApiResponse<ProjectModel[]>> {
  const body = {
    query: `{
      organization(ids: ["${organizationId}"]) {
        edges {
          node {
            project {
              edges {
                node {
                  id
                  name
                  description
                }
              }
            }
          }
        }
      }
    }`,
  };

  const tempData = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });

  if (tempData.isError) {
    return {
      isError: true,
      responseCode: tempData.responseCode,
      error: tempData.error,
      data: [],
    };
  }

  const edges = tempData.data?.organization?.edges?.[0]?.node?.project?.edges ?? [];

  return {
    isError: false,
    responseCode: tempData.responseCode,
    data: edges.map((edge: any) => ({
      id: edge.node.id,
      name: edge.node.name,
      description: edge.node.description,
    })),
  };
}

export type ProjectSummaryQuery = {
  filter?: string;
  search?: string;
  attentionOnly?: boolean;
  sort: string;
  page: number;
  pageSize: number;
};

export type ProjectSummaryPage = {
  items: ProjectSummaryModel[];
  totalRecords: number;
};

export function buildProjectSummaryFilter(query: Pick<ProjectSummaryQuery, "search" | "attentionOnly">): string {
  const parts: string[] = [];
  const search = query.search?.trim();
  if (search) {
    const value = containsFilter(search);
    if (value) parts.push(`(name=ini=${value},description=ini=${value})`);
  }
  if (query.attentionOnly) parts.push("attentionCount=gt=0");
  return parts.join(";");
}

// The databases disagree on where a null sorts, so a project that never ran would lead the list on one and trail it on
// another. Sorting by hasRun first puts those projects last, whichever way the last run is ordered.
export function withNeverRunLast(sort: string): string {
  return sort
    .split(",")
    .flatMap((term) => (term.slice(1) === "lastRunAt" ? ["-hasRun", term] : [term]))
    .join(",");
}

// Server-side paged and sorted list. Always sends "after" because Elide rejects "first" without it.
async function listProjectSummaries(
  organizationId: string,
  query: ProjectSummaryQuery
): Promise<ApiResponse<ProjectSummaryPage>> {
  const body = {
    query: `query ProjectSummaries($orgId: String!, $first: Int!, $after: String, $sort: String, $filter: String) {
      organization(ids: [$orgId]) {
        edges {
          node {
            projectSummary(first: $first, after: $after, sort: $sort, filter: $filter) {
              edges {
                node {
                  id name description workspaceCount completedCount failedCount runningCount queuedCount
                  waitingApprovalCount neverRunCount nonCompliantCount lockedCount attentionCount lastRunAt teamCount
                }
              }
              pageInfo { totalRecords }
            }
          }
        }
      }
    }`,
    variables: {
      orgId: organizationId,
      first: query.pageSize,
      after: String((query.page - 1) * query.pageSize),
      sort: withNeverRunLast(query.sort),
      filter: query.filter ?? (buildProjectSummaryFilter(query) || null),
    },
  };

  const tempData = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });

  if (tempData.isError) {
    return {
      isError: true,
      responseCode: tempData.responseCode,
      error: tempData.error,
      data: { items: [], totalRecords: 0 },
    };
  }

  const connection = tempData.data?.organization?.edges?.[0]?.node?.projectSummary;
  const items: ProjectSummaryModel[] = (connection?.edges ?? []).map((edge: any) => ({
    ...edge.node,
    description: edge.node.description ?? undefined,
    lastRunAt: edge.node.lastRunAt ?? undefined,
  }));

  return {
    isError: false,
    responseCode: tempData.responseCode,
    data: { items, totalRecords: connection?.pageInfo?.totalRecords ?? items.length },
  };
}

async function getProjectSummary(
  organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectSummaryModel | null>> {
  const result = await listProjectSummaries(organizationId, {
    filter: `id==${quoteRsql(projectId)}`,
    sort: "+name",
    page: 1,
    pageSize: 1,
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: null };
  }
  return { isError: false, responseCode: result.responseCode, data: result.data?.items[0] ?? null };
}

// Workspaces that are not in any project; shown as the "Unassigned" row on the projects index.
async function countUnassignedWorkspaces(organizationId: string): Promise<ApiResponse<number>> {
  const body = {
    query: `query Unassigned($orgId: String!) {
      organization(ids: [$orgId]) {
        edges { node { workspace(filter: "project=isnull=true", first: 1, after: "0") { pageInfo { totalRecords } } } }
      }
    }`,
    variables: { orgId: organizationId },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: 0 };
  }
  const total = result.data?.organization?.edges?.[0]?.node?.workspace?.pageInfo?.totalRecords ?? 0;
  return { isError: false, responseCode: result.responseCode, data: total };
}

async function createProject(organizationId: string, data: { name: string; description?: string }): Promise<void> {
  const body = {
    data: {
      type: "project",
      attributes: {
        name: data.name,
        description: data.description ?? "",
      },
    },
  };

  await axiosInstance.post(`organization/${organizationId}/project`, body, {
    headers: { "Content-Type": "application/vnd.api+json" },
  });
}

async function updateProject(
  organizationId: string,
  projectId: string,
  data: { name: string; description?: string; landingTab?: string | null }
): Promise<void> {
  const body = {
    data: {
      id: projectId,
      type: "project",
      attributes: {
        name: data.name,
        description: data.description ?? "",
        // null clears the override so the organization default applies again.
        ...(data.landingTab !== undefined ? { landingTab: data.landingTab } : {}),
      },
    },
  };

  await axiosInstance.patch(`organization/${organizationId}/project/${projectId}`, body, {
    headers: { "Content-Type": "application/vnd.api+json" },
  });
}

async function getProject(organizationId: string, projectId: string): Promise<ApiResponse<ProjectModel>> {
  const body = {
    query: `{
      organization(ids: ["${organizationId}"]) {
        edges {
          node {
            projectLandingTab
            project(ids: ["${projectId}"]) {
              edges {
                node {
                  id
                  name
                  description
                  landingTab
                }
              }
            }
          }
        }
      }
    }`,
  };

  const tempData = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });

  if (tempData.isError) {
    return {
      isError: true,
      responseCode: tempData.responseCode,
      error: tempData.error,
      data: { id: "", name: "" },
    };
  }

  const organization = tempData.data?.organization?.edges?.[0]?.node;
  const node = organization?.project?.edges?.[0]?.node;

  return {
    isError: false,
    responseCode: tempData.responseCode,
    data: {
      id: node?.id ?? "",
      name: node?.name ?? "",
      description: node?.description,
      landingTab: node?.landingTab ?? undefined,
      organizationLandingTab: organization?.projectLandingTab ?? undefined,
    },
  };
}

async function deleteProject(organizationId: string, projectId: string): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/project/${projectId}`);
}

async function listProjectAccess(
  _organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectAccessModel[]>> {
  const body = {
    query: `{
      project(ids: ["${projectId}"]) {
        edges {
          node {
            projectAccess {
              edges {
                node {
                  id
                  name
                  role
                  manageWorkspace
                  manageState
                  manageJob
                  planJob
                  approveJob
                  destroyJob
                }
              }
            }
          }
        }
      }
    }`,
  };

  const tempData = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });

  if (tempData.isError) {
    return {
      isError: true,
      responseCode: tempData.responseCode,
      error: tempData.error,
      data: [],
    };
  }

  const edges = tempData.data?.project?.edges?.[0]?.node?.projectAccess?.edges ?? [];

  return {
    isError: false,
    responseCode: tempData.responseCode,
    data: edges.map((edge: any) => ({
      id: edge.node.id,
      name: edge.node.name,
      role: edge.node.role ?? "custom",
      manageWorkspace: edge.node.manageWorkspace ?? false,
      manageState: edge.node.manageState ?? false,
      manageJob: edge.node.manageJob ?? false,
      planJob: edge.node.planJob ?? false,
      approveJob: edge.node.approveJob ?? false,
      destroyJob: edge.node.destroyJob ?? true,
    })),
  };
}

async function addProjectAccess(
  organizationId: string,
  projectId: string,
  teamName: string,
  role: string,
  permissions?: ProjectAccessPermissions
): Promise<void> {
  const body = {
    data: {
      type: "project_access",
      attributes: {
        name: teamName,
        role,
        ...(permissions ?? {}),
      },
    },
  };

  await axiosInstance.post(`organization/${organizationId}/project/${projectId}/projectAccess`, body, {
    headers: { "Content-Type": "application/vnd.api+json" },
  });
}

async function removeProjectAccess(organizationId: string, projectId: string, accessId: string): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/project/${projectId}/projectAccess/${accessId}`);
}

async function updateProjectAccess(
  organizationId: string,
  projectId: string,
  accessId: string,
  role: string,
  permissions?: ProjectAccessPermissions
): Promise<void> {
  const body = {
    data: {
      id: accessId,
      type: "project_access",
      attributes: { role, ...(permissions ?? {}) },
    },
  };
  await axiosInstance.patch(`organization/${organizationId}/project/${projectId}/projectAccess/${accessId}`, body, {
    headers: { "Content-Type": "application/vnd.api+json" },
  });
}

export type ProjectCollection = {
  id: string;
  name: string;
  description?: string;
  priority: number;
  variableCount: number;
  referenceId?: string;
};

// Every collection in the organization, marked with the reference that attaches it to this project (if any).
async function listCollectionsForProject(
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

async function attachCollectionToProject(
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

async function detachCollectionFromProject(
  organizationId: string,
  collectionId: string,
  referenceId: string
): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/collection/${collectionId}/reference/${referenceId}`);
}

export type ProjectDefaultTag = { id: string; tagId: string; value?: string };
export type OrganizationTag = { id: string; name: string };

async function listProjectDefaultTags(organizationId: string, projectId: string): Promise<ProjectDefaultTag[]> {
  const response = await axiosInstance.get(`organization/${organizationId}/project/${projectId}/projectTag`);
  return (response.data?.data ?? []).map((item: any) => ({
    id: item.id,
    tagId: item.attributes?.tagId,
    value: item.attributes?.value ?? undefined,
  }));
}

async function listOrganizationTags(organizationId: string): Promise<OrganizationTag[]> {
  const response = await axiosInstance.get(`organization/${organizationId}/tag`);
  return (response.data?.data ?? []).map((item: any) => ({ id: item.id, name: item.attributes?.name }));
}

async function addProjectDefaultTag(
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

async function removeProjectDefaultTag(organizationId: string, projectId: string, id: string): Promise<void> {
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
async function listProjectPolicySets(
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

export type AttentionWorkspace = { id: string; name: string; lastJobStatus: string; policyComplianceStatus?: string };

// The first few workspaces of a project that failed, wait for approval or break policy.
async function listAttentionWorkspaces(
  organizationId: string,
  projectId: string,
  limit = 5
): Promise<ApiResponse<AttentionWorkspace[]>> {
  const body = {
    query: `query ($orgId: String!, $first: Int!, $filter: String) {
      organization(ids: [$orgId]) {
        edges { node { workspace(first: $first, after: "0", sort: "-lastJobDate", filter: $filter) {
          edges { node { id name lastJobStatus policyComplianceStatus } }
        } } }
      }
    }`,
    variables: {
      orgId: organizationId,
      first: limit,
      filter: `project.id=='${projectId}';(lastJobStatus=in=('failed','waitingApproval'),policyComplianceStatus=='NON_COMPLIANT')`,
    },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: [] };
  }
  const edges = result.data?.organization?.edges?.[0]?.node?.workspace?.edges ?? [];
  return {
    isError: false,
    responseCode: result.responseCode,
    data: edges.map((edge: any) => ({
      id: edge.node.id,
      name: edge.node.name,
      lastJobStatus: edge.node.lastJobStatus,
      policyComplianceStatus: edge.node.policyComplianceStatus ?? undefined,
    })),
  };
}

export type ApplyDefaultTagsResult = { workspaces: number; workspacesChanged: number; tagsAdded: number };

// dryRun only counts what would change, so the UI can show the impact before anything is written.
async function applyProjectDefaultTags(
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

const methods = {
  listProjects,
  listProjectSummaries,
  countUnassignedWorkspaces,
  getProjectSummary,
  applyProjectDefaultTags,
  listAttentionWorkspaces,
  listProjectPolicySets,
  listProjectDefaultTags,
  listOrganizationTags,
  addProjectDefaultTag,
  removeProjectDefaultTag,
  listCollectionsForProject,
  attachCollectionToProject,
  detachCollectionFromProject,
  getProject,
  createProject,
  updateProject,
  deleteProject,
  listProjectAccess,
  addProjectAccess,
  updateProjectAccess,
  removeProjectAccess,
};

export default methods;
