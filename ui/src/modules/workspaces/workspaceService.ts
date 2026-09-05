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
  name_asc: "name,id",
  name_desc: "-name,-id",
  lastRun_asc: "lastJobDate,id",
  lastRun_desc: "-lastJobDate,-id",
  status: "lastJobStatus,id",
  source_asc: "source,id",
  source_desc: "-source,-id",
  terraformVersion_asc: "terraformVersion,id",
  terraformVersion_desc: "-terraformVersion,-id",
};

function quoteRsql(value: string): string {
  return `"${value.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

function combineFilters(...filters: (string | undefined)[]): string | undefined {
  return filters.filter(Boolean).join(";") || undefined;
}

function workspaceFilter(request: WorkspacePageRequest, status = request.status): string | undefined {
  const search = request.search?.trim();
  const base = combineFilters(
    search ? `(name=ini=${quoteRsql(`*${search}*`)},description=ini=${quoteRsql(`*${search}*`)})` : undefined,
    request.tagIds?.length ? `workspaceTag.tagId=in=(${request.tagIds.map(quoteRsql).join(",")})` : undefined,
    request.projectId === "__unassigned__"
      ? "project.id=isnull=true"
      : request.projectId
        ? `project.id==${quoteRsql(request.projectId)}`
        : undefined
  );

  return combineFilters(
    base,
    status && status !== "All"
      ? status === "NeverExecuted"
        ? "(lastJobStatus=isnull=true,lastJobStatus==NeverExecuted)"
        : `lastJobStatus==${quoteRsql(status)}`
      : undefined
  );
}

async function listWorkspacePage(request: WorkspacePageRequest): Promise<ApiResponse<WorkspacePageResponse>> {
  const baseFilter = workspaceFilter(request, "All");
  const body = {
    query: `query WorkspacePage(
      $organizationIds: [String]
      $first: StringOrInt
      $after: StringOrInt
      $filter: String
      $sort: String
      $allFilter: String
      $waitingApprovalFilter: String
      $failedFilter: String
      $pendingFilter: String
      $queueFilter: String
      $runningFilter: String
      $completedFilter: String
      $neverExecutedFilter: String
    ) {
      organization(ids: $organizationIds) {
        edges {
          node {
            name
            workspace(first: $first, after: $after, filter: $filter, sort: $sort) {
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
                  workspaceTag { edges { node { tagId } } }
                  project { edges { node { id name } } }
                }
              }
              pageInfo { endCursor hasNextPage totalRecords }
            }
            all: workspace(first: "1", filter: $allFilter) { pageInfo { totalRecords } }
            waitingApproval: workspace(first: "1", filter: $waitingApprovalFilter) { pageInfo { totalRecords } }
            failed: workspace(first: "1", filter: $failedFilter) { pageInfo { totalRecords } }
            pending: workspace(first: "1", filter: $pendingFilter) { pageInfo { totalRecords } }
            queue: workspace(first: "1", filter: $queueFilter) { pageInfo { totalRecords } }
            running: workspace(first: "1", filter: $runningFilter) { pageInfo { totalRecords } }
            completed: workspace(first: "1", filter: $completedFilter) { pageInfo { totalRecords } }
            neverExecuted: workspace(first: "1", filter: $neverExecutedFilter) { pageInfo { totalRecords } }
          }
        }
      }
    }`,
    variables: {
      organizationIds: [request.organizationId],
      first: String(request.first),
      after: String(request.after),
      filter: workspaceFilter(request),
      sort: workspaceSortMap[request.sort],
      allFilter: baseFilter,
      waitingApprovalFilter: workspaceFilter(request, "waitingApproval"),
      failedFilter: workspaceFilter(request, "failed"),
      pendingFilter: workspaceFilter(request, "pending"),
      queueFilter: workspaceFilter(request, "queue"),
      runningFilter: workspaceFilter(request, "running"),
      completedFilter: workspaceFilter(request, "completed"),
      neverExecutedFilter: workspaceFilter(request, "NeverExecuted"),
    },
  };

  const response = await apiPost<typeof body, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });

  if (response.isError) {
    return {
      isError: true,
      responseCode: response.responseCode,
      error: response.error,
      originResponseCode: response.originResponseCode,
      data: {
        organizationName: "",
        workspaces: [],
        pageInfo: { hasNextPage: false, totalRecords: 0 },
        statusCounts: {},
      },
    };
  }

  const organization = response.data?.organization?.edges?.[0]?.node;
  const page = organization?.workspace ?? { edges: [], pageInfo: { hasNextPage: false, totalRecords: 0 } };
  const workspaces: WorkspaceListItem[] = page.edges.map(({ node }: any) => ({
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
    tags: node.workspaceTag?.edges?.map(({ node: tag }: any) => tag.tagId),
    projectId: node.project?.edges?.[0]?.node?.id,
    projectName: node.project?.edges?.[0]?.node?.name,
  }));

  return {
    isError: false,
    responseCode: response.responseCode,
    data: {
      organizationName: organization?.name ?? "",
      workspaces,
      pageInfo: page.pageInfo,
      statusCounts: {
        All: organization?.all?.pageInfo?.totalRecords ?? 0,
        waitingApproval: organization?.waitingApproval?.pageInfo?.totalRecords ?? 0,
        failed: organization?.failed?.pageInfo?.totalRecords ?? 0,
        pending: organization?.pending?.pageInfo?.totalRecords ?? 0,
        queue: organization?.queue?.pageInfo?.totalRecords ?? 0,
        running: organization?.running?.pageInfo?.totalRecords ?? 0,
        completed: organization?.completed?.pageInfo?.totalRecords ?? 0,
        NeverExecuted: organization?.neverExecuted?.pageInfo?.totalRecords ?? 0,
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
