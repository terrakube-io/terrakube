export type ListViewMode = "new" | "legacy";

const LIST_VIEW_STORAGE_KEY = "terrakube.listViewMode";

export function getStoredListViewMode(): ListViewMode {
  const stored = localStorage.getItem(LIST_VIEW_STORAGE_KEY);
  return stored === "legacy" ? "legacy" : "new";
}

export function setStoredListViewMode(mode: ListViewMode): void {
  localStorage.setItem(LIST_VIEW_STORAGE_KEY, mode);
}
