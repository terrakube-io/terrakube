import { apiPost } from "@/modules/api/apiWrapper";
import { ApiResponse } from "@/modules/api/types";
import { CollectionEntry, VariableEntry } from "./effectiveVariables";

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
export async function getWorkspaceVariableData(
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
