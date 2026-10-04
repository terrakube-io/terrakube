import { TagModel } from "@/modules/organizations/types";
import { WorkspaceTagBinding } from "@/modules/workspaces/types";

/** The key of a tag binding, falling back to its id while the organization tag list is still loading. */
export function workspaceTagKey(binding: WorkspaceTagBinding, tags: TagModel[]): string {
  return tags.find((tag) => tag.id === binding.tagId)?.name ?? binding.tagId;
}

/** A tag as one line of text, for a title attribute. On screen, WorkspaceTagLabel styles key and value apart. */
export function formatWorkspaceTag(binding: WorkspaceTagBinding, tags: TagModel[]): string {
  const key = workspaceTagKey(binding, tags);
  return binding.value ? `${key} = ${binding.value}` : key;
}

/**
 * Tag bindings by key, then by value. The API returns them in no fixed order, so without this a row that
 * shows only its first few tags could show different ones from one load to the next.
 */
export function sortWorkspaceTagBindings(bindings: WorkspaceTagBinding[], tags: TagModel[]): WorkspaceTagBinding[] {
  const names = new Map(tags.map((tag) => [tag.id, tag.name]));
  return bindings
    .map((binding) => ({ binding, key: names.get(binding.tagId) ?? binding.tagId }))
    .sort((a, b) => a.key.localeCompare(b.key) || (a.binding.value ?? "").localeCompare(b.binding.value ?? ""))
    .map(({ binding }) => binding);
}
