export type ProjectTab = "overview" | "workspaces" | "variables" | "policies" | "teams" | "general";

const STORAGE_KEY = "terrakube-project-landing-tab";
export const PROJECT_TABS: ProjectTab[] = ["overview", "workspaces", "variables", "policies", "teams", "general"];

export const PROJECT_TAB_LABELS: Record<ProjectTab, string> = {
  overview: "Overview",
  workspaces: "Workspaces",
  variables: "Variables",
  policies: "Policies",
  teams: "Teams",
  general: "General",
};

function isTab(value: unknown): value is ProjectTab {
  return typeof value === "string" && (PROJECT_TABS as string[]).includes(value);
}

/** The tab this person chose in their browser, or null when they have not chosen one. */
export function getStoredLandingTab(): ProjectTab | null {
  const stored = localStorage.getItem(STORAGE_KEY);
  return isTab(stored) ? stored : null;
}

export function getLandingTab(): ProjectTab {
  return getStoredLandingTab() ?? "overview";
}

/** Passing null forgets the choice, so the project and organization settings apply again. */
export function setLandingTab(tab: ProjectTab | null): void {
  if (tab === null) localStorage.removeItem(STORAGE_KEY);
  else localStorage.setItem(STORAGE_KEY, tab);
}

/** Order of precedence: the person's own choice, then the project, then the organization, then the overview. */
export function resolveLandingTab(project?: string | null, organization?: string | null): ProjectTab {
  return getStoredLandingTab() ?? (isTab(project) ? project : isTab(organization) ? organization : "overview");
}
