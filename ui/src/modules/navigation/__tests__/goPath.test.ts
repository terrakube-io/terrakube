import { goDestination, parseGoPath } from "../goPath";

describe("parseGoPath", () => {
  it("reads an organization link", () => {
    expect(parseGoPath("")).toEqual({ kind: "organization" });
    expect(parseGoPath("/")).toEqual({ kind: "organization" });
  });

  it("reads a project and a workspace by name", () => {
    expect(parseGoPath("projects/payments-prod")).toEqual({ kind: "project", name: "payments-prod" });
    expect(parseGoPath("workspaces/ledger-db")).toEqual({ kind: "workspace", name: "ledger-db" });
  });

  it("reads a workspace section and a run number", () => {
    expect(parseGoPath("workspaces/ledger-db/runs")).toEqual({ kind: "workspace", name: "ledger-db", section: "runs" });
    expect(parseGoPath("workspaces/ledger-db/runs/42")).toEqual({
      kind: "workspace",
      name: "ledger-db",
      section: "runs",
      run: "42",
    });
    expect(parseGoPath("workspaces/ledger-db/run-triggers")).toMatchObject({ section: "run-triggers" });
  });

  it("keeps a name as the router gave it, spaces and all", () => {
    expect(parseGoPath("workspaces/my workspace")).toEqual({ kind: "workspace", name: "my workspace" });
  });

  it("refuses a name with a star, which the API would read as a wildcard", () => {
    expect(parseGoPath("workspaces/prod*")).toBeNull();
    expect(parseGoPath("workspaces/*prod")).toBeNull();
    expect(parseGoPath("projects/pay*ments")).toBeNull();
  });

  it("refuses shapes it does not know instead of guessing", () => {
    expect(parseGoPath("nonsense")).toBeNull();
    expect(parseGoPath("workspaces")).toBeNull();
    expect(parseGoPath("workspaces/ledger-db/admin")).toBeNull();
    expect(parseGoPath("workspaces/ledger-db/states/7")).toBeNull();
    expect(parseGoPath("workspaces/ledger-db/runs/abc")).toBeNull();
    expect(parseGoPath("workspaces/ledger-db/runs/1/extra")).toBeNull();
    expect(parseGoPath("projects/a/b")).toBeNull();
  });

  it("does not decode again: a percent sign in a name stays a percent sign", () => {
    expect(parseGoPath("workspaces/%E0%A4%A")).toEqual({ kind: "workspace", name: "%E0%A4%A" });
    expect(parseGoPath("projects/50%-off")).toEqual({ kind: "project", name: "50%-off" });
  });
});

describe("goDestination", () => {
  it("builds the app address for each kind of target", () => {
    expect(goDestination("o", { kind: "organization" }, {})).toBe("/organizations/o/projects");
    expect(goDestination("o", { kind: "project", name: "p" }, { projectId: "pid" })).toBe(
      "/organizations/o/projects/pid"
    );
    expect(goDestination("o", { kind: "workspace", name: "w" }, { workspaceId: "wid" })).toBe(
      "/organizations/o/workspaces/wid"
    );
    expect(
      goDestination("o", { kind: "workspace", name: "w", section: "runs", run: "9" }, { workspaceId: "wid" })
    ).toBe("/organizations/o/workspaces/wid/runs/9");
    expect(goDestination("o", { kind: "workspace", name: "w", section: "variables" }, { workspaceId: "wid" })).toBe(
      "/organizations/o/workspaces/wid/variables"
    );
  });
});
