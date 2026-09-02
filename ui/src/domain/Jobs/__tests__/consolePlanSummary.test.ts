import { parseConsolePlanSummary } from "../consolePlanSummary";

describe("parseConsolePlanSummary", () => {
  it("detects a plan that produced changes", () => {
    const summary = parseConsolePlanSummary("...\nPlan: 2 to add, 1 to change, 0 to destroy.\n");
    expect(summary).toMatchObject({ hasPlan: true, declaresChanges: true, add: 2, change: 1, destroy: 0 });
  });

  it("detects an explicit no-changes plan", () => {
    const summary = parseConsolePlanSummary("No changes. Your infrastructure matches the configuration.");
    expect(summary).toMatchObject({ hasPlan: true, declaresNoChanges: true, declaresChanges: false });
  });

  it("treats a zero-count plan line as no changes", () => {
    const summary = parseConsolePlanSummary("Plan: 0 to add, 0 to change, 0 to destroy.");
    expect(summary).toMatchObject({ hasPlan: true, declaresChanges: false, declaresNoChanges: true });
  });

  it("tolerates ANSI colour codes", () => {
    const summary = parseConsolePlanSummary("[1mPlan:[0m 1 to add, 0 to change, 0 to destroy.");
    expect(summary.declaresChanges).toBe(true);
  });

  it("detects apply completion with resource counts", () => {
    const summary = parseConsolePlanSummary("Apply complete! Resources: 3 added, 0 changed, 1 destroyed.");
    expect(summary).toMatchObject({ hasPlan: true, declaresChanges: true });
  });

  it("returns all-false for unrelated output", () => {
    expect(parseConsolePlanSummary("Initializing the backend...")).toMatchObject({
      hasPlan: false,
      declaresChanges: false,
      declaresNoChanges: false,
    });
  });

  it("handles empty / nullish input", () => {
    expect(parseConsolePlanSummary(undefined).hasPlan).toBe(false);
    expect(parseConsolePlanSummary("").hasPlan).toBe(false);
  });
});
