import { isPlanOnlyTemplate } from "../templatePlanOnly";

const flow = (...types: string[]) =>
  ["flow:", ...types.map((type, index) => `  - type: "${type}"\n    step: ${100 * (index + 1)}`)].join("\n");

describe("isPlanOnlyTemplate", () => {
  it("accepts a template that only plans", () => {
    expect(isPlanOnlyTemplate(flow("terraformPlan"))).toBe(true);
    expect(isPlanOnlyTemplate(flow("terraformPlan", "terraformPlanDestroy"))).toBe(true);
  });

  it("refuses a template that applies, destroys, runs scripts or waits for approval", () => {
    expect(isPlanOnlyTemplate(flow("terraformPlan", "terraformApply"))).toBe(false);
    expect(isPlanOnlyTemplate(flow("terraformDestroy"))).toBe(false);
    expect(isPlanOnlyTemplate(flow("terraformPlan", "customScripts"))).toBe(false);
    expect(isPlanOnlyTemplate(flow("terraformPlan", "approval"))).toBe(false);
  });

  it("reads unquoted and single quoted types and trailing comments", () => {
    expect(isPlanOnlyTemplate("flow:\n  - type: terraformPlan # preview\n    step: 100")).toBe(true);
    expect(isPlanOnlyTemplate("flow:\n  - type: 'terraformApply'\n    step: 100")).toBe(false);
  });

  it("treats an empty, missing or unreadable template as not plan-only", () => {
    expect(isPlanOnlyTemplate(undefined)).toBe(false);
    expect(isPlanOnlyTemplate("")).toBe(false);
    expect(isPlanOnlyTemplate("flow: []")).toBe(false);
    expect(isPlanOnlyTemplate("not a template")).toBe(false);
  });
});
