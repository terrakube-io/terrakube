import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import containsFilter from "@/modules/workspaces/utils/containsFilter";
import quoteRsql from "@/modules/workspaces/utils/quoteRsql";
import { ProjectModel, ProjectSummaryModel } from "@/domain/types";
import { CollectionEntry, VariableEntry } from "./effectiveVariables";

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

// Each value is escaped, not stripped, so a team called O'Reilly Ops still matches its own name.
function quoteList(values: string[]): string {
  return values.map(quoteRsql).join(",");
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
  // The filters go in as variables, so nothing a team is called is ever part of the query text.
  const mine = groups.length ? chipFilter("mine", groups) : undefined;
  const body = {
    query: `query ($orgId: String!, $attention: String, $mine: String, $empty: String, $stale: String) {
      organization(ids: [$orgId]) { edges { node {
        all: projectSummary(first: "1", after: "0") { pageInfo { totalRecords } }
        attention: projectSummary(first: "1", after: "0", filter: $attention) { pageInfo { totalRecords } }
        ${mine ? 'mine: projectSummary(first: "1", after: "0", filter: $mine) { pageInfo { totalRecords } }' : ""}
        empty: projectSummary(first: "1", after: "0", filter: $empty) { pageInfo { totalRecords } }
        stale: projectSummary(first: "1", after: "0", filter: $stale) { pageInfo { totalRecords } }
      } } }
    }`,
    variables: {
      orgId: organizationId,
      attention: chipFilter("attention"),
      mine,
      empty: chipFilter("empty"),
      stale: chipFilter("stale"),
    },
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
        ${count("never", "(lastJobStatus=='NeverExecuted',lastJobStatus=isnull=true)")}
        ${count("nonCompliant", "policyComplianceStatus=='NON_COMPLIANT'")}
        ${count("compliant", "policyComplianceStatus=='COMPLIANT'")}
        ${count("locked", "locked==true")}
        ${count("attention", "(lastJobStatus=in=('failed','waitingApproval'),policyComplianceStatus=='NON_COMPLIANT')")}
        latest: workspace(first: "1", after: "0", sort: "-lastJobDate", filter: "${base};lastJobDate=isnull=false") { edges { node { lastJobDate } } }
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
  data: {
    name: string;
    description?: string;
    landingTab?: string | null;
    defaultTerraformVersion?: string | null;
    defaultExecutionMode?: string | null;
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
      organizationExecutionMode: organization?.executionMode ?? undefined,
    },
  };
}

async function deleteProject(organizationId: string, projectId: string): Promise<void> {
  await axiosInstance.delete(`organization/${organizationId}/project/${projectId}`);
}

// Read through the organization, because a caller who only has project access gets nothing from the root project
// collection. A caller who cannot manage the project's access sees the project but an empty list here.
async function listProjectAccess(
  organizationId: string,
  projectId: string
): Promise<ApiResponse<ProjectAccessModel[]>> {
  const body = {
    query: `{
      organization(ids: ["${organizationId}"]) {
        edges {
          node {
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

  const edges = tempData.data?.organization?.edges?.[0]?.node?.project?.edges?.[0]?.node?.projectAccess?.edges ?? [];

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

export type ProjectTeams = {
  teams: ProjectAccessModel[];
  /** True when the project has teams this caller is not allowed to see, so the list is incomplete. */
  restricted: boolean;
};

// The visible teams, and whether some are hidden: the summary counts every access row, the list only the readable ones.
async function listProjectTeams(organizationId: string, projectId: string): Promise<ApiResponse<ProjectTeams>> {
  const [access, summary] = await Promise.all([
    listProjectAccess(organizationId, projectId),
    getProjectSummary(organizationId, projectId),
  ]);
  if (access.isError) {
    return {
      isError: true,
      responseCode: access.responseCode,
      error: access.error,
      data: { teams: [], restricted: false },
    };
  }
  const teams = access.data ?? [];
  const total = summary.isError ? teams.length : (summary.data?.teamCount ?? teams.length);
  return {
    isError: false,
    responseCode: access.responseCode,
    data: { teams, restricted: teams.length < total },
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

export type DeletePreflight = {
  /** Null when the team list cannot be read by this caller. */
  teamCount: number | null;
  attachments: { id: string; policySetId: string }[];
  exemptions: { id: string; policySetId: string }[];
  collections: { id: string; name: string; referenceId: string }[];
  defaultTagCount: number;
  /** The reads that failed. While any did, an empty list means nothing is known, not that nothing is attached. */
  failed: ("policy sets" | "variable collections")[];
};

// Everything that points at the project besides its workspaces, so the dialog can say what the delete removes.
async function getDeletePreflight(organizationId: string, projectId: string): Promise<DeletePreflight> {
  const body = {
    query: `query ($orgId: String!) {
      organization(ids: [$orgId]) { edges { node { policySet { edges { node {
        id
        attachments { edges { node { id project { edges { node { id } } } } } }
        exemptions { edges { node { id project { edges { node { id } } } } } }
      } } } } } }
    }`,
    variables: { orgId: organizationId },
  };
  const [policies, access, collections, tags] = await Promise.all([
    apiPost<unknown, any>("/graphql/api/v1", body, { dataWrapped: true, contentType: "application/json" }),
    getProjectSummary(organizationId, projectId),
    listCollectionsForProject(organizationId, projectId),
    listProjectDefaultTags(organizationId, projectId).catch(() => []),
  ]);
  const attachments: DeletePreflight["attachments"] = [];
  const exemptions: DeletePreflight["exemptions"] = [];
  const sets = policies.isError ? [] : (policies.data?.organization?.edges?.[0]?.node?.policySet?.edges ?? []);
  const onProject = (node: any) => (node?.project?.edges ?? []).some((edge: any) => edge.node?.id === projectId);
  sets.forEach((set: any) => {
    (set.node.attachments?.edges ?? []).forEach((edge: any) => {
      if (onProject(edge.node)) attachments.push({ id: edge.node.id, policySetId: set.node.id });
    });
    (set.node.exemptions?.edges ?? []).forEach((edge: any) => {
      if (onProject(edge.node)) exemptions.push({ id: edge.node.id, policySetId: set.node.id });
    });
  });
  const failed: DeletePreflight["failed"] = [];
  if (policies.isError) failed.push("policy sets");
  if (collections.isError) failed.push("variable collections");
  return {
    failed,
    teamCount: access.isError ? null : (access.data?.teamCount ?? null),
    attachments,
    exemptions,
    collections: (collections.data ?? [])
      .filter((item) => item.referenceId)
      .map((item) => ({ id: item.id, name: item.name, referenceId: item.referenceId as string })),
    defaultTagCount: tags.length,
  };
}

async function deletePolicyAttachment(policySetId: string, id: string): Promise<void> {
  await axiosInstance.delete(`policy_set/${policySetId}/attachments/${id}`);
}

async function deletePolicyExemption(id: string): Promise<void> {
  await axiosInstance.delete(`policy_exemption/${id}`);
}

export type WorkspaceVariableData = {
  projectId?: string;
  variables: VariableEntry[];
  globals: VariableEntry[];
  collections: CollectionEntry[];
};

const toEntry = (node: any): VariableEntry => ({
  id: node.id,
  key: node.key,
  value: node.value ?? null,
  category: node.category,
  sensitive: !!node.sensitive,
  hcl: !!node.hcl,
});

// Reading the value of a sensitive variable is denied by the API, and a denied field fails the whole response. So
// values are only asked for on rows that are not sensitive, and sensitive rows are fetched without their value.
const PLAIN_FIELDS = "id key value category sensitive hcl";
const SENSITIVE_FIELDS = "id key category sensitive hcl";
const split = (name: string) =>
  `${name}Plain: ${name}(filter: "sensitive==false") { edges { node { ${PLAIN_FIELDS} } } }
   ${name}Hidden: ${name}(filter: "sensitive==true") { edges { node { ${SENSITIVE_FIELDS} } } }`;

// Everything needed to work out the variables of one workspace, read through the same API (and permissions) as the
// variable pages themselves.
async function getWorkspaceVariableData(
  organizationId: string,
  workspaceId: string
): Promise<ApiResponse<WorkspaceVariableData>> {
  const body = {
    query: `query ($orgId: String!, $workspaceId: String!) {
      organization(ids: [$orgId]) { edges { node {
        ${split("globalvar")}
        collection { edges { node {
          id name priority
          ${split("item")}
          reference { edges { node { scope workspace { edges { node { id } } } project { edges { node { id } } } } } }
        } } }
        workspace(ids: [$workspaceId]) { edges { node {
          project { edges { node { id } } }
          ${split("variable")}
        } } }
      } } }
    }`,
    variables: { orgId: organizationId, workspaceId },
  };
  const result = await apiPost<unknown, any>("/graphql/api/v1", body, {
    dataWrapped: true,
    contentType: "application/json",
  });
  if (result.isError) {
    return {
      isError: true,
      responseCode: result.responseCode,
      error: result.error,
      data: { variables: [], globals: [], collections: [] },
    };
  }
  const org = result.data?.organization?.edges?.[0]?.node ?? {};
  const workspace = org.workspace?.edges?.[0]?.node;
  const projectId: string | undefined = workspace?.project?.edges?.[0]?.node?.id;
  const nodes = (connection: any) => (connection?.edges ?? []).map((edge: any) => edge.node);
  const merged = (parent: any, name: string): VariableEntry[] =>
    [...nodes(parent?.[`${name}Plain`]), ...nodes(parent?.[`${name}Hidden`])].map(toEntry);

  const collections: CollectionEntry[] = [];
  nodes(org.collection).forEach((collection: any) => {
    // A collection can be attached more than once; the narrowest attachment that applies to this workspace wins.
    const scopes = nodes(collection.reference)
      .map((reference: any) => {
        const scope = reference.scope ?? "WORKSPACE";
        if (scope === "WORKSPACE")
          return nodes(reference.workspace).some((node: any) => node.id === workspaceId) ? scope : null;
        if (scope === "PROJECT")
          return projectId && nodes(reference.project).some((node: any) => node.id === projectId) ? scope : null;
        return scope;
      })
      .filter(Boolean) as CollectionEntry["scope"][];
    if (scopes.length === 0) return;
    const order = ["WORKSPACE", "PROJECT", "ORGANIZATION"];
    scopes.sort((a, b) => order.indexOf(a) - order.indexOf(b));
    collections.push({
      id: collection.id,
      name: collection.name,
      priority: collection.priority ?? 0,
      scope: scopes[0],
      items: merged(collection, "item"),
    });
  });

  return {
    isError: false,
    responseCode: result.responseCode,
    data: {
      projectId,
      variables: merged(workspace, "variable"),
      globals: merged(org, "globalvar"),
      collections,
    },
  };
}

const methods = {
  listProjects,
  listProjectSummaries,
  getProjectIndexCounts,
  getUnassignedSummary,
  getCurrentGroups,
  getProjectSummary,
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
