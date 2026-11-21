export type VariableCategory = "TERRAFORM" | "ENV";

export type VariableEntry = {
  id: string;
  key: string;
  value?: string | null;
  category: VariableCategory;
  sensitive: boolean;
  hcl: boolean;
};

export type CollectionEntry = {
  id: string;
  name: string;
  priority: number;
  /** What the collection is attached to for this workspace. */
  scope: "WORKSPACE" | "PROJECT" | "ORGANIZATION";
  items: VariableEntry[];
};

export type VariableSource =
  | { type: "workspace" }
  | { type: "global" }
  | { type: "collection"; id: string; name: string; scope: CollectionEntry["scope"] };

export type EffectiveVariable = VariableEntry & { source: VariableSource };

// Same order a run uses (see CollectionResolutionService on the API): narrower scopes first, then priority.
const SCOPE_ORDER: Record<CollectionEntry["scope"], number> = { WORKSPACE: 0, PROJECT: 1, ORGANIZATION: 2 };

/**
 * The variables a run of one workspace sees, and where each one comes from. A key is taken from the first source
 * that defines it: the workspace's own variables, then the organization's global variables, then collections
 * (attached to the workspace, then its project, then the organization, each by descending priority). Terraform
 * variables and environment variables are separate namespaces, as they are in a run.
 */
export function resolveEffectiveVariables(
  workspaceVariables: VariableEntry[],
  globals: VariableEntry[],
  collections: CollectionEntry[]
): EffectiveVariable[] {
  const seen = new Set<string>();
  const result: EffectiveVariable[] = [];
  const take = (entry: VariableEntry, source: VariableSource) => {
    const id = `${entry.category}:${entry.key}`;
    if (seen.has(id)) return;
    seen.add(id);
    result.push({ ...entry, source });
  };

  workspaceVariables.forEach((entry) => take(entry, { type: "workspace" }));
  globals.forEach((entry) => take(entry, { type: "global" }));
  [...collections]
    .sort((a, b) => SCOPE_ORDER[a.scope] - SCOPE_ORDER[b.scope] || b.priority - a.priority)
    .forEach((collection) =>
      collection.items.forEach((entry) =>
        take(entry, { type: "collection", id: collection.id, name: collection.name, scope: collection.scope })
      )
    );

  return result.sort((a, b) => a.key.localeCompare(b.key) || a.category.localeCompare(b.category));
}

export function describeSource(source: VariableSource): string {
  switch (source.type) {
    case "workspace":
      return "workspace";
    case "global":
      return "organisation global";
    default:
      return `collection "${source.name}" (${source.scope.toLowerCase()})`;
  }
}
