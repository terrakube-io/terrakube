import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";

export type WorkspaceAccessModel = {
  id: string;
  name: string;
  role: string;
  manageWorkspace: boolean;
  manageState: boolean;
  planJob: boolean;
  approveJob: boolean;
};

export type WorkspaceAccessPermissions = {
  manageWorkspace: boolean;
  manageState: boolean;
  planJob: boolean;
  approveJob: boolean;
};

async function listWorkspaceAccess(
  organizationId: string,
  workspaceId: string
): Promise<ApiResponse<WorkspaceAccessModel[]>> {
  const body = {
    query: `{
      workspace(ids: ["${workspaceId}"]) {
        edges {
          node {
            access {
              edges {
                node {
                  id
                  name
                  role
                  manageWorkspace
                  manageState
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

  const edges = tempData.data?.workspace?.edges?.[0]?.node?.access?.edges ?? [];

  return {
    isError: false,
    responseCode: tempData.responseCode,
    data: edges.map((edge: any) => ({
      id: edge.node.id,
      name: edge.node.name,
      role: edge.node.role ?? "custom",
      manageWorkspace: edge.node.manageWorkspace ?? false,
      manageState: edge.node.manageState ?? false,
      planJob: edge.node.planJob ?? false,
      approveJob: edge.node.approveJob ?? false,
    })),
  };
}

async function addWorkspaceAccess(
  organizationId: string,
  workspaceId: string,
  teamName: string,
  role: string,
  permissions?: WorkspaceAccessPermissions
): Promise<void> {
  const body = {
    data: {
      type: "access",
      attributes: {
        name: teamName,
        role,
        ...(permissions ?? {}),
      },
    },
  };

  await axiosInstance.post(`organization/${organizationId}/workspace/${workspaceId}/access`, body, {
    headers: { "Content-Type": "application/vnd.api+json" },
  });
}

async function updateWorkspaceAccess(
  organizationId: string,
  workspaceId: string,
  accessId: string,
  role: string,
  permissions?: WorkspaceAccessPermissions
): Promise<void> {
  const body = {
    data: {
      id: accessId,
      type: "access",
      attributes: { role, ...(permissions ?? {}) },
    },
  };
  await axiosInstance.patch(`organization/${organizationId}/workspace/${workspaceId}/access/${accessId}`, body, {
    headers: { "Content-Type": "application/vnd.api+json" },
  });
}

async function removeWorkspaceAccess(organizationId: string, workspaceId: string, accessId: string): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/workspace/${workspaceId}/access/${accessId}`);
}

const methods = {
  listWorkspaceAccess,
  addWorkspaceAccess,
  updateWorkspaceAccess,
  removeWorkspaceAccess,
};

export default methods;
