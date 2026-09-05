import { apiGet } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { axiosGraphQL } from "@/config/axiosConfig";
import { FlatOrganization, Organization } from "../../domain/types";
import { TagModel } from "./types";
import { WorkspaceStatusFilter } from "@/modules/workspaces/utils/workspaceFilter";
import { isOrgId } from "@/config/orgId";

function computeWorkspaceStatusCounts(edges: { node: { lastJobStatus?: string } }[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const edge of edges) {
    const status = edge.node.lastJobStatus || WorkspaceStatusFilter.NeverExecuted;
    counts[status] = (counts[status] || 0) + 1;
  }
  return counts;
}

async function listOrganizations(): Promise<ApiResponse<Organization[]>> {
  return await apiGet("/api/v1/organization", { dataWrapped: true });
}

async function listOrganizationSummaries(): Promise<FlatOrganization[]> {
  const body = {
    query: `{
      organization {
        edges {
          node {
            id
            name
            description
            executionMode
            icon
            workspace {
              edges {
                node {
                  id
                  lastJobStatus
                }
              }
            }
          }
        }
      }
    }`,
  };

  const response = await axiosGraphQL.post("", body, {
    headers: { "Content-Type": "application/json" },
  });

  if (response.data?.errors?.length) {
    throw new Error(response.data.errors[0].message || "Failed to load organizations");
  }

  const data = response.data?.data;
  if (!data?.organization?.edges) {
    return [];
  }

  return data.organization.edges.map((edge: any) => ({
    id: edge.node.id,
    name: edge.node.name,
    description: edge.node.description,
    executionMode: edge.node.executionMode,
    icon: edge.node.icon,
    workspaceCount: edge.node.workspace?.edges?.length,
    workspaceStatusCounts: computeWorkspaceStatusCounts(edge.node.workspace?.edges ?? []),
  }));
}

async function getOrganizationNameGraphQL(orgId: string): Promise<string | null> {
  if (!isOrgId(orgId)) {
    return null;
  }
  const body = {
    query: `{
      organization(ids: ["${orgId}"]) {
        edges {
          node {
            id
            name
          }
        }
      }
    }`,
  };

  const response = await axiosGraphQL.post("", body, {
    headers: { "Content-Type": "application/json" },
  });

  if (response.data?.errors?.length) {
    throw new Error(response.data.errors[0].message || "Failed to load organization");
  }

  const data = response.data?.data;
  if (!data?.organization?.edges?.length) {
    return null;
  }

  return data.organization.edges[0].node.name;
}

async function listOrganizationTags(organizationId: string): Promise<TagModel[]> {
  const body = {
    query: `{
      organization(ids: ["${organizationId}"]) {
        edges {
          node {
            tag {
              edges {
                node {
                  id
                  name
                }
              }
            }
          }
        }
      }
    }`,
  };

  const response = await axiosGraphQL.post("", body, {
    headers: { "Content-Type": "application/json" },
  });

  if (response.data?.errors?.length) {
    throw new Error(response.data.errors[0].message || "Failed to load organization tags");
  }

  const tagEdges = response.data?.data?.organization?.edges?.[0]?.node?.tag?.edges;
  if (!tagEdges) {
    return [];
  }

  return tagEdges.map((edge: any) => ({
    id: edge.node.id,
    name: edge.node.name,
  }));
}

const methods = {
  listOrganizations,
  listOrganizationSummaries,
  getOrganizationNameGraphQL,
  listOrganizationTags,
};

export default methods;
