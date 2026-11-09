import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import containsFilter from "@/modules/workspaces/utils/containsFilter";
import quoteRsql from "@/modules/workspaces/utils/quoteRsql";
import { ProjectModel, ProjectSummaryModel } from "@/domain/types";

export type ProjectAccessModel = {
  id: string;
  name: string;
  role: string;
  manageWorkspace: boolean;
  manageState: boolean;
  manageJob: boolean;
  planJob: boolean;
  approveJob: boolean;
  destroyJob: boolean;
};

// The individual permissions a team gets on every workspace of the project when its role is "custom".
export type ProjectAccessPermissions = {
  manageWorkspace: boolean;
  manageState: boolean;
  planJob: boolean;
  approveJob: boolean;
  destroyJob: boolean;
};

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

// The databases disagree on where a null sorts, so a project that never ran would lead the list on one and trail it on
// another. Sorting by hasRun first puts those projects last, whichever way the last run is ordered.
export function withNeverRunLast(sort: string): string {
  return sort
    .split(",")
    .flatMap((term) => (term.slice(1) === "lastRunAt" ? ["-hasRun", term] : [term]))
    .join(",");
}

// Server-side paged and sorted list. Always sends "after" because Elide rejects "first" without it.
async function listProjectSummaries(
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
                  id name description workspaceCount completedCount failedCount runningCount queuedCount
                  waitingApprovalCount neverRunCount nonCompliantCount compliantCount exemptedCount lockedCount attentionCount lastRunAt queuedJobCount queuedSince teamCount
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
      sort: withNeverRunLast(query.sort),
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

async function getProjectSummary(
  organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectSummaryModel | null>> {
  const result = await listProjectSummaries(organizationId, {
    filter: `id==${quoteRsql(projectId)}`,
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
async function getProjectIndexCounts(
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
async function getUnassignedSummary(organizationId: string): Promise<ApiResponse<ProjectSummaryModel>> {
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

async function getCurrentGroups(): Promise<string[]> {
  const url = `${new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin}/access-token/v1/teams/current-teams`;
  const response = await axiosInstance.get(url);
  return response.data?.groups ?? [];
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
  data: { name: string; description?: string; landingTab?: string | null }
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
            project(ids: ["${projectId}"]) {
              edges {
                node {
                  id
                  name
                  description
                  landingTab
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
    },
  };
}

async function deleteProject(organizationId: string, projectId: string): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/project/${projectId}`);
}

async function listProjectAccess(
  _organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectAccessModel[]>> {
  const body = {
    query: `{
      project(ids: ["${projectId}"]) {
        edges {
          node {
            projectAccess {
              edges {
                node {
                  id
                  name
                  role
                  manageWorkspace
                  manageState
                  manageJob
                  planJob
                  approveJob
                  destroyJob
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

  const edges = tempData.data?.project?.edges?.[0]?.node?.projectAccess?.edges ?? [];

  return {
    isError: false,
    responseCode: tempData.responseCode,
    data: edges.map((edge: any) => ({
      id: edge.node.id,
      name: edge.node.name,
      role: edge.node.role ?? "custom",
      manageWorkspace: edge.node.manageWorkspace ?? false,
      manageState: edge.node.manageState ?? false,
      manageJob: edge.node.manageJob ?? false,
      planJob: edge.node.planJob ?? false,
      approveJob: edge.node.approveJob ?? false,
      destroyJob: edge.node.destroyJob ?? true,
    })),
  };
}

async function addProjectAccess(
  organizationId: string,
  projectId: string,
  teamName: string,
  role: string,
  permissions?: ProjectAccessPermissions
): Promise<void> {
  const body = {
    data: {
      type: "project_access",
      attributes: {
        name: teamName,
        role,
        ...(permissions ?? {}),
      },
    },
  };

  await axiosInstance.post(`organization/${organizationId}/project/${projectId}/projectAccess`, body, {
    headers: { "Content-Type": "application/vnd.api+json" },
  });
}

async function removeProjectAccess(organizationId: string, projectId: string, accessId: string): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/project/${projectId}/projectAccess/${accessId}`);
}

async function updateProjectAccess(
  organizationId: string,
  projectId: string,
  accessId: string,
  role: string,
  permissions?: ProjectAccessPermissions
): Promise<void> {
  const body = {
    data: {
      id: accessId,
      type: "project_access",
      attributes: { role, ...(permissions ?? {}) },
    },
  };
  await axiosInstance.patch(`organization/${organizationId}/project/${projectId}/projectAccess/${accessId}`, body, {
    headers: { "Content-Type": "application/vnd.api+json" },
  });
}

export type ProjectCollection = {
  id: string;
  name: string;
  description?: string;
  priority: number;
  variableCount: number;
  referenceId?: string;
};

// Every collection in the organization, marked with the reference that attaches it to this project (if any).
async function listCollectionsForProject(
  organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectCollection[]>> {
  const body = {
    query: `query ($orgId: String!, $filter: String) {
      organization(ids: [$orgId]) {
        edges { node { collection {
          edges { node {
            id name description priority
            item(first: "1", after: "0") { pageInfo { totalRecords } }
            reference(filter: $filter) { edges { node { id } } }
          } }
        } } }
      }
    }`,
    variables: { orgId: organizationId, filter: `scope=='PROJECT';project.id==${quoteRsql(projectId)}` },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: [] };
  }
  const edges = result.data?.organization?.edges?.[0]?.node?.collection?.edges ?? [];
  return {
    isError: false,
    responseCode: result.responseCode,
    data: edges.map((edge: any) => ({
      id: edge.node.id,
      name: edge.node.name,
      description: edge.node.description ?? undefined,
      priority: edge.node.priority ?? 0,
      variableCount: edge.node.item?.pageInfo?.totalRecords ?? 0,
      referenceId: edge.node.reference?.edges?.[0]?.node?.id,
    })),
  };
}

async function attachCollectionToProject(
  organizationId: string,
  projectId: string,
  collectionId: string
): Promise<void> {
  await axiosInstance.post(
    `organization/${organizationId}/collection/${collectionId}/reference`,
    {
      data: {
        type: "reference",
        attributes: { description: `Reference to project ${projectId}`, scope: "PROJECT" },
        relationships: { project: { data: { type: "project", id: projectId } } },
      },
    },
    { headers: { "Content-Type": "application/vnd.api+json" } }
  );
}

async function detachCollectionFromProject(
  organizationId: string,
  collectionId: string,
  referenceId: string
): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/collection/${collectionId}/reference/${referenceId}`);
}

export type ProjectDefaultTag = { id: string; tagId: string; value?: string };
export type OrganizationTag = { id: string; name: string };

async function listProjectDefaultTags(organizationId: string, projectId: string): Promise<ProjectDefaultTag[]> {
  const response = await axiosInstance.get(`organization/${organizationId}/project/${projectId}/projectTag`);
  return (response.data?.data ?? []).map((item: any) => ({
    id: item.id,
    tagId: item.attributes?.tagId,
    value: item.attributes?.value ?? undefined,
  }));
}

async function listOrganizationTags(organizationId: string): Promise<OrganizationTag[]> {
  const response = await axiosInstance.get(`organization/${organizationId}/tag`);
  return (response.data?.data ?? []).map((item: any) => ({ id: item.id, name: item.attributes?.name }));
}

async function addProjectDefaultTag(
  organizationId: string,
  projectId: string,
  tagId: string,
  value?: string
): Promise<void> {
  await axiosInstance.post(
    `organization/${organizationId}/project/${projectId}/projectTag`,
    { data: { type: "project_tag", attributes: { tagId, value: value || null } } },
    { headers: { "Content-Type": "application/vnd.api+json" } }
  );
}

async function removeProjectDefaultTag(organizationId: string, projectId: string, id: string): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/project/${projectId}/projectTag/${id}`);
}

export type ProjectPolicySet = {
  id: string;
  name: string;
  description?: string;
  enforcementLevel: string;
  global: boolean;
  attachedToProject: boolean;
};

// Policy sets that apply to the project: global ones (every workspace) and ones attached to this project.
// Tag and workspace attachments are not listed here because they do not follow the project.
async function listProjectPolicySets(
  organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectPolicySet[]>> {
  const body = {
    query: `query ($orgId: String!) {
      organization(ids: [$orgId]) {
        edges { node { policySet {
          edges { node {
            id name description enforcementLevel global
            attachments { edges { node { project { edges { node { id } } } } } }
          } }
        } } }
      }
    }`,
    variables: { orgId: organizationId },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  if (result.isError) {
    return { isError: true, responseCode: result.responseCode, error: result.error, data: [] };
  }
  const edges = result.data?.organization?.edges?.[0]?.node?.policySet?.edges ?? [];
  const sets: ProjectPolicySet[] = edges.map((edge: any) => ({
    id: edge.node.id,
    name: edge.node.name,
    description: edge.node.description ?? undefined,
    enforcementLevel: edge.node.enforcementLevel,
    global: !!edge.node.global,
    attachedToProject: (edge.node.attachments?.edges ?? []).some((attachment: any) =>
      (attachment.node?.project?.edges ?? []).some((project: any) => project.node?.id === projectId)
    ),
  }));
  return {
    isError: false,
    responseCode: result.responseCode,
    data: sets.filter((set) => set.global || set.attachedToProject),
  };
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
      filter: `project.id==${quoteRsql(projectId)}${extraFilter ? `;${extraFilter}` : ""}`,
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
function listAttentionWorkspaces(organizationId: string, projectId: string, limit = 5) {
  return listProjectWorkspaceBriefs(
    organizationId,
    projectId,
    "(lastJobStatus=in=('failed','waitingApproval'),policyComplianceStatus=='NON_COMPLIANT')",
    limit
  );
}

// Workspaces that have run, most recent first.
function listRecentWorkspaces(organizationId: string, projectId: string, limit = 5) {
  return listProjectWorkspaceBriefs(organizationId, projectId, "lastJobStatus!='NeverExecuted'", limit);
}

export type ApplyDefaultTagsResult = { workspaces: number; workspacesChanged: number; tagsAdded: number };

// dryRun only counts what would change, so the UI can show the impact before anything is written.
async function applyProjectDefaultTags(
  organizationId: string,
  projectId: string,
  dryRun: boolean
): Promise<ApplyDefaultTagsResult> {
  const url = `${
    new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
  }/access-token/v1/projects/organization/${organizationId}/project/${projectId}/default-tags/apply`;
  const response = await axiosInstance.post(url, null, { params: { dryRun } });
  return response.data;
}

const methods = {
  listProjects,
  listProjectSummaries,
  getProjectIndexCounts,
  getUnassignedSummary,
  getCurrentGroups,
  getProjectSummary,
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
