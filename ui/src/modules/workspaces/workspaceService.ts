import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import {
  ListWorkspacesResponse,
  WorkspaceListItem,
  WorkspacePageRequest,
  WorkspacePageResponse,
} from "@/modules/workspaces/types";
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
                      locked
                      workspaceTag {
                        edges {
                          node {
                            id
                            tagId
                          }
                        }
                      }
                      project {
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
      locked: element.node.locked,
      tags: element.node?.workspaceTag?.edges?.map((e: any) => e.node.tagId),
      projectId: element.node?.project?.edges?.[0]?.node?.id,
      projectName: element.node?.project?.edges?.[0]?.node?.name,
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

const workspaceSortMap: Record<WorkspacePageRequest["sort"], string> = {
  name_asc: "NAME_ASC",
  name_desc: "NAME_DESC",
  lastRun_asc: "LAST_RUN_ASC",
  lastRun_desc: "LAST_RUN_DESC",
  status: "STATUS",
  source_asc: "SOURCE_ASC",
  source_desc: "SOURCE_DESC",
  terraformVersion_asc: "TERRAFORM_VERSION_ASC",
  terraformVersion_desc: "TERRAFORM_VERSION_DESC",
};

async function listWorkspacePage(request: WorkspacePageRequest): Promise<ApiResponse<WorkspacePageResponse>> {
  const body = {
    query: `query WorkspacePage(
      $organizationId: ID!
      $first: Int!
      $after: String
      $search: String
      $status: String
      $tagIds: [ID!]
      $projectId: ID
      $sort: WorkspaceSort!
    ) {
      workspacePage(
        organizationId: $organizationId
        first: $first
        after: $after
        search: $search
        status: $status
        tagIds: $tagIds
        projectId: $projectId
        sort: $sort
      ) {
        nodes {
          id
          name
          description
          source
          branch
          terraformVersion
          iacType
          lastJobStatus
          lastJobDate
          locked
          tagIds
          projectId
          projectName
        }
        pageInfo { endCursor hasNextPage totalRecords }
        statusCounts { all waitingApproval failed pending queue running completed neverExecuted }
      }
    }`,
    variables: {
      organizationId: request.organizationId,
      first: request.first,
      after: request.after,
      search: request.search?.trim() || undefined,
      status: request.status,
      tagIds: request.tagIds?.length ? request.tagIds : undefined,
      projectId: request.projectId || undefined,
      sort: workspaceSortMap[request.sort],
    },
  };

  const response = await apiPost<typeof body, any>("/graphql", body, {
    dataWrapped: true,
    contentType: "application/json",
  });

  if (response.isError || !response.data?.workspacePage) {
    return {
      isError: true,
      responseCode: response.responseCode,
      error: response.error,
      originResponseCode: response.originResponseCode,
      data: {
        workspaces: [],
        pageInfo: { hasNextPage: false, totalRecords: 0 },
        statusCounts: {},
      },
    };
  }

  const page = response.data.workspacePage;
  const workspaces: WorkspaceListItem[] = page.nodes.map((node: any) => ({
    id: node.id,
    name: node.name,
    description: node.description,
    source: node.source ?? "",
    normalizedSource: node.source ? formatSshUrl(node.source) : undefined,
    branch: node.branch,
    terraformVersion: node.terraformVersion,
    iacType: node.iacType ?? "terraform",
    lastStatus: node.lastJobStatus,
    lastRun: node.lastJobDate,
    locked: node.locked,
    tags: node.tagIds,
    projectId: node.projectId,
    projectName: node.projectName,
  }));

  return {
    isError: false,
    responseCode: response.responseCode,
    data: {
      workspaces,
      pageInfo: page.pageInfo,
      statusCounts: {
        All: page.statusCounts.all,
        waitingApproval: page.statusCounts.waitingApproval,
        failed: page.statusCounts.failed,
        pending: page.statusCounts.pending,
        queue: page.statusCounts.queue,
        running: page.statusCounts.running,
        completed: page.statusCounts.completed,
        NeverExecuted: page.statusCounts.neverExecuted,
      },
    },
  };
}

async function assignWorkspaceToProject(orgId: string, workspaceId: string, projectId: string): Promise<void> {
  await axiosInstance.patch(
    `organization/${orgId}/workspace/${workspaceId}/relationships/project`,
    { data: { type: "project", id: projectId } },
    { headers: { "Content-Type": "application/vnd.api+json" } }
  );
}

async function removeWorkspaceFromProject(orgId: string, workspaceId: string): Promise<void> {
  await axiosInstance.patch(
    `organization/${orgId}/workspace/${workspaceId}/relationships/project`,
    { data: null },
    { headers: { "Content-Type": "application/vnd.api+json" } }
  );
}

const methods = {
  listWorkspaces,
  listWorkspacePage,
  assignWorkspaceToProject,
  removeWorkspaceFromProject,
};

export default methods;
