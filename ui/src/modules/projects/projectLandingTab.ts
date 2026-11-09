export type ProjectTab = "overview" | "workspaces" | "teams" | "general";

const STORAGE_KEY = "terrakube-project-landing-tab";
const TABS: ProjectTab[] = ["overview", "workspaces", "teams", "general"];

export const PROJECT_TAB_LABELS: Record<ProjectTab, string> = {
  overview: "Overview",
  workspaces: "Workspaces",
  teams: "Teams",
  general: "General",
};

// The tab a project opens on. Overview unless the user picked another one.
export function getLandingTab(): ProjectTab {
  const stored = localStorage.getItem(STORAGE_KEY) as ProjectTab | null;
  return stored && TABS.includes(stored) ? stored : "overview";
}

export function setLandingTab(tab: ProjectTab): void {
  localStorage.setItem(STORAGE_KEY, tab);
}
