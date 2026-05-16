import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { ProjectModel } from "@/domain/types";

export type ProjectAccessModel = {
  id: string;
  name: string;
  role: string;
  manageWorkspace: boolean;
  manageState: boolean;
  manageJob: boolean;
  planJob: boolean;
  approveJob: boolean;
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

const methods = {
  listProjects,
  getProject,
  createProject,
  updateProject,
  deleteProject,
  listProjectAccess,
  addProjectAccess,
  removeProjectAccess,
};

export default methods;
