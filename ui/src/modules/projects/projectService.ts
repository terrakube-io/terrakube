import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { ProjectModel } from "@/domain/types";
import {
  listProjectSummaries,
  getProjectIndexCounts,
  getUnassignedSummary,
  getCurrentGroups,
  getDelivery,
  getProjectSummary,
  listAttentionWorkspaces,
  listRecentWorkspaces,
} from "./projectSummaryService";
import {
  listOrganizationTemplates,
  applyProjectDefaultTags,
  listProjectPolicySets,
  listProjectDefaultTags,
  listOrganizationTags,
  addProjectDefaultTag,
  removeProjectDefaultTag,
  listCollectionsForProject,
  attachCollectionToProject,
  detachCollectionFromProject,
} from "./projectConfigService";
import {
  listProjectTeams,
  listProjectAccess,
  addProjectAccess,
  updateProjectAccess,
  removeProjectAccess,
} from "./projectAccessService";
import { getWorkspaceVariableData } from "./projectVariablesService";
import { getDeletePreflight, deletePolicyAttachment, deletePolicyExemption } from "./projectDeleteService";

export * from "./projectSummaryService";
export * from "./projectAccessService";
export * from "./projectConfigService";
export * from "./projectDeleteService";
export * from "./projectVariablesService";

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
  data: {
    name: string;
    description?: string;
    landingTab?: string | null;
    defaultTerraformVersion?: string | null;
    defaultExecutionMode?: string | null;
    defaultTemplate?: string | null;
    ownerTeam?: string | null;
  }
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
        ...(data.defaultTerraformVersion !== undefined
          ? { defaultTerraformVersion: data.defaultTerraformVersion }
          : {}),
        ...(data.defaultExecutionMode !== undefined ? { defaultExecutionMode: data.defaultExecutionMode } : {}),
        ...(data.defaultTemplate !== undefined ? { defaultTemplate: data.defaultTemplate } : {}),
        ...(data.ownerTeam !== undefined ? { ownerTeam: data.ownerTeam } : {}),
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
            executionMode
            project(ids: ["${projectId}"]) {
              edges {
                node {
                  id
                  name
                  description
                  landingTab
                  defaultTerraformVersion
                  defaultExecutionMode
                  defaultTemplate
                  ownerTeam
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
      defaultTerraformVersion: node?.defaultTerraformVersion ?? undefined,
      defaultExecutionMode: node?.defaultExecutionMode ?? undefined,
      defaultTemplate: node?.defaultTemplate ?? undefined,
      ownerTeam: node?.ownerTeam ?? undefined,
      organizationExecutionMode: organization?.executionMode ?? undefined,
    },
  };
}

async function deleteProject(organizationId: string, projectId: string): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/project/${projectId}`);
}

const methods = {
  listProjects,
  listProjectSummaries,
  getProjectIndexCounts,
  getUnassignedSummary,
  getCurrentGroups,
  getDelivery,
  getProjectSummary,
  listOrganizationTemplates,
  listProjectTeams,
  getWorkspaceVariableData,
  getDeletePreflight,
  deletePolicyAttachment,
  deletePolicyExemption,
  applyProjectDefaultTags,
  listAttentionWorkspaces,
  listRecentWorkspaces,
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
