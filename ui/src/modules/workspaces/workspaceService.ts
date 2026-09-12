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
                      policyComplianceStatus
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
      policyComplianceStatus: element.node.policyComplianceStatus,
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
  // Server pagination uses the stored status order, without prioritizing active runs.
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

type WorkspaceFilterOverrides = Pick<WorkspacePageRequest, "status" | "policyStatus">;

function workspaceFilter(request: WorkspacePageRequest, overrides: WorkspaceFilterOverrides = {}): string | undefined {
  const status = overrides.status ?? request.status;
  const policyStatus = overrides.policyStatus ?? request.policyStatus;
  const search = request.search?.trim();
  return combineFilters(
    search ? `(name=ini=${quoteRsql(`*${search}*`)},description=ini=${quoteRsql(`*${search}*`)})` : undefined,
    request.tagIds?.length ? `workspaceTag.tagId=in=(${request.tagIds.map(quoteRsql).join(",")})` : undefined,
    request.projectId === "__unassigned__"
      ? "project.id=isnull=true"
      : request.projectId
        ? `project.id==${quoteRsql(request.projectId)}`
        : undefined,
    status && status !== "All"
      ? status === "NeverExecuted"
        ? "(lastJobStatus=isnull=true,lastJobStatus==NeverExecuted)"
        : `lastJobStatus==${quoteRsql(status)}`
      : undefined,
    policyStatus && policyStatus !== "All"
      ? policyStatus === "UNKNOWN"
        ? "(policyComplianceStatus=isnull=true,policyComplianceStatus==UNKNOWN)"
        : `policyComplianceStatus==${quoteRsql(policyStatus)}`
      : undefined
  );
}

const STATUS_COUNT_KEYS = ["waitingApproval", "failed", "pending", "queue", "running", "completed", "NeverExecuted"];
const POLICY_COUNT_KEYS = ["COMPLIANT", "NON_COMPLIANT", "EXEMPTED", "UNKNOWN"];

async function listWorkspacePage(
  request: WorkspacePageRequest,
  includeStatusCounts = true
): Promise<ApiResponse<WorkspacePageResponse>> {
  const filter = workspaceFilter(request);
  // Facet counts: each dimension is counted with every other filter applied, but its own filter varied.
  const countFilters: Record<string, string | undefined> = includeStatusCounts
    ? {
        statusAll: workspaceFilter(request, { status: "All" }),
        ...Object.fromEntries(
          STATUS_COUNT_KEYS.map((key) => [`status_${key}`, workspaceFilter(request, { status: key })])
        ),
        policyAll: workspaceFilter(request, { policyStatus: "All" }),
        ...Object.fromEntries(
          POLICY_COUNT_KEYS.map((key) => [`policy_${key}`, workspaceFilter(request, { policyStatus: key })])
        ),
      }
    : {};
  const countAliases = Object.keys(countFilters);
  const body = {
    query: `query WorkspacePage(
      $organizationIds: [String]
      $first: StringOrInt
      $after: StringOrInt
      ${filter ? "$filter: String" : ""}
      $sort: String
      ${countAliases
        .filter((alias) => countFilters[alias])
        .map((alias) => `$${alias}: String`)
        .join("\n      ")}
    ) {
      organization(ids: $organizationIds) {
        edges {
          node {
            name
            workspace(first: $first, after: $after, ${filter ? "filter: $filter," : ""} sort: $sort) {
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
                  policyComplianceStatus
                  workspaceTag { edges { node { tagId } } }
                  project { edges { node { id name } } }
                }
              }
              pageInfo { endCursor hasNextPage totalRecords }
            }
            ${countAliases
              .map(
                (alias) =>
                  `${alias}: workspace(first: "1"${countFilters[alias] ? `, filter: $${alias}` : ""}) { pageInfo { totalRecords } }`
              )
              .join("\n            ")}
          }
        }
      }
    }`,
    variables: {
      organizationIds: [request.organizationId],
      first: String(request.first),
      after: String(request.after),
      filter,
      sort: workspaceSortMap[request.sort],
      ...countFilters,
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
        policyCounts: {},
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
    policyComplianceStatus: node.policyComplianceStatus,
    tags: node.workspaceTag?.edges?.map(({ node: tag }: any) => tag.tagId),
    projectId: node.project?.edges?.[0]?.node?.id,
    projectName: node.project?.edges?.[0]?.node?.name,
  }));
  const count = (alias: string) => organization?.[alias]?.pageInfo?.totalRecords ?? 0;

  return {
    isError: false,
    responseCode: response.responseCode,
    data: {
      organizationName: organization?.name ?? "",
      workspaces,
      pageInfo: page.pageInfo,
      statusCounts: {
        All: count("statusAll"),
        ...Object.fromEntries(STATUS_COUNT_KEYS.map((key) => [key, count(`status_${key}`)])),
      },
      policyCounts: {
        All: count("policyAll"),
        ...Object.fromEntries(POLICY_COUNT_KEYS.map((key) => [key, count(`policy_${key}`)])),
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
