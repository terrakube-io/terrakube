import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { getProjectSummary } from "./projectSummaryService";

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

// Read through the organization, because a caller who only has project access gets nothing from the root project
// collection. A caller who cannot manage the project's access sees the project but an empty list here.
export async function listProjectAccess(
  organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectAccessModel[]>> {
  const body = {
    query: `{
      organization(ids: ["${organizationId}"]) {
        edges {
          node {
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

  const edges = tempData.data?.organization?.edges?.[0]?.node?.project?.edges?.[0]?.node?.projectAccess?.edges ?? [];

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

export type ProjectTeams = {
  teams: ProjectAccessModel[];
  /** True when the project has teams this caller is not allowed to see, so the list is incomplete. */
  restricted: boolean;
};

// The visible teams, and whether some are hidden: the summary counts every access row, the list only the readable ones.
export async function listProjectTeams(organizationId: string, projectId: string): Promise<ApiResponse<ProjectTeams>> {
  const [access, summary] = await Promise.all([
    listProjectAccess(organizationId, projectId),
    getProjectSummary(organizationId, projectId),
  ]);
  if (access.isError) {
    return {
      isError: true,
      responseCode: access.responseCode,
      error: access.error,
      data: { teams: [], restricted: false },
    };
  }
  const teams = access.data ?? [];
  const total = summary.isError ? teams.length : (summary.data?.teamCount ?? teams.length);
  return {
    isError: false,
    responseCode: access.responseCode,
    data: { teams, restricted: teams.length < total },
  };
}

export async function addProjectAccess(
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

export async function removeProjectAccess(organizationId: string, projectId: string, accessId: string): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/project/${projectId}/projectAccess/${accessId}`);
}

export async function updateProjectAccess(
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
