import { apiPost } from "@/modules/api/apiWrapper";
import { mapWithLimit } from "@/modules/utils/mapWithLimit";
import { wildcardTerm } from "@/modules/navigation/navigationService";
import quoteRsql from "@/modules/workspaces/utils/quoteRsql";
import { InboxCounts, InboxData, InboxItem, InboxKind, KIND_ORDER, emptyCounts, sortItems } from "./inboxModel";

const ROWS_PER_KIND = 10;
// One request each, so a person in many organizations does not start them all together.
const MAX_PARALLEL_ORGANIZATIONS = 5;

const WORKSPACE_FIELDS = "id name lastJobDate driftFirstSeen driftJobId project { edges { node { name ownerTeam } } }";

const BASE_FILTERS: Record<InboxKind, string> = {
  waiting: "lastJobStatus=='waitingApproval'",
  failed: "lastJobStatus=='failed'",
  // A drifted workspace that also failed or waits is already listed there, once is enough.
  drifted: "driftStatus=='drifted';lastJobStatus!='failed';lastJobStatus!='waitingApproval'",
  policy: "policyComplianceStatus=='NON_COMPLIANT';lastJobStatus!='failed';lastJobStatus!='waitingApproval'",
};

const SORTS: Record<InboxKind, string> = {
  // The same order as sortItems, with the name last so that a page boundary never reorders equal times.
  waiting: "+lastJobDate,+name",
  failed: "-lastJobDate,+name",
  drifted: "+driftFirstSeen,+name",
  policy: "-lastJobDate,+name",
};

// Every kind is a `workspace` connection of the organization with its own filter. The filters travel as variables,
// because the owner part holds names a person typed and quoting them inside the query text would mean escaping twice.
const FILTER_VARIABLES = KIND_ORDER.map((kind) => `$${kind}Filter: String`).join(", ");
const ROWS = `edges { node { ${WORKSPACE_FIELDS} } } pageInfo { totalRecords }`;
const COUNT = "pageInfo { totalRecords }";

const kindQuery = (declarations: string, selection: (kind: InboxKind) => string) =>
  `query ($orgId: String!, ${declarations}${FILTER_VARIABLES}) {
  organization(ids: [$orgId]) { edges { node { ${KIND_ORDER.map(selection).join(" ")} } } }
}`;

/** The first rows of every kind and their totals: one request for a summary. */
const SUMMARY_QUERY = kindQuery(
  "$first: StringOrInt, ",
  (kind) => `${kind}: workspace(first: $first, after: "0", sort: "${SORTS[kind]}", filter: $${kind}Filter) { ${ROWS} }`
);

/** The totals of every kind and no rows. */
const COUNTS_QUERY = kindQuery(
  "",
  (kind) => `${kind}: workspace(first: "1", after: "0", filter: $${kind}Filter) { ${COUNT} }`
);

/** One page of one kind. */
const PAGE_QUERY = `query ($orgId: String!, $first: StringOrInt, $after: StringOrInt, $sort: String, $filter: String) {
  organization(ids: [$orgId]) { edges { node {
    workspace(first: $first, after: $after, sort: $sort, filter: $filter) { ${ROWS} }
  } } }
}`;

/** Limits the inbox to the projects owned by these teams. Null means everything the person can see. */
export type InboxScope = { ownerTeams: string[] } | null;

const scoped = (filter: string, scope: InboxScope) =>
  scope ? `${filter};project.ownerTeam=in=(${scope.ownerTeams.map(quoteRsql).join(",")})` : filter;

/** A person in no team owns nothing, and an empty "in" list is not a filter the API accepts. */
const ownsNothing = (scope: InboxScope) => scope !== null && scope.ownerTeams.length === 0;

/** The filter for one kind, narrowed to the owned projects and to names containing the search text. */
function kindFilter(kind: InboxKind, scope: InboxScope, search?: string): string {
  const term = search ? wildcardTerm(search) : "";
  const filter = scoped(BASE_FILTERS[kind], scope);
  return term ? `${filter};name=ini=*${term}*` : filter;
}

const kindFilters = (scope: InboxScope, search?: string) =>
  Object.fromEntries(KIND_ORDER.map((kind) => [`${kind}Filter`, kindFilter(kind, scope, search)]));

/** Runs a query against one organization and returns its node. */
async function queryOrganization(query: string, variables: Record<string, string>): Promise<any> {
  const result = await apiPost<unknown, any>(
    "/graphql/api/v1",
    { query, variables },
    { dataWrapped: true, contentType: "application/json" }
  );
  if (result.isError) throw new Error(result.error?.message ?? "The request failed");
  return result.data?.organization?.edges?.[0]?.node ?? {};
}

type Org = { id: string; name: string };

function toItem(kind: InboxKind, org: Org, node: any): InboxItem {
  return {
    key: `${kind}:${node.id}`,
    kind,
    orgId: org.id,
    orgName: org.name,
    workspaceId: node.id,
    name: node.name,
    projectName: node.project?.edges?.[0]?.node?.name ?? undefined,
    ownerTeam: node.project?.edges?.[0]?.node?.ownerTeam ?? undefined,
    at: (kind === "drifted" ? node.driftFirstSeen : node.lastJobDate) ?? undefined,
    runId: kind === "drifted" && node.driftJobId != null ? String(node.driftJobId) : undefined,
  };
}

async function loadOrganization(org: Org, scope: InboxScope): Promise<{ items: InboxItem[]; counts: InboxCounts }> {
  const node = await queryOrganization(SUMMARY_QUERY, {
    orgId: org.id,
    first: String(ROWS_PER_KIND),
    ...kindFilters(scope),
  });
  const items: InboxItem[] = [];
  const counts = emptyCounts();
  KIND_ORDER.forEach((kind) => {
    counts[kind] = node[kind]?.pageInfo?.totalRecords ?? 0;
    (node[kind]?.edges ?? []).forEach((edge: any) => items.push(toItem(kind, org, edge.node)));
  });
  return { items, counts };
}

/** What needs a person across every organization given. One request each, in parallel, so one slow one is not fatal. */
export async function loadInbox(organizations: Org[], scope: InboxScope = null): Promise<InboxData> {
  if (ownsNothing(scope)) {
    return { items: [], counts: emptyCounts(), failedOrganizations: [] };
  }
  const settled = await mapWithLimit(organizations, MAX_PARALLEL_ORGANIZATIONS, (org) => loadOrganization(org, scope));
  const items: InboxItem[] = [];
  const counts = emptyCounts();
  const failedOrganizations: string[] = [];
  settled.forEach((outcome, index) => {
    if (outcome.status === "rejected") {
      failedOrganizations.push(organizations[index].name);
      return;
    }
    items.push(...outcome.value.items);
    (Object.keys(counts) as InboxKind[]).forEach((kind) => (counts[kind] += outcome.value.counts[kind]));
  });
  const sorted = KIND_ORDER.flatMap((kind) =>
    sortItems(
      kind,
      items.filter((item) => item.kind === kind)
    )
  );
  return { items: sorted, counts, failedOrganizations };
}

// ---- One organization at a time, a page at a time ----------------------------------------------------------------
// loadInbox is a summary: a few rows of each kind. A person with hundreds of items needs the rest, one kind of one
// organization a page at a time, with the total.

export type InboxPageQuery = {
  kind: InboxKind;
  /** From 1. */
  page: number;
  pageSize: number;
  search?: string;
  scope?: InboxScope;
};

export type InboxPage = { items: InboxItem[]; total: number };

/** One page of one kind in one organization, and how many there are in all. */
export async function loadInboxPage(org: Org, query: InboxPageQuery): Promise<InboxPage> {
  const { kind, page, pageSize, search, scope = null } = query;
  if (ownsNothing(scope)) return { items: [], total: 0 };
  const node = await queryOrganization(PAGE_QUERY, {
    orgId: org.id,
    first: String(pageSize),
    after: String((Math.max(1, page) - 1) * pageSize),
    sort: SORTS[kind],
    filter: kindFilter(kind, scope, search),
  });
  const items = (node.workspace?.edges ?? []).map((edge: any) => toItem(kind, org, edge.node));
  return { items, total: node.workspace?.pageInfo?.totalRecords ?? items.length };
}

/** How many of each kind one organization has, without any rows. */
export async function loadInboxCounts(org: Org, scope: InboxScope = null, search?: string): Promise<InboxCounts> {
  if (ownsNothing(scope)) return emptyCounts();
  const node = await queryOrganization(COUNTS_QUERY, { orgId: org.id, ...kindFilters(scope, search) });
  const counts = emptyCounts();
  KIND_ORDER.forEach((kind) => (counts[kind] = node[kind]?.pageInfo?.totalRecords ?? 0));
  return counts;
}

export type OrganizationCounts = { org: Org; counts: InboxCounts };

/** The counts of many organizations, a few requests at a time: the summary of an inbox too big to list. */
export async function loadOrganizationCounts(
  organizations: Org[],
  scope: InboxScope = null
): Promise<{ rows: OrganizationCounts[]; failedOrganizations: string[] }> {
  const settled = await mapWithLimit(organizations, MAX_PARALLEL_ORGANIZATIONS, (org) => loadInboxCounts(org, scope));
  const rows: OrganizationCounts[] = [];
  const failedOrganizations: string[] = [];
  settled.forEach((outcome, index) => {
    if (outcome.status === "fulfilled") rows.push({ org: organizations[index], counts: outcome.value });
    else failedOrganizations.push(organizations[index].name);
  });
  return { rows, failedOrganizations };
}
