import { ProjectTab } from "./projectLandingTab";

export type ProjectSection = "overview" | "workspaces" | "policies" | "teams" | "variables" | "settings";

const SECTIONS: ProjectSection[] = ["overview", "workspaces", "policies", "teams", "variables", "settings"];

export function sectionFromParam(param?: string): ProjectSection {
  return SECTIONS.includes(param as ProjectSection) ? (param as ProjectSection) : "overview";
}

export function projectPath(orgid: string, projectId: string, section: ProjectSection = "overview"): string {
  const base = `/organizations/${orgid}/projects/${projectId}`;
  return section === "overview" ? base : `${base}/${section}`;
}

// Landing tabs are stored by the API as short names; General is the Settings page.
export function sectionForTab(tab: ProjectTab): ProjectSection {
  return tab === "general" ? "settings" : tab;
}

export const SECTION_LABELS: Record<ProjectSection, string> = {
  overview: "Overview",
  workspaces: "Workspaces",
  policies: "Policies",
  teams: "Team access",
  variables: "Variables",
  settings: "Settings",
};
