import { apiPost } from "@/modules/api/apiWrapper";
import { InboxCounts, InboxData, InboxItem, InboxKind, emptyCounts, sortItems } from "./inboxModel";

const ROWS_PER_KIND = 10;

const WORKSPACE_FIELDS = "id name lastJobDate project { edges { node { name } } }";

const QUERY = `query ($orgId: String!, $first: StringOrInt) {
  organization(ids: [$orgId]) { edges { node {
    waiting: workspace(first: $first, after: "0", sort: "+lastJobDate", filter: "lastJobStatus=='waitingApproval'") {
      edges { node { ${WORKSPACE_FIELDS} } } pageInfo { totalRecords } }
    failed: workspace(first: $first, after: "0", sort: "-lastJobDate", filter: "lastJobStatus=='failed'") {
      edges { node { ${WORKSPACE_FIELDS} } } pageInfo { totalRecords } }
    policy: workspace(first: $first, after: "0", sort: "-lastJobDate",
        filter: "policyComplianceStatus=='NON_COMPLIANT';lastJobStatus!='failed';lastJobStatus!='waitingApproval'") {
      edges { node { ${WORKSPACE_FIELDS} } } pageInfo { totalRecords } }
  } } }
}`;

type Org = { id: string; name: string };

async function loadOrganization(org: Org): Promise<{ items: InboxItem[]; counts: InboxCounts }> {
  const result = await apiPost<unknown, any>(
    "/graphql/api/v1",
    { query: QUERY, variables: { orgId: org.id, first: String(ROWS_PER_KIND) } },
    { dataWrapped: true, contentType: "application/json" }
  );
  if (result.isError) throw new Error(result.error?.message ?? "The request failed");
  const node = result.data?.organization?.edges?.[0]?.node ?? {};
  const items: InboxItem[] = [];
  const counts = emptyCounts();
  (["waiting", "failed", "policy"] as InboxKind[]).forEach((kind) => {
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
        at: edge.node.lastJobDate ?? undefined,
      })
    );
  });
  return { items, counts };
}

/** What needs a person across every organization given. One request each, in parallel, so one slow one is not fatal. */
export async function loadInbox(organizations: Org[]): Promise<InboxData> {
  const settled = await Promise.allSettled(organizations.map(loadOrganization));
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
  const sorted = (["waiting", "failed", "policy"] as InboxKind[]).flatMap((kind) =>
    sortItems(
      kind,
      items.filter((item) => item.kind === kind)
    )
  );
  return { items: sorted, counts, failedOrganizations };
}
