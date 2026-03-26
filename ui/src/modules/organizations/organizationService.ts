import { apiGet } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { axiosGraphQL } from "@/config/axiosConfig";
import { ApiWorkspaceTag, FlatOrganization, Organization } from "../../domain/types";

async function listOrganizations(): Promise<ApiResponse<Organization[]>> {
  return await apiGet("/api/v1/organization", { dataWrapped: true });
}

async function listOrganizationsGraphQL(): Promise<FlatOrganization[]> {
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
  }));
}

async function getOrganizationNameGraphQL(orgId: string): Promise<string | null> {
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

async function listOrganizationTags(organizationId: string): Promise<ApiResponse<ApiWorkspaceTag[]>> {
  return await apiGet(`/api/v1/organization/${organizationId}/tag`, { dataWrapped: true });
}

const methods = {
  listOrganizations,
  listOrganizationsGraphQL,
  getOrganizationNameGraphQL,
  listOrganizationTags,
};

export default methods;
