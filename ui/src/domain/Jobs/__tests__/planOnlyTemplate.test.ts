import { configuredBranch, isPlanOnlyTemplate } from "../planOnlyTemplate";

const tcl = (yaml: string) => window.btoa(yaml);

describe("isPlanOnlyTemplate", () => {
  it("is true when every step plans", () => {
    expect(isPlanOnlyTemplate(tcl('flow:\n  - type: "terraformPlan"\n    name: "Plan"\n    step: 100\n'))).toBe(true);
    expect(
      isPlanOnlyTemplate(
        tcl('flow:\n  - type: "terraformPlan"\n    step: 100\n  - type: "terraformPlanDestroy"\n    step: 200\n')
      )
    ).toBe(true);
  });

  it("is false when a step applies, destroys or runs scripts", () => {
    expect(
      isPlanOnlyTemplate(
        tcl('flow:\n  - type: "terraformPlan"\n    step: 100\n  - type: "terraformApply"\n    step: 200')
      )
    ).toBe(false);
    expect(isPlanOnlyTemplate(tcl('flow:\n  - type: "terraformDestroy"\n    step: 100\n'))).toBe(false);
    expect(isPlanOnlyTemplate(tcl('flow:\n  - type: "customScripts"\n    step: 100\n'))).toBe(false);
  });

  it("is false for a template that cannot be read", () => {
    expect(isPlanOnlyTemplate(undefined)).toBe(false);
    expect(isPlanOnlyTemplate("")).toBe(false);
    expect(isPlanOnlyTemplate("not base64 !!")).toBe(false);
    expect(isPlanOnlyTemplate(tcl("flow: []"))).toBe(false);
  });
});

describe("configuredBranch", () => {
  it("uses the first entry and drops a refs/heads prefix", () => {
    expect(configuredBranch("main")).toBe("main");
    expect(configuredBranch("main, develop")).toBe("main");
    expect(configuredBranch("refs/heads/main")).toBe("main");
    expect(configuredBranch(undefined)).toBe("");
  });
});
