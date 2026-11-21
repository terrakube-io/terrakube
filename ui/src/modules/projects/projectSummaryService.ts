import { Delivery, DeliveryJob, summarizeDelivery } from "./deliveryStats";
import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import containsFilter from "@/modules/workspaces/utils/containsFilter";
import { ProjectSummaryModel } from "@/domain/types";

export type ProjectSummaryQuery = {
  filter?: string;
  search?: string;
  attentionOnly?: boolean;
  // One of the filter chips, and the caller's groups for "My projects".
  chip?: "all" | "attention" | "mine" | "empty" | "stale";
  groups?: string[];
  sort: string;
  page: number;
  pageSize: number;
};

export type ProjectSummaryPage = {
  items: ProjectSummaryModel[];
  totalRecords: number;
};

export const STALE_DAYS = 90;

function quoteList(values: string[]): string {
  return values.map((value) => `'${value.replace(/['"\\]/g, "")}'`).join(",");
}

// The filter behind each chip. "My projects" with no groups matches nothing rather than everything.
export function chipFilter(chip: ProjectSummaryQuery["chip"], groups: string[] = []): string | undefined {
  switch (chip) {
    case "attention":
      return "attentionCount=gt=0";
    case "mine":
      return groups.length ? `projectAccess.name=in=(${quoteList(groups)})` : "workspaceCount<0";
    case "empty":
      return "workspaceCount==0";
    case "stale":
      return `lastRunAt=lt=${new Date(Date.now() - STALE_DAYS * 24 * 60 * 60 * 1000).toISOString()}`;
    default:
      return undefined;
  }
}

export function buildProjectSummaryFilter(
  query: Pick<ProjectSummaryQuery, "search" | "attentionOnly" | "chip" | "groups">
): string {
  const parts: string[] = [];
  const search = query.search?.trim();
  if (search) {
    const value = containsFilter(search);
    if (value) parts.push(`(name=ini=${value},description=ini=${value})`);
  }
  if (query.attentionOnly) parts.push("attentionCount=gt=0");
  const chip = chipFilter(query.chip, query.groups);
  if (chip) parts.push(chip);
  return parts.join(";");
}

// Server-side paged and sorted list. Always sends "after" because Elide rejects "first" without it.
export async function listProjectSummaries(
  organizationId: string,
  query: ProjectSummaryQuery
): Promise<ApiResponse<ProjectSummaryPage>> {
  // Elide rejects a null filter variable, so the argument is left out when there is nothing to filter.
  const filter = query.filter ?? buildProjectSummaryFilter(query);
  const body = {
    query: `query ProjectSummaries($orgId: String!, $first: StringOrInt, $after: StringOrInt, $sort: String${filter ? ", $filter: String" : ""}) {
      organization(ids: [$orgId]) {
        edges {
          node {
            projectSummary(first: $first, after: $after, sort: $sort${filter ? ", filter: $filter" : ""}) {
              edges {
                node {
                  id name description ownerTeam workspaceCount completedCount failedCount runningCount queuedCount
                  waitingApprovalCount neverRunCount nonCompliantCount hardViolationCount softViolationCount compliantCount exemptedCount lockedCount attentionCount lastRunAt queuedJobCount queuedSince teamCount
                }
              }
              pageInfo { totalRecords }
            }
          }
        }
      }
    }`,
    variables: {
      orgId: organizationId,
      first: String(query.pageSize),
      after: String((query.page - 1) * query.pageSize),
      sort: query.sort,
      ...(filter ? { filter } : {}),
    },
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
      data: { items: [], totalRecords: 0 },
    };
  }

  const connection = tempData.data?.organization?.edges?.[0]?.node?.projectSummary;
  const items: ProjectSummaryModel[] = (connection?.edges ?? []).map((edge: any) => ({
    ...edge.node,
    description: edge.node.description ?? undefined,
    lastRunAt: edge.node.lastRunAt ?? undefined,
    queuedSince: edge.node.queuedSince ?? undefined,
  }));

  return {
    isError: false,
    responseCode: tempData.responseCode,
    data: { items, totalRecords: connection?.pageInfo?.totalRecords ?? items.length },
  };
}

export async function getProjectSummary(
  organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectSummaryModel | null>> {
  const result = await listProjectSummaries(organizationId, {
    filter: `id=='${projectId}'`,
    sort: "+name",
    page: 1,
    pageSize: 1,
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: null };
  }
  return { isError: false, responseCode: result.responseCode, data: result.data?.items[0] ?? null };
}

export type ProjectIndexCounts = { all: number; attention: number; mine: number; empty: number; stale: number };

// The numbers on the filter chips, in one request: each is the total of a differently filtered, one-row page.
export async function getProjectIndexCounts(
  organizationId: string,
  groups: string[]
): Promise<ApiResponse<ProjectIndexCounts>> {
  const count = (alias: string, filter?: string) =>
    `${alias}: projectSummary(first: "1", after: "0"${filter ? `, filter: "${filter}"` : ""}) { pageInfo { totalRecords } }`;
  const mine = groups.length ? count("mine", chipFilter("mine", groups)) : "";
  const body = {
    query: `query ($orgId: String!) {
      organization(ids: [$orgId]) { edges { node {
        ${count("all")}
        ${count("attention", chipFilter("attention"))}
        ${mine}
        ${count("empty", chipFilter("empty"))}
        ${count("stale", chipFilter("stale"))}
      } } }
    }`,
    variables: { orgId: organizationId },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  const empty: ProjectIndexCounts = { all: 0, attention: 0, mine: 0, empty: 0, stale: 0 };
  if (result.isError) return { isError: true, responseCode: result.responseCode, error: result.error, data: empty };
  const node = result.data?.organization?.edges?.[0]?.node ?? {};
  const total = (alias: string) => node[alias]?.pageInfo?.totalRecords ?? 0;
  return {
    isError: false,
    responseCode: result.responseCode,
    data: {
      all: total("all"),
      attention: total("attention"),
      mine: total("mine"),
      empty: total("empty"),
      stale: total("stale"),
    },
  };
}

// The workspaces that are in no project, rolled up like a project so the index can show them as one entry.
export async function getUnassignedSummary(organizationId: string): Promise<ApiResponse<ProjectSummaryModel>> {
  const base = "project=isnull=true";
  const count = (alias: string, extra?: string) =>
    `${alias}: workspace(first: "1", after: "0", filter: "${extra ? `${base};${extra}` : base}") { pageInfo { totalRecords } }`;
  const body = {
    query: `query ($orgId: String!) {
      organization(ids: [$orgId]) { edges { node {
        ${count("total")}
        ${count("completed", "lastJobStatus=in=('completed','noChanges')")}
        ${count("failed", "lastJobStatus=='failed'")}
        ${count("running", "lastJobStatus=='running'")}
        ${count("queued", "lastJobStatus=in=('pending','approved','queue')")}
        ${count("waiting", "lastJobStatus=='waitingApproval'")}
        ${count("never", "lastJobStatus=='NeverExecuted'")}
        ${count("nonCompliant", "policyComplianceStatus=='NON_COMPLIANT'")}
        ${count("compliant", "policyComplianceStatus=='COMPLIANT'")}
        ${count("locked", "locked==true")}
        ${count("attention", "(lastJobStatus=in=('failed','waitingApproval'),policyComplianceStatus=='NON_COMPLIANT')")}
        latest: workspace(first: "1", after: "0", sort: "-lastJobDate", filter: "${base}") { edges { node { lastJobDate } } }
      } } }
    }`,
    variables: { orgId: organizationId },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  const blank: ProjectSummaryModel = {
    id: "unassigned",
    name: "Unassigned",
    description: "Workspaces with no project",
    workspaceCount: 0,
    completedCount: 0,
    failedCount: 0,
    runningCount: 0,
    queuedCount: 0,
    waitingApprovalCount: 0,
    neverRunCount: 0,
    nonCompliantCount: 0,
    hardViolationCount: 0,
    softViolationCount: 0,
    compliantCount: 0,
    exemptedCount: 0,
    queuedJobCount: 0,
    lockedCount: 0,
    attentionCount: 0,
    teamCount: 0,
  };
  if (result.isError) return { isError: true, responseCode: result.responseCode, error: result.error, data: blank };
  const node = result.data?.organization?.edges?.[0]?.node ?? {};
  const total = (alias: string) => node[alias]?.pageInfo?.totalRecords ?? 0;
  return {
    isError: false,
    responseCode: result.responseCode,
    data: {
      ...blank,
      workspaceCount: total("total"),
      completedCount: total("completed"),
      failedCount: total("failed"),
      runningCount: total("running"),
      queuedCount: total("queued"),
      waitingApprovalCount: total("waiting"),
      neverRunCount: total("never"),
      nonCompliantCount: total("nonCompliant"),
      compliantCount: total("compliant"),
      lockedCount: total("locked"),
      attentionCount: total("attention"),
      lastRunAt: node.latest?.edges?.[0]?.node?.lastJobDate ?? undefined,
    },
  };
}

// The runs a project's workspaces started in the last days, for the delivery numbers. Job's read permission is
// evaluated in memory, so Elide rejects first/after ("Cannot paginate job"): the window and its default cap bound it.
export async function getDelivery(
  organizationId: string,
  projectId: string,
  days = 30
): Promise<ApiResponse<Delivery>> {
  const since = new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString();
  const body = {
    query: `query ($orgId: String!, $filter: String) {
      organization(ids: [$orgId]) { edges { node {
        job(sort: "-createdDate", filter: $filter) {
          edges { node { status createdDate updatedDate approvedAt } }
        }
      } } }
    }`,
    variables: {
      orgId: organizationId,
      filter: `workspace.project.id=='${projectId}';createdDate=ge=${since}`,
    },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: undefined as never };
  }
  const connection = result.data?.organization?.edges?.[0]?.node?.job;
  const jobs: DeliveryJob[] = (connection?.edges ?? []).map((edge: any) => edge.node);
  return {
    isError: false,
    responseCode: result.responseCode,
    data: summarizeDelivery(jobs, jobs.length),
  };
}

export async function getCurrentGroups(): Promise<string[]> {
  const url = `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/access-token/v1/teams/current-teams`;
  const response = await axiosInstance.get(url);
  return response.data?.groups ?? [];
}

export type ProjectWorkspaceBrief = {
  id: string;
  name: string;
  lastJobStatus: string;
  lastJobDate?: string;
  policyComplianceStatus?: string;
};

export type AttentionWorkspace = ProjectWorkspaceBrief;

// The first few workspaces of a project matching an extra filter, newest run first.
async function listProjectWorkspaceBriefs(
  organizationId: string,
  projectId: string,
  extraFilter: string | null,
  limit = 5
): Promise<ApiResponse<ProjectWorkspaceBrief[]>> {
  const body = {
    query: `query ($orgId: String!, $first: StringOrInt, $filter: String) {
      organization(ids: [$orgId]) {
        edges { node { workspace(first: $first, after: "0", sort: "-lastJobDate", filter: $filter) {
          edges { node { id name lastJobStatus lastJobDate policyComplianceStatus } }
        } } }
      }
    }`,
    variables: {
      orgId: organizationId,
      first: String(limit),
      filter: `project.id=='${projectId}'${extraFilter ? `;${extraFilter}` : ""}`,
    },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: [] };
  }
  const edges = result.data?.organization?.edges?.[0]?.node?.workspace?.edges ?? [];
  return {
    isError: false,
    responseCode: result.responseCode,
    data: edges.map((edge: any) => ({
      id: edge.node.id,
      name: edge.node.name,
      lastJobStatus: edge.node.lastJobStatus,
      lastJobDate: edge.node.lastJobDate ?? undefined,
      policyComplianceStatus: edge.node.policyComplianceStatus ?? undefined,
    })),
  };
}

// Failed, waiting for approval or breaking policy.
export function listAttentionWorkspaces(organizationId: string, projectId: string, limit = 5) {
  return listProjectWorkspaceBriefs(
    organizationId,
    projectId,
    "(lastJobStatus=in=('failed','waitingApproval'),policyComplianceStatus=='NON_COMPLIANT')",
    limit
  );
}

// Workspaces that have run, most recent first.
export function listRecentWorkspaces(organizationId: string, projectId: string, limit = 5) {
  return listProjectWorkspaceBriefs(organizationId, projectId, "lastJobStatus!='NeverExecuted'", limit);
}
