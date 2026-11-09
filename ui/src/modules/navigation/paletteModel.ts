export type PaletteKind = "page" | "organization" | "project" | "workspace";

export type PaletteItem = {
  key: string;
  kind: PaletteKind;
  title: string;
  /** Where it is, for example the organization a workspace belongs to. */
  subtitle?: string;
  to: string;
};

const RECENT_KEY = "terrakube-palette-recent";
const RECENT_LIMIT = 6;

/** Case-insensitive match of every word of the query against the title and subtitle. */
export function matchesQuery(item: Pick<PaletteItem, "title" | "subtitle">, query: string): boolean {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return true;
  const haystack = `${item.title} ${item.subtitle ?? ""}`.toLowerCase();
  return words.every((word) => haystack.includes(word));
}

/** Names that start with the query come first, then the rest in their given order. */
export function rank(items: PaletteItem[], query: string): PaletteItem[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return items;
  const starts = (item: PaletteItem) => (item.title.toLowerCase().startsWith(needle) ? 0 : 1);
  return items
    .map((item, index) => ({ item, index }))
    .sort((a, b) => starts(a.item) - starts(b.item) || a.index - b.index)
    .map(({ item }) => item);
}

/** The pages of the organization the person is in, always one keystroke away. */
export function pageItems(orgId: string | null, orgName?: string): PaletteItem[] {
  const pages: PaletteItem[] = [{ key: "page:orgs", kind: "page", title: "All organizations", to: "/organizations" }];
  if (!orgId) return pages;
  const where = orgName ? `${orgName}` : undefined;
  return [
    { key: "page:projects", kind: "page", title: "Projects", subtitle: where, to: `/organizations/${orgId}/projects` },
    {
      key: "page:workspaces",
      kind: "page",
      title: "Workspaces",
      subtitle: where,
      to: `/organizations/${orgId}/workspaces`,
    },
    { key: "page:registry", kind: "page", title: "Registry", subtitle: where, to: `/organizations/${orgId}/registry` },
    { key: "page:settings", kind: "page", title: "Settings", subtitle: where, to: `/organizations/${orgId}/settings` },
    ...pages,
  ];
}

export function loadRecents(): PaletteItem[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(RECENT_KEY) ?? "[]");
    return Array.isArray(parsed)
      ? parsed
          .filter((item) => item && typeof item.key === "string" && typeof item.to === "string")
          .slice(0, RECENT_LIMIT)
      : [];
  } catch {
    return [];
  }
}

/** Remembers what was opened, newest first, once each, so the palette can offer it when empty. */
export function rememberRecent(item: PaletteItem): void {
  const next = [item, ...loadRecents().filter((existing) => existing.key !== item.key)].slice(0, RECENT_LIMIT);
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(next));
  } catch {
    // Storage is full or blocked: not remembering a recent must never stop the person from getting where they chose.
  }
}
