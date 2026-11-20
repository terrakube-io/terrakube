import { ProjectSummaryModel } from "@/domain/types";
import {
  attentionFilter,
  projectWorkspacesLink,
  readChoice,
  readPositiveInt,
  statusForHealth,
  withViewState,
} from "../projectLinks";

const base: ProjectSummaryModel = {
  id: "p",
  name: "p",
  workspaceCount: 5,
  completedCount: 0,
  failedCount: 0,
  runningCount: 0,
  queuedCount: 0,
  waitingApprovalCount: 0,
  neverRunCount: 0,
  nonCompliantCount: 0,
  hardViolationCount: 0,
  softViolationCount: 0,
  compliantCount: 0,
  exemptedCount: 0,
  queuedJobCount: 0,
  lockedCount: 0,
  cliDrivenCount: 0,
  vcsDrivenCount: 0,
  noRunStatusCount: 0,
  attentionCount: 0,
  teamCount: 0,
};

describe("projectWorkspacesLink", () => {
  it("links to the workspaces tab, filtered when asked", () => {
    expect(projectWorkspacesLink("o", "p")).toBe("/organizations/o/projects/p/workspaces");
    expect(projectWorkspacesLink("o", "p", { status: "failed" })).toBe(
      "/organizations/o/projects/p/workspaces?status=failed"
    );
    expect(projectWorkspacesLink("o", "p", { policy: "NON_COMPLIANT" })).toContain("policy=NON_COMPLIANT");
  });
});

describe("statusForHealth", () => {
  it("maps each part of the health bar to the status the list filters on", () => {
    expect(statusForHealth("waiting")).toBe("waitingApproval");
    expect(statusForHealth("queued")).toBe("pending");
    expect(statusForHealth("never")).toBe("NeverExecuted");
  });
});

describe("attentionFilter", () => {
  it("leads to the worst thing that needs attention", () => {
    expect(attentionFilter({ ...base, failedCount: 1, waitingApprovalCount: 2, nonCompliantCount: 3 })).toEqual({
      status: "failed",
    });
    expect(attentionFilter({ ...base, waitingApprovalCount: 2, nonCompliantCount: 3 })).toEqual({
      status: "waitingApproval",
    });
    expect(attentionFilter({ ...base, nonCompliantCount: 3 })).toEqual({ policy: "NON_COMPLIANT" });
    expect(attentionFilter(base)).toEqual({});
  });
});

describe("reading the query string", () => {
  it("accepts only known choices", () => {
    expect(readChoice(new URLSearchParams("chip=empty"), "chip", ["all", "empty"], "all")).toBe("empty");
    expect(readChoice(new URLSearchParams("chip=bogus"), "chip", ["all", "empty"], "all")).toBe("all");
    expect(readChoice(new URLSearchParams(""), "chip", ["all", "empty"], "all")).toBe("all");
  });

  it("accepts only positive whole numbers", () => {
    expect(readPositiveInt(new URLSearchParams("page=3"), "page", 1)).toBe(3);
    expect(readPositiveInt(new URLSearchParams("page=0"), "page", 1)).toBe(1);
    expect(readPositiveInt(new URLSearchParams("page=abc"), "page", 1)).toBe(1);
  });
});

describe("withViewState", () => {
  it("leaves defaults out so a plain link stays plain", () => {
    const next = withViewState(new URLSearchParams(), { chip: "all", page: 1, q: "" }, { chip: "all", page: 1 });

    expect(next.toString()).toBe("");
  });

  it("writes what differs from the defaults", () => {
    const next = withViewState(new URLSearchParams(), { chip: "empty", page: 2, q: "pay" }, { chip: "all", page: 1 });

    expect(next.get("chip")).toBe("empty");
    expect(next.get("page")).toBe("2");
    expect(next.get("q")).toBe("pay");
  });

  it("keeps parameters it does not own and returns the same object when nothing changed", () => {
    const current = new URLSearchParams("keep=1&chip=empty");

    expect(withViewState(current, { chip: "empty" }, { chip: "all" })).toBe(current);
    expect(withViewState(current, { chip: "all" }, { chip: "all" }).get("keep")).toBe("1");
  });

  it("removes a value that went back to its default", () => {
    const next = withViewState(new URLSearchParams("chip=empty"), { chip: "all" }, { chip: "all" });

    expect(next.has("chip")).toBe(false);
  });
});
