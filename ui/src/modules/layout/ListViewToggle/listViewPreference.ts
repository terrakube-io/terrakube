export type ListViewMode = "compact" | "cards";

const LIST_VIEW_STORAGE_KEY = "terrakube.listViewMode";

export function getStoredListViewMode(): ListViewMode {
  const stored = localStorage.getItem(LIST_VIEW_STORAGE_KEY);
  return stored === "cards" ? "cards" : "compact";
}

export function setStoredListViewMode(mode: ListViewMode): void {
  localStorage.setItem(LIST_VIEW_STORAGE_KEY, mode);
}
