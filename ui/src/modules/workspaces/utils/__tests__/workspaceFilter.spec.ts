import { JobStatus } from "../../../../domain/types";
import { WorkspaceListItem } from "../../types";
import { filterWorkspaces, WorkspaceStatusFilter, PolicyComplianceFilter } from "../workspaceFilter";

const ws = (overrides: Partial<WorkspaceListItem>): WorkspaceListItem => ({
  id: overrides.id ?? "id",
  name: overrides.name ?? "name",
  iacType: "terraform",
  source: "",
  ...overrides,
});

describe("filterWorkspaces", () => {
  it("returns everything when status is All, search is empty, no tags, no project", () => {
    const list = [ws({ id: "1" }), ws({ id: "2" })];
    const result = filterWorkspaces(list, {
      status: WorkspaceStatusFilter.All,
      search: "",
      tagIds: [],
      projectId: null,
    });
    expect(result.map((w) => w.id)).toEqual(["1", "2"]);
  });

  it("filters by exact status", () => {
    const list = [ws({ id: "1", lastStatus: JobStatus.Running }), ws({ id: "2", lastStatus: JobStatus.Failed })];
    const result = filterWorkspaces(list, {
      status: JobStatus.Running,
      search: "",
      tagIds: [],
      projectId: null,
    });
    expect(result.map((w) => w.id)).toEqual(["1"]);
  });

  it("'NeverExecuted' status matches workspaces with no lastStatus or with NeverExecuted status", () => {
    const list = [
      ws({ id: "1", lastStatus: JobStatus.Running }),
      ws({ id: "2" }),
      ws({ id: "3", lastStatus: "NeverExecuted" as any }),
      ws({ id: "4", lastStatus: JobStatus.NeverExecuted }),
    ];
    const result = filterWorkspaces(list, {
      status: WorkspaceStatusFilter.NeverExecuted,
      search: "",
      tagIds: [],
      projectId: null,
    });
    expect(result.map((w) => w.id)).toEqual(["2", "3", "4"]);
  });

  it("filters by search matching name or description", () => {
    const list = [
      ws({ id: "1", name: "billing-api", description: "handles invoices" }),
      ws({ id: "2", name: "auth-core", description: "sso" }),
    ];
    const result = filterWorkspaces(list, {
      status: WorkspaceStatusFilter.All,
      search: "invoices",
      tagIds: [],
      projectId: null,
    });
    expect(result.map((w) => w.id)).toEqual(["1"]);
  });

  it("filters by tagIds (any match)", () => {
    const list = [ws({ id: "1", tags: ["a", "b"] }), ws({ id: "2", tags: ["c"] })];
    const result = filterWorkspaces(list, {
      status: WorkspaceStatusFilter.All,
      search: "",
      tagIds: ["b"],
      projectId: null,
    });
    expect(result.map((w) => w.id)).toEqual(["1"]);
  });

  it("filters by projectId", () => {
    const list = [ws({ id: "1", projectId: "p1" }), ws({ id: "2", projectId: "p2" })];
    const result = filterWorkspaces(list, {
      status: WorkspaceStatusFilter.All,
      search: "",
      tagIds: [],
      projectId: "p1",
    });
    expect(result.map((w) => w.id)).toEqual(["1"]);
  });

  it("__unassigned__ projectId matches workspaces with no projectId", () => {
    const list = [ws({ id: "1", projectId: "p1" }), ws({ id: "2" })];
    const result = filterWorkspaces(list, {
      status: WorkspaceStatusFilter.All,
      search: "",
      tagIds: [],
      projectId: "__unassigned__",
    });
    expect(result.map((w) => w.id)).toEqual(["2"]);
  });

  it("filters by policyStatus (Compliant, NonCompliant, Exempted)", () => {
    const list = [
      ws({ id: "1", policyComplianceStatus: "COMPLIANT" }),
      ws({ id: "2", policyComplianceStatus: "NON_COMPLIANT" }),
      ws({ id: "3", policyComplianceStatus: "EXEMPTED" }),
    ];

    const compliant = filterWorkspaces(list, {
      status: WorkspaceStatusFilter.All,
      policyStatus: PolicyComplianceFilter.Compliant,
      search: "",
      tagIds: [],
      projectId: null,
    });
    expect(compliant.map((w) => w.id)).toEqual(["1"]);

    const nonCompliant = filterWorkspaces(list, {
      status: WorkspaceStatusFilter.All,
      policyStatus: PolicyComplianceFilter.NonCompliant,
      search: "",
      tagIds: [],
      projectId: null,
    });
    expect(nonCompliant.map((w) => w.id)).toEqual(["2"]);

    const exempted = filterWorkspaces(list, {
      status: WorkspaceStatusFilter.All,
      policyStatus: PolicyComplianceFilter.Exempted,
      search: "",
      tagIds: [],
      projectId: null,
    });
    expect(exempted.map((w) => w.id)).toEqual(["3"]);
  });

  it("policyStatus Unknown matches both explicit UNKNOWN and undefined/null compliance status", () => {
    const list = [
      ws({ id: "1", policyComplianceStatus: "COMPLIANT" }),
      ws({ id: "2", policyComplianceStatus: "UNKNOWN" }),
      ws({ id: "3", policyComplianceStatus: undefined }),
    ];

    const unknown = filterWorkspaces(list, {
      status: WorkspaceStatusFilter.All,
      policyStatus: PolicyComplianceFilter.Unknown,
      search: "",
      tagIds: [],
      projectId: null,
    });
    expect(unknown.map((w) => w.id)).toEqual(["2", "3"]);
  });

  it("combines status and policyStatus filters", () => {
    const list = [
      ws({ id: "1", lastStatus: JobStatus.Completed, policyComplianceStatus: "COMPLIANT" }),
      ws({ id: "2", lastStatus: JobStatus.Completed, policyComplianceStatus: "NON_COMPLIANT" }),
      ws({ id: "3", lastStatus: JobStatus.Failed, policyComplianceStatus: "COMPLIANT" }),
    ];

    const result = filterWorkspaces(list, {
      status: JobStatus.Completed,
      policyStatus: PolicyComplianceFilter.Compliant,
      search: "",
      tagIds: [],
      projectId: null,
    });
    expect(result.map((w) => w.id)).toEqual(["1"]);
  });
});
