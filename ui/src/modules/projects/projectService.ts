import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import containsFilter from "@/modules/workspaces/utils/containsFilter";
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
      sort: query.sort,
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
    filter: `id=='${projectId}'`,
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
  data: { name: string; description?: string }
): Promise<void> {
  const body = {
    data: {
      id: projectId,
      type: "project",
      attributes: {
        name: data.name,
        description: data.description ?? "",
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
            project(ids: ["${projectId}"]) {
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
      data: { id: "", name: "" },
    };
  }

  const node = tempData.data?.organization?.edges?.[0]?.node?.project?.edges?.[0]?.node;

  return {
    isError: false,
    responseCode: tempData.responseCode,
    data: {
      id: node?.id ?? "",
      name: node?.name ?? "",
      description: node?.description,
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

const methods = {
  listProjects,
  listProjectSummaries,
  countUnassignedWorkspaces,
  getProjectSummary,
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
