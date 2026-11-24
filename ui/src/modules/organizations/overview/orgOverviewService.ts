import { apiPost } from "@/modules/api/apiWrapper";

import { StatusCounts } from "@/components/display/OverviewCards/overviewModel";

export type OrgOverviewCounts = {
  workspaces: number;
  status: StatusCounts;
  projects: number;
  compliant: number;
  nonCompliant: number;
};

export type RecentRun = {
  workspaceId: string;
  name: string;
  status: string;
  at?: string;
  projectName?: string;
};

export type OrgOverview = { counts: OrgOverviewCounts; recent: RecentRun[] };

const RECENT = 6;

/** The org-wide numbers and the last runs, in one request. What needs a person comes from the inbox service. */
export async function loadOrgOverview(orgId: string): Promise<OrgOverview> {
  const total = "pageInfo { totalRecords }";
  const result = await apiPost<unknown, any>(
    "/graphql/api/v1",
    {
      query: `query ($orgId: String!, $first: StringOrInt) {
        organization(ids: [$orgId]) { edges { node {
          all: workspace(first: "1", after: "0") { ${total} }
          completed: workspace(first: "1", after: "0", filter: "lastJobStatus=in=('completed','noChanges')") { ${total} }
          failed: workspace(first: "1", after: "0", filter: "lastJobStatus=='failed'") { ${total} }
          waiting: workspace(first: "1", after: "0", filter: "lastJobStatus=='waitingApproval'") { ${total} }
          running: workspace(first: "1", after: "0", filter: "lastJobStatus=in=('running','pending','approved','queue')") { ${total} }
          never: workspace(first: "1", after: "0", filter: "(lastJobStatus=='NeverExecuted',lastJobStatus=isnull=true)") { ${total} }
          compliant: workspace(first: "1", after: "0", filter: "policyComplianceStatus=='COMPLIANT'") { ${total} }
          nonCompliant: workspace(first: "1", after: "0", filter: "policyComplianceStatus=='NON_COMPLIANT'") { ${total} }
          projects: project(first: "1", after: "0") { ${total} }
          recent: workspace(first: $first, after: "0", sort: "-lastJobDate", filter: "lastJobStatus!='NeverExecuted'") {
            edges { node { id name lastJobStatus lastJobDate project { edges { node { name } } } } }
          }
        } } }
      }`,
      variables: { orgId, first: String(RECENT) },
    },
    { dataWrapped: true, contentType: "application/json" }
  );
  if (result.isError) throw new Error(result.error?.message ?? "The request failed");
  const node = result.data?.organization?.edges?.[0]?.node ?? {};
  const count = (key: string) => node[key]?.pageInfo?.totalRecords ?? 0;
  return {
    counts: {
      workspaces: count("all"),
      status: {
        completed: count("completed"),
        failed: count("failed"),
        waiting: count("waiting"),
        running: count("running"),
        never: count("never"),
      },
      projects: count("projects"),
      compliant: count("compliant"),
      nonCompliant: count("nonCompliant"),
    },
    recent: (node.recent?.edges ?? []).map((edge: any) => ({
      workspaceId: edge.node.id,
      name: edge.node.name,
      status: edge.node.lastJobStatus,
      at: edge.node.lastJobDate ?? undefined,
      projectName: edge.node.project?.edges?.[0]?.node?.name ?? undefined,
    })),
  };
}

/** Share of the evaluated workspaces that comply, as a whole percentage. Null when none was evaluated. */
export function complianceRate(counts: OrgOverviewCounts): number | null {
  const evaluated = counts.compliant + counts.nonCompliant;
  return evaluated === 0 ? null : Math.round((counts.compliant / evaluated) * 100);
}
