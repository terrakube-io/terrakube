import type { OrgPermissionSet } from "./useOrgPermissions";
import type { ProjectPermissionSet } from "./useProjectPermissions";

export type AccessScope = "organization" | "project" | "workspace";

export type Capability = { label: string; allowed: boolean };

export const accessLevelLabel = (level: string) =>
  level === "custom" ? "Custom" : level.charAt(0).toUpperCase() + level.slice(1);

/** What a person can do in a project. Manage Workspaces in the organization also reaches every project. */
export function projectCapabilities(
  permissions: ProjectPermissionSet,
  organizationManageWorkspace: boolean
): Capability[] {
  const manageWorkspaces = permissions.manageWorkspace || organizationManageWorkspace;
  return [
    { label: "View the project, its workspaces and runs", allowed: true },
    { label: "Plan runs", allowed: permissions.planJob || permissions.manageJob },
    { label: "Approve runs", allowed: permissions.approveJob },
    { label: "Create and manage workspaces", allowed: manageWorkspaces },
    { label: "Manage state", allowed: permissions.manageState },
    { label: "Manage policies", allowed: permissions.managePolicies },
    {
      label: "Edit the project, manage its access and delete it",
      allowed: permissions.manageProject || organizationManageWorkspace,
    },
  ];
}

/** What a person can do across an organization, from the teams they are in. */
export function organizationCapabilities(permissions: OrgPermissionSet): Capability[] {
  return [
    { label: "View the organization, its projects and workspaces", allowed: true },
    { label: "Plan runs", allowed: permissions.planJob || permissions.manageJob },
    { label: "Approve runs", allowed: permissions.approveJob },
    { label: "Create and manage workspaces and projects", allowed: permissions.manageWorkspace },
    { label: "Manage state", allowed: permissions.manageState },
    { label: "Manage modules and providers", allowed: permissions.manageModule || permissions.manageProvider },
    { label: "Manage VCS connections and templates", allowed: permissions.manageVcs || permissions.manageTemplate },
    { label: "Manage variable collections", allowed: permissions.manageCollection },
    { label: "Manage policies", allowed: permissions.managePolicies },
    { label: "Manage teams and access", allowed: permissions.managePermission },
  ];
}

export type WorkspacePermissionSet = {
  manageWorkspace: boolean;
  manageState: boolean;
  manageJob: boolean;
  planJob: boolean;
  approveJob: boolean;
};

/** What a person can do in one workspace: their organization teams, the project's access and the workspace's own. */
export function workspaceCapabilities(permissions: WorkspacePermissionSet): Capability[] {
  return [
    { label: "View the workspace, its runs and outputs", allowed: true },
    { label: "Plan runs", allowed: permissions.planJob || permissions.manageJob },
    { label: "Approve runs", allowed: permissions.approveJob },
    { label: "Edit, lock and delete the workspace", allowed: permissions.manageWorkspace },
    { label: "Read and manage state", allowed: permissions.manageState },
  ];
}
