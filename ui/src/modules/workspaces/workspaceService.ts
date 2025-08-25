import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { ListWorkspacesResponse, WorkspaceListItem } from "@/modules/workspaces/types";
import formatSshUrl from "@/modules/workspaces/utils/formatSshUrl";

async function listWorkspaces(organizationId: string): Promise<ApiResponse<ListWorkspacesResponse>> {
  const body = {
    query: `{
          organization(ids: ["${organizationId}"]) {
            edges {
              node {
                id
                name
                workspace(sort: "name") {
                  edges {
                    node {
                      id
                      name
                      description
                      source
                      branch
                      terraformVersion
                      iacType
                      lastJobStatus
                      lastJobDate
                      workspaceTag {
                        edges { 
                          node {
                            id
                            tagId
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
      isError: tempData.isError,
      responseCode: tempData.responseCode,
      error: tempData.error,
      originResponseCode: tempData.originResponseCode,
      data: {
        organizationId: "",
        organizationName: "",
        workspaces: [],
      },
    };
  }
  const organization = tempData.data.organization.edges[0].node;
  const includes = tempData.data.organization.edges[0].node.workspace.edges;

  const workspaces = includes.map((element: any) => {
    console.log(element.node.name + " " + element.node.lastJobStatus + " " + element.node.lastJobDate);
    const lastStatus = element.node.lastJobStatus;
    const lastJobDate = element.node.lastJobDate;
    const ws: WorkspaceListItem = {
      id: element.node.id,
      lastRun: lastJobDate,
      lastStatus,
      name: element.node.name,
      description: element.node.description,
      branch: element.node.branch,
      iacType: element.node.iacType,
      source: element.node.source,
      normalizedSource: formatSshUrl(element.node.source),
      terraformVersion: element.node.terraformVersion,
      tags: element.node?.workspaceTag?.edges?.map((e: any) => e.node.tagId),
    };
    return ws;
  });

  return {
    isError: tempData.isError,
    responseCode: tempData.responseCode,
    error: tempData.error,
    originResponseCode: tempData.originResponseCode,
    data: {
      organizationId: organization?.id,
      organizationName: organization?.name,
      workspaces,
    },
  };
}

const methods = {
  listWorkspaces,
};

export default methods;
