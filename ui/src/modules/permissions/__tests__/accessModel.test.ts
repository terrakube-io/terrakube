import { organizationCapabilities, projectCapabilities, workspaceCapabilities } from "../accessModel";
import type { OrgPermissionSet } from "../useOrgPermissions";
import type { ProjectPermissionSet } from "../useProjectPermissions";

const project = (overrides: Partial<ProjectPermissionSet> = {}): ProjectPermissionSet => ({
  manageProject: false,
  manageWorkspace: false,
  manageState: false,
  manageJob: false,
  planJob: false,
  approveJob: false,
  managePolicies: false,
  grantedThrough: ["devs"],
  accessLevel: "read",
  ...overrides,
});

const org = (overrides: Partial<OrgPermissionSet> = {}): OrgPermissionSet => ({
  manageState: false,
  manageWorkspace: false,
  manageModule: false,
  manageProvider: false,
  manageVcs: false,
  manageTemplate: false,
  manageCollection: false,
  manageJob: false,
  planJob: false,
  approveJob: false,
  managePermission: false,
  managePolicies: false,
  ...overrides,
});

const allowed = (items: { label: string; allowed: boolean }[]) =>
  Object.fromEntries(items.map((item) => [item.label, item.allowed]));

describe("projectCapabilities", () => {
  it("lets a read-only role view and nothing else", () => {
    const result = allowed(projectCapabilities(project(), false));

    expect(result["View the project, its workspaces and runs"]).toBe(true);
    expect(Object.values(result).filter(Boolean)).toHaveLength(1);
  });

  it("takes planning from either the plan or the job permission", () => {
    expect(allowed(projectCapabilities(project({ planJob: true }), false))["Plan runs"]).toBe(true);
    expect(allowed(projectCapabilities(project({ manageJob: true }), false))["Plan runs"]).toBe(true);
  });

  it("lets Manage Workspaces in the organization create workspaces and edit the project", () => {
    const result = allowed(projectCapabilities(project(), true));

    expect(result["Create and manage workspaces"]).toBe(true);
    expect(result["Edit the project, manage its access and delete it"]).toBe(true);
  });
});

describe("organizationCapabilities", () => {
  it("lets a team with no permissions view and nothing else", () => {
    const result = allowed(organizationCapabilities(org()));

    expect(result["View the organization, its projects and workspaces"]).toBe(true);
    expect(Object.values(result).filter(Boolean)).toHaveLength(1);
  });

  it("groups the registry and the connection permissions", () => {
    const result = allowed(organizationCapabilities(org({ manageProvider: true, manageTemplate: true })));

    expect(result["Manage modules and providers"]).toBe(true);
    expect(result["Manage VCS connections and templates"]).toBe(true);
    expect(result["Manage policies"]).toBe(false);
  });
});

describe("workspaceCapabilities", () => {
  it("reports each workspace permission", () => {
    const result = allowed(
      workspaceCapabilities({
        manageWorkspace: true,
        manageState: false,
        manageJob: false,
        planJob: true,
        approveJob: false,
      })
    );

    expect(result["Edit, lock and delete the workspace"]).toBe(true);
    expect(result["Plan runs"]).toBe(true);
    expect(result["Approve runs"]).toBe(false);
    expect(result["Read and manage state"]).toBe(false);
  });
});
