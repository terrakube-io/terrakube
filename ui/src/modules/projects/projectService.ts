import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { ProjectModel } from "@/domain/types";
import quoteRsql from "@/modules/workspaces/utils/quoteRsql";

export type ProjectListItem = ProjectModel & {
  workspaceCount: number;
  teamCount: number;
};

type Connection<T> = { edges: { node: T }[] };
type TeamConnection = Connection<{ name: string }>;
type ProjectListNode = ProjectModel & { projectAccess: TeamConnection };
type ProjectListResponse = {
  organization: Connection<{ project: Connection<ProjectListNode> }>;
};

const COUNT_BATCH_SIZE = 50;
// ProjectAccess uses Elide's implicit page size; explicit paging is disabled by its read checks.
const ACCESS_PAGE_SIZE = 500;

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

async function listProjects(organizationId: string): Promise<ApiResponse<ProjectModel[]>>;
async function listProjects(organizationId: string, includeCounts: true): Promise<ApiResponse<ProjectListItem[]>>;
async function listProjects(
  organizationId: string,
  includeCounts = false
): Promise<ApiResponse<ProjectModel[] | ProjectListItem[]>> {
  const body = {
    query: `query Projects($organizationIds: [String]) {
      organization(ids: $organizationIds) {
        edges {
          node {
            project {
              edges {
                node {
                  id
                  name
                  description
                  ${includeCounts ? 'projectAccess(sort: "name") { edges { node { name } } }' : ""}
                }
              }
            }
          }
        }
      }
    }`,
    variables: { organizationIds: [organizationId] },
  };

  const tempData = await apiPost<unknown, ProjectListResponse>("/graphql/api/v1", body, {
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
  if (!includeCounts) {
    return {
      isError: false,
      responseCode: tempData.responseCode,
      data: edges.map(({ node }) => ({ id: node.id, name: node.name, description: node.description })),
    };
  }
  const projects: ProjectListItem[] = [];

  for (const { node } of edges) {
    let access = node.projectAccess.edges;
    const teams = new Set(access.map(({ node: team }) => team.name));
    // Seek by name rather than silently counting only the first 500 assignments.
    // Skipping repeated names at the boundary is safe because each team is counted once.
    while (access.length === ACCESS_PAGE_SIZE) {
      const afterName = access[access.length - 1].node.name;
      const next = await apiPost<unknown, { project: Connection<{ projectAccess: TeamConnection }> }>(
        "/graphql/api/v1",
        {
          query: `query ProjectTeams($projectIds: [String], $filter: String) {
            project(ids: $projectIds) {
              edges { node {
                projectAccess(sort: "name", filter: $filter) { edges { node { name } } }
              } }
            }
          }`,
          variables: { projectIds: [node.id], filter: `name=gt=${quoteRsql(afterName)}` },
        },
        { dataWrapped: true, contentType: "application/json" }
      );
      if (next.isError) return { ...next, data: [] };
      access = next.data?.project.edges[0]?.node.projectAccess.edges ?? [];
      access.forEach(({ node: team }) => teams.add(team.name));
    }
    projects.push({
      id: node.id,
      name: node.name,
      description: node.description,
      workspaceCount: 0,
      teamCount: teams.size,
    });
  }

  for (let offset = 0; offset < projects.length; offset += COUNT_BATCH_SIZE) {
    const batch = projects.slice(offset, offset + COUNT_BATCH_SIZE);
    const counts = await apiPost<
      unknown,
      { organization: Connection<Record<string, { pageInfo: { totalRecords: number } }>> }
    >(
      "/graphql/api/v1",
      {
        query: `query ProjectWorkspaceCounts($organizationIds: [String], ${batch
          .map((_, index) => `$p${index}: String`)
          .join(", ")}) {
          organization(ids: $organizationIds) {
            edges { node {
              ${batch
                .map((_, index) => `p${index}: workspace(first: "1", filter: $p${index}) { pageInfo { totalRecords } }`)
                .join("\n")}
            } }
          }
        }`,
        variables: {
          organizationIds: [organizationId],
          ...Object.fromEntries(batch.map((project, index) => [`p${index}`, `project.id==${quoteRsql(project.id)}`])),
        },
      },
      { dataWrapped: true, contentType: "application/json" }
    );
    if (counts.isError) return { ...counts, data: [] };
    const organization = counts.data?.organization.edges[0]?.node;
    for (const [index, project] of batch.entries()) {
      const count = organization?.[`p${index}`]?.pageInfo.totalRecords;
      if (typeof count !== "number" || count < 0) {
        return {
          isError: true,
          responseCode: 500,
          error: { status: "Failed to load project counts", message: "Workspace totals were not returned." },
          data: [],
        };
      }
      project.workspaceCount = count;
    }
  }

  return {
    isError: false,
    responseCode: tempData.responseCode,
    data: projects,
  };
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
  organizationId: string,
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
  role: string
): Promise<void> {
  const body = {
    data: {
      type: "project_access",
      attributes: {
        name: teamName,
        role,
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
  role: string
): Promise<void> {
  const body = {
    data: {
      id: accessId,
      type: "project_access",
      attributes: { role },
    },
  };
  await axiosInstance.patch(`organization/${organizationId}/project/${projectId}/projectAccess/${accessId}`, body, {
    headers: { "Content-Type": "application/vnd.api+json" },
  });
}

const methods = {
  listProjects,
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
