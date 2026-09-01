import { apiGet } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { axiosGraphQL } from "@/config/axiosConfig";
import { FlatOrganization, Organization } from "../../domain/types";
import { TagModel } from "./types";
import { isOrgId } from "@/config/orgId";

type OrganizationSummary = {
  id: string;
  name: string;
  description?: string;
  executionMode?: string;
  icon?: string;
  workspaceCount: number;
  statusCounts: Record<string, number>;
};

async function listOrganizations(): Promise<ApiResponse<Organization[]>> {
  return await apiGet("/api/v1/organization", { dataWrapped: true });
}

async function listOrganizationSummaries(): Promise<FlatOrganization[]> {
  const response = await apiGet<OrganizationSummary[]>("/ui/v1/organizations/summary", {
    contentType: "application/json",
  });
  if (response.isError) {
    throw new Error(response.error?.message || response.error?.status || "Failed to load organizations");
  }

  return (response.data ?? []).map((organization) => ({
    id: organization.id,
    name: organization.name,
    description: organization.description,
    executionMode: organization.executionMode,
    icon: organization.icon,
    workspaceCount: organization.workspaceCount,
    workspaceStatusCounts: organization.statusCounts,
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
