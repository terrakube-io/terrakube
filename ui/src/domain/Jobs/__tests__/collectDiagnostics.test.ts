import { collectRunDiagnostics, countBySeverity } from "../collectDiagnostics";
import { ApplyChange, Diagnostic, PlanChange } from "../structuredPlan";

const steps = [
  { id: "plan-1", stepNumber: 1, name: "Plan" },
  { id: "apply-1", stepNumber: 2, name: "Apply" },
];

const warning = (summary: string, extra: Partial<Diagnostic> = {}): Diagnostic => ({
  severity: "warning",
  summary,
  ...extra,
});
const error = (summary: string, extra: Partial<Diagnostic> = {}): Diagnostic => ({
  severity: "error",
  summary,
  ...extra,
});

const planChange = (address: string, diagnostics: Diagnostic[], action = "no-op"): PlanChange => ({
  address,
  action,
  actions: [action],
  diagnostics,
});
const applyChange = (address: string, diagnostics: Diagnostic[]): ApplyChange => ({
  address,
  action: "create",
  actions: ["create"],
  status: "errored",
  diagnostics,
});

describe("collectRunDiagnostics", () => {
  it("collects job-level and resource-level diagnostics from every step, with no filter applied", () => {
    const result = collectRunDiagnostics({
      steps,
      planStructuredOutput: { "plan-1": [planChange("aws_vpc.main", [warning("Deprecated argument")])] },
      applyStructuredOutput: { "apply-1": [applyChange("aws_instance.web", [error("Quota exceeded")])] },
      jobDiagnostics: { "plan-1": [warning("Unused variable", { location: "main.tf:3" })] },
    });

    // Errors first; otherwise in the order the steps report them.
    expect(result.map((d) => d.summary)).toEqual(["Quota exceeded", "Unused variable", "Deprecated argument"]);
    expect(result.find((d) => d.summary === "Quota exceeded")?.address).toBe("aws_instance.web");
    expect(result.find((d) => d.summary === "Unused variable")?.location).toBe("main.tf:3");
    expect(result.find((d) => d.summary === "Unused variable")?.address).toBeUndefined();
  });

  it("keeps a diagnostic on a no-op resource, which has no row in the resource list", () => {
    const result = collectRunDiagnostics({
      steps,
      planStructuredOutput: {
        "plan-1": [planChange("aws_s3_bucket.logs", [warning("Bucket ACL is deprecated")], "no-op")],
      },
      applyStructuredOutput: {},
      jobDiagnostics: {},
    });

    expect(result).toHaveLength(1);
    expect(result[0].address).toBe("aws_s3_bucket.logs");
  });

  it("collapses a diagnostic repeated by the plan and the apply of the same run into one", () => {
    const same = warning("Deprecated argument", { detail: "Use 'x' instead." });
    const result = collectRunDiagnostics({
      steps,
      planStructuredOutput: { "plan-1": [planChange("aws_vpc.main", [same])] },
      applyStructuredOutput: { "apply-1": [applyChange("aws_vpc.main", [same])] },
      jobDiagnostics: {},
    });

    expect(result).toHaveLength(1);
    expect(result[0].stepNames).toEqual(["Plan", "Apply"]);
  });

  it("collapses a diagnostic that was stored twice in one step", () => {
    const result = collectRunDiagnostics({
      steps,
      planStructuredOutput: {},
      applyStructuredOutput: {},
      jobDiagnostics: { "plan-1": [warning("Unused variable"), warning("Unused variable")] },
    });

    expect(result).toHaveLength(1);
  });

  it("keeps the same text about different resources apart", () => {
    const result = collectRunDiagnostics({
      steps,
      planStructuredOutput: {
        "plan-1": [
          planChange("aws_vpc.a", [warning("Deprecated argument")]),
          planChange("aws_vpc.b", [warning("Deprecated argument")]),
        ],
      },
      applyStructuredOutput: {},
      jobDiagnostics: {},
    });

    expect(result.map((d) => d.address)).toEqual(["aws_vpc.a", "aws_vpc.b"]);
  });

  it("keeps the same text at different source locations apart", () => {
    const result = collectRunDiagnostics({
      steps,
      planStructuredOutput: {},
      applyStructuredOutput: {},
      jobDiagnostics: {
        "plan-1": [
          warning("Deprecated variable", { location: "a.tf:1" }),
          warning("Deprecated variable", { location: "b.tf:9" }),
        ],
      },
    });

    expect(result.map((d) => d.location)).toEqual(["a.tf:1", "b.tf:9"]);
  });

  it("keeps an error and a warning with the same text apart", () => {
    const result = collectRunDiagnostics({
      steps,
      planStructuredOutput: {},
      applyStructuredOutput: {},
      jobDiagnostics: { "plan-1": [warning("Problem"), error("Problem")] },
    });

    expect(result).toHaveLength(2);
  });

  it("finds a step's data by step number as well as id", () => {
    const result = collectRunDiagnostics({
      steps,
      planStructuredOutput: {},
      applyStructuredOutput: {},
      jobDiagnostics: { "1": [error("By number")] },
    });

    expect(result.map((d) => d.summary)).toEqual(["By number"]);
  });

  it("is empty when nothing was captured", () => {
    expect(
      collectRunDiagnostics({ steps, planStructuredOutput: {}, applyStructuredOutput: {}, jobDiagnostics: {} })
    ).toEqual([]);
  });
});

describe("countBySeverity", () => {
  it("counts errors and warnings", () => {
    expect(
      countBySeverity([
        { key: "a", severity: "error", summary: "a", stepNames: [] },
        { key: "b", severity: "warning", summary: "b", stepNames: [] },
        { key: "c", severity: "warning", summary: "c", stepNames: [] },
      ])
    ).toEqual({ errors: 1, warnings: 2 });
  });
});

describe("collectRunDiagnostics with output that is not well formed", () => {
  it("skips a null or non-object diagnostic instead of failing", () => {
    const result = collectRunDiagnostics({
      steps,
      planStructuredOutput: {
        "plan-1": [planChange("aws_vpc.main", [null as unknown as Diagnostic, warning("Deprecated argument")])],
      },
      applyStructuredOutput: {},
      jobDiagnostics: { "plan-1": ["oops" as unknown as Diagnostic, error("Boom")] },
    });

    expect(result.map((diagnostic) => diagnostic.summary)).toEqual(["Boom", "Deprecated argument"]);
  });

  it("gives the same diagnostic the same key and different ones different keys", () => {
    const result = collectRunDiagnostics({
      steps,
      planStructuredOutput: {
        "plan-1": [planChange("a.one", [warning("Same")]), planChange("a.two", [warning("Same")])],
      },
      applyStructuredOutput: {},
      jobDiagnostics: {},
    });

    expect(result).toHaveLength(2);
    expect(new Set(result.map((diagnostic) => diagnostic.key)).size).toBe(2);
  });
});
