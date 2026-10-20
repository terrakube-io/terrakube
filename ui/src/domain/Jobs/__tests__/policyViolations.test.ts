import { hasBlockingPolicyViolations } from "../policyViolations";

describe("hasBlockingPolicyViolations", () => {
  it("is false without an evaluation or without violations", () => {
    expect(hasBlockingPolicyViolations(undefined)).toBe(false);
    expect(hasBlockingPolicyViolations({ softMandatoryViolations: 0, hardMandatoryViolations: 0 } as never)).toBe(
      false
    );
    expect(hasBlockingPolicyViolations({ results: [{ softMandatoryViolations: 0 }] } as never)).toBe(false);
  });

  it.each([
    ["soft at the top", { softMandatoryViolations: 1 }],
    ["hard at the top", { hardMandatoryViolations: 1 }],
    ["soft in one policy set", { results: [{}, { softMandatoryViolations: 2 }] }],
    ["hard in one policy set", { results: [{}, { hardMandatoryViolations: 1 }] }],
  ])("is true for %s violations", (_name, evaluation) => {
    expect(hasBlockingPolicyViolations(evaluation as never)).toBe(true);
  });
});
