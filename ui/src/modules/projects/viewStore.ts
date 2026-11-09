export type SavedView = { name: string; query: string };

export const MAX_SAVED_VIEWS = 12;
export const MAX_VIEW_NAME = 40;

const KEYS = ["q", "chip", "sort"];

const storageKey = (orgid: string) => `terrakube-saved-views:projects:${orgid}`;

/** Only the parts that describe what is listed, never the page, the page size or cards versus table. */
export function viewQuery(params: URLSearchParams): string {
  const out = new URLSearchParams();
  KEYS.forEach((key) => {
    const value = params.get(key);
    if (value) out.set(key, value);
  });
  return out.toString();
}

export function loadViews(orgid: string): SavedView[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(storageKey(orgid)) ?? "[]");
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((item) => item && typeof item.name === "string" && typeof item.query === "string")
      .slice(0, MAX_SAVED_VIEWS)
      .map((item) => ({ name: item.name, query: viewQuery(new URLSearchParams(item.query)) }));
  } catch {
    return [];
  }
}

function persist(orgid: string, views: SavedView[]) {
  try {
    localStorage.setItem(storageKey(orgid), JSON.stringify(views));
  } catch {
    // Private mode or a full quota: the view simply is not kept.
  }
}

/** Returns an error to show, or null when the name can be used. */
export function validateViewName(name: string, views: SavedView[]): string | null {
  const trimmed = name.trim();
  if (!trimmed) return "Give the view a name.";
  if (trimmed.length > MAX_VIEW_NAME) return `Use ${MAX_VIEW_NAME} characters or fewer.`;
  if (views.some((view) => view.name.toLowerCase() === trimmed.toLowerCase())) return "A view with this name exists.";
  if (views.length >= MAX_SAVED_VIEWS) return `You can keep up to ${MAX_SAVED_VIEWS} views. Delete one first.`;
  return null;
}

export function addView(orgid: string, name: string, query: string): SavedView[] {
  const views = loadViews(orgid);
  if (validateViewName(name, views)) return views;
  const next = [...views, { name: name.trim(), query }];
  persist(orgid, next);
  return next;
}

export function removeView(orgid: string, name: string): SavedView[] {
  const next = loadViews(orgid).filter((view) => view.name !== name);
  persist(orgid, next);
  return next;
}
