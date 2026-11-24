import { apiPost } from "@/modules/api/apiWrapper";
import { RUN_POLICY_WINDOW_DAYS, createdWithin } from "./jobWindow";
import quoteRsql from "./utils/quoteRsql";

export type PolicyResult = "FAILED" | "WARNING" | "OVERRIDDEN" | "EXEMPTED" | "PASSED";

// The worst result of a run is the one a person needs to see: a run can have one evaluation per plan step.
const SEVERITY: PolicyResult[] = ["FAILED", "WARNING", "OVERRIDDEN", "EXEMPTED", "PASSED"];

export function worstResult(statuses: string[]): PolicyResult | null {
  return SEVERITY.find((result) => statuses.includes(result)) ?? null;
}

/**
 * The policy result of each run of a workspace, by run id. A run without an evaluation (policies did not apply, or
 * it never planned) is left out. Job is not paginated by Elide, so the filter is what bounds this: only the runs of
 * the last {@link RUN_POLICY_WINDOW_DAYS} days are read, and an older run is shown without a policy result.
 */
export async function listRunPolicyResults(orgId: string, workspaceId: string): Promise<Record<string, PolicyResult>> {
  const result = await apiPost<unknown, any>(
    "/graphql/api/v1",
    {
      query: `query ($orgId: String!, $filter: String) {
        organization(ids: [$orgId]) { edges { node {
          job(filter: $filter) { edges { node { id policyEvaluation { edges { node { status override { edges { node { id } } } } } } } } }
        } } }
      }`,
      variables: { orgId, filter: `workspace.id==${quoteRsql(workspaceId)};${createdWithin(RUN_POLICY_WINDOW_DAYS)}` },
    },
    { dataWrapped: true, contentType: "application/json" }
  );
  if (result.isError) throw new Error(result.error?.message ?? "The request failed");
  const jobs = result.data?.organization?.edges?.[0]?.node?.job?.edges ?? [];
  const results: Record<string, PolicyResult> = {};
  jobs.forEach((edge: any) => {
    // The API never stores OVERRIDDEN: an override is a record on an evaluation whose status is then set to PASSED, so
    // an evaluation that has one is read as overridden, or a run whose violation someone let through looks like a pass.
    const statuses = (edge.node.policyEvaluation?.edges ?? []).map((evaluation: any) =>
      (evaluation.node.override?.edges ?? []).length > 0 ? "OVERRIDDEN" : String(evaluation.node.status)
    );
    const worst = worstResult(statuses);
    if (worst) results[String(edge.node.id)] = worst;
  });
  return results;
}
