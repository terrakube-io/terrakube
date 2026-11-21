import { apiPost } from "@/modules/api/apiWrapper";
import { isOrgId } from "@/config/orgId";
import quoteRsql from "@/modules/workspaces/utils/quoteRsql";

export type NamedItem = { id: string; name: string };

async function graphql<T>(query: string, variables: Record<string, unknown> = {}): Promise<T> {
  const result = await apiPost<unknown, any>(
    "/graphql/api/v1",
    { query, variables },
    {
      dataWrapped: true,
      contentType: "application/json",
    }
  );
  if (result.isError) throw new Error(result.error?.message ?? "The request failed");
  return result.data as T;
}

const nodes = (connection: any): any[] => (connection?.edges ?? []).map((edge: any) => edge.node);

/** Every organization the caller can see, with just id and name (not the workspace lists the sidebar loads). */
export async function listOrganizationNames(): Promise<NamedItem[]> {
  const data = await graphql<any>("{ organization { edges { node { id name } } } }");
  return nodes(data.organization).map((node) => ({ id: node.id, name: node.name }));
}

/** An organization by id, or by name: exact first, then ignoring case when that is unambiguous. */
export async function resolveOrganization(idOrName: string): Promise<NamedItem | null> {
  const all = await listOrganizationNames();
  if (isOrgId(idOrName)) return all.find((org) => org.id === idOrName) ?? null;
  const exact = all.find((org) => org.name === idOrName);
  if (exact) return exact;
  const loose = all.filter((org) => org.name.toLowerCase() === String(idOrName).toLowerCase());
  return loose.length === 1 ? loose[0] : null;
}

async function findByName(orgId: string, field: "workspace" | "project", name: string): Promise<NamedItem | null> {
  // project and workspace names are unique within an organization, so one row is the answer.
  const query =
    field === "workspace"
      ? `query ($orgId: String!, $filter: String) { organization(ids: [$orgId]) { edges { node {
           workspace(first: "2", after: "0", filter: $filter) { edges { node { id name } } } } } } }`
      : `query ($orgId: String!, $filter: String) { organization(ids: [$orgId]) { edges { node {
           project(first: "2", after: "0", filter: $filter) { edges { node { id name } } } } } } }`;
  const data = await graphql<any>(query, { orgId, filter: `name==${quoteRsql(name)}` });
  const found = nodes(nodes(data.organization)[0]?.[field]);
  return found.length === 1 ? { id: found[0].id, name: found[0].name } : null;
}

export const findWorkspaceByName = (orgId: string, name: string) => findByName(orgId, "workspace", name);
export const findProjectByName = (orgId: string, name: string) => findByName(orgId, "project", name);

export type SearchHits = { projects: NamedItem[]; workspaces: NamedItem[] };

/** Terms go into a filter as a wildcard, so the characters that mean something to the filter are dropped. */
export function wildcardTerm(text: string): string {
  return text.replace(/[*\\'"(),;=!<>~]/g, " ").trim();
}

/** Projects and workspaces of one organization whose name contains the text, a few of each. */
export async function searchOrganization(orgId: string, text: string, limit = 6): Promise<SearchHits> {
  const term = wildcardTerm(text);
  if (!term) return { projects: [], workspaces: [] };
  const filter = `name=ini=*${term}*`;
  const data = await graphql<any>(
    `
      query ($orgId: String!, $first: StringOrInt, $filter: String) {
        organization(ids: [$orgId]) {
          edges {
            node {
              project(first: $first, after: "0", sort: "+name", filter: $filter) {
                edges {
                  node {
                    id
                    name
                  }
                }
              }
              workspace(first: $first, after: "0", sort: "+name", filter: $filter) {
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
    `,
    { orgId, first: String(limit), filter }
  );
  const node = nodes(data.organization)[0] ?? {};
  const pick = (connection: any): NamedItem[] => nodes(connection).map((item) => ({ id: item.id, name: item.name }));
  return { projects: pick(node.project), workspaces: pick(node.workspace) };
}
