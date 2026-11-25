export type GoTarget =
  | { kind: "organization" }
  | { kind: "project"; name: string }
  | { kind: "workspace"; name: string; section?: string; run?: string };

// The workspace pages a link can name. Anything else is not a page and is refused rather than guessed.
const WORKSPACE_SECTIONS = ["runs", "states", "variables", "schedules", "run-triggers", "settings"];

/**
 * Reads the part of a /go link after the organization: nothing (the organization), projects/<name>, or
 * workspaces/<name> with an optional section and, for runs, a run number. Names come from a pull request or a chat
 * message, not from the app, so unknown shapes return null. The address arrives decoded by the router, so a segment is
 * used as it is: decoding it again would turn `50%25` into an error and `%2541` into `A`.
 */
export function parseGoPath(rest: string): GoTarget | null {
  const segments = rest.split("/").filter((segment) => segment !== "");
  if (segments.length === 0) return { kind: "organization" };

  const [kind, rawName, section, run, ...extra] = segments;
  const name = rawName === undefined || rawName.length === 0 ? null : rawName;
  if (name === null || extra.length > 0) return null;
  // Workspace and project names cannot contain a star, and the API reads one in a name filter as a wildcard, so a
  // link with one would open whichever name happens to match instead of being refused.
  if (name.includes("*")) return null;

  if (kind === "projects") {
    return segments.length === 2 ? { kind: "project", name } : null;
  }
  if (kind === "workspaces") {
    if (section === undefined) return { kind: "workspace", name };
    if (!WORKSPACE_SECTIONS.includes(section)) return null;
    if (run === undefined) return { kind: "workspace", name, section };
    // Only a run page takes one more segment, and a run is a number.
    if (section !== "runs" || !/^\d+$/.test(run)) return null;
    return { kind: "workspace", name, section, run };
  }
  return null;
}

/** The app address of a resolved target. */
export function goDestination(
  orgId: string,
  target: GoTarget,
  ids: { projectId?: string; workspaceId?: string }
): string {
  const org = `/organizations/${orgId}`;
  if (target.kind === "organization") return `${org}/projects`;
  if (target.kind === "project") return `${org}/projects/${ids.projectId}`;
  const base = `${org}/workspaces/${ids.workspaceId}`;
  if (!target.section) return base;
  return target.run ? `${base}/${target.section}/${target.run}` : `${base}/${target.section}`;
}
