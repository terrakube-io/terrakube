import { apiPost } from "@/modules/api/apiWrapper";
import { mapWithLimit } from "@/modules/utils/mapWithLimit";
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

// The filters travel as variables, because the owner part holds names a person typed and quoting them inside the
// query text would mean escaping twice.
const QUERY = `query ($orgId: String!, $first: StringOrInt, $waitingFilter: String, $failedFilter: String, $driftedFilter: String, $policyFilter: String) {
  organization(ids: [$orgId]) { edges { node {
    waiting: workspace(first: $first, after: "0", sort: "+lastJobDate", filter: $waitingFilter) {
      edges { node { ${WORKSPACE_FIELDS} } } pageInfo { totalRecords } }
    failed: workspace(first: $first, after: "0", sort: "-lastJobDate", filter: $failedFilter) {
      edges { node { ${WORKSPACE_FIELDS} } } pageInfo { totalRecords } }
    drifted: workspace(first: $first, after: "0", sort: "+driftFirstSeen", filter: $driftedFilter) {
      edges { node { ${WORKSPACE_FIELDS} } } pageInfo { totalRecords } }
    policy: workspace(first: $first, after: "0", sort: "-lastJobDate", filter: $policyFilter) {
      edges { node { ${WORKSPACE_FIELDS} } } pageInfo { totalRecords } }
  } } }
}`;

/** Limits the inbox to the projects owned by these teams. Null means everything the person can see. */
export type InboxScope = { ownerTeams: string[] } | null;

const scoped = (filter: string, scope: InboxScope) =>
  scope ? `${filter};project.ownerTeam=in=(${scope.ownerTeams.map(quoteRsql).join(",")})` : filter;

type Org = { id: string; name: string };

async function loadOrganization(org: Org, scope: InboxScope): Promise<{ items: InboxItem[]; counts: InboxCounts }> {
  const result = await apiPost<unknown, any>(
    "/graphql/api/v1",
    {
      query: QUERY,
      variables: {
        orgId: org.id,
        first: String(ROWS_PER_KIND),
        waitingFilter: scoped(BASE_FILTERS.waiting, scope),
        failedFilter: scoped(BASE_FILTERS.failed, scope),
        driftedFilter: scoped(BASE_FILTERS.drifted, scope),
        policyFilter: scoped(BASE_FILTERS.policy, scope),
      },
    },
    { dataWrapped: true, contentType: "application/json" }
  );
  if (result.isError) throw new Error(result.error?.message ?? "The request failed");
  const node = result.data?.organization?.edges?.[0]?.node ?? {};
  const items: InboxItem[] = [];
  const counts = emptyCounts();
  KIND_ORDER.forEach((kind) => {
    counts[kind] = node[kind]?.pageInfo?.totalRecords ?? 0;
    (node[kind]?.edges ?? []).forEach((edge: any) =>
      items.push({
        key: `${kind}:${edge.node.id}`,
        kind,
        orgId: org.id,
        orgName: org.name,
        workspaceId: edge.node.id,
        name: edge.node.name,
        projectName: edge.node.project?.edges?.[0]?.node?.name ?? undefined,
        ownerTeam: edge.node.project?.edges?.[0]?.node?.ownerTeam ?? undefined,
        at: (kind === "drifted" ? edge.node.driftFirstSeen : edge.node.lastJobDate) ?? undefined,
        runId: kind === "drifted" && edge.node.driftJobId != null ? String(edge.node.driftJobId) : undefined,
      })
    );
  });
  return { items, counts };
}

/** What needs a person across every organization given. One request each, in parallel, so one slow one is not fatal. */
export async function loadInbox(organizations: Org[], scope: InboxScope = null): Promise<InboxData> {
  // A person in no team owns nothing, and an empty "in" list is not a filter the API accepts.
  if (scope && scope.ownerTeams.length === 0) {
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
