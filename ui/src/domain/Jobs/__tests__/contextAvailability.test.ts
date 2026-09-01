import {
  getReconciliationCounts,
  parseContextAvailability,
  recordReconciliationResult,
  resetReconciliationCounts,
} from "../contextAvailability";

describe("parseContextAvailability", () => {
  it("maps a 5xx status to unavailable regardless of body", () => {
    expect(parseContextAvailability({ planStructuredOutput: {} }, 503)).toBe("unavailable");
  });

  it("reads an explicit structuredOutputStatus.state", () => {
    expect(parseContextAvailability({ structuredOutputStatus: { state: "PERSISTED" } })).toBe("persisted");
    expect(parseContextAvailability({ structuredOutputStatus: { state: "PENDING" } })).toBe("pending");
    expect(parseContextAvailability({ structuredOutputStatus: { state: "UNAVAILABLE" } })).toBe("unavailable");
  });

  it("treats a bare {} as pending, not an empty plan", () => {
    expect(parseContextAvailability({})).toBe("pending");
  });

  it("treats legacy content with no status marker as persisted", () => {
    expect(parseContextAvailability({ planStructuredOutput: { "step-1": [] } })).toBe("persisted");
    expect(parseContextAvailability({ applyStructuredOutput: { "step-1": [] } })).toBe("persisted");
  });

  it("handles nullish / non-object data", () => {
    expect(parseContextAvailability(undefined)).toBe("pending");
    expect(parseContextAvailability(null)).toBe("pending");
    expect(parseContextAvailability("nope")).toBe("pending");
  });
});

describe("reconciliation counters", () => {
  beforeEach(() => resetReconciliationCounts());

  it("records outcomes", () => {
    recordReconciliationResult("persisted");
    recordReconciliationResult("persisted");
    recordReconciliationResult("expired");
    expect(getReconciliationCounts()).toEqual({ persisted: 2, unavailable: 0, expired: 1 });
  });
});
