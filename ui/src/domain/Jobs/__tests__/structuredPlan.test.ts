import {
  getPlanChangeActionColor,
  getPlanChangeActionLabel,
  normalizeStructuredPlanOutput,
  normalizeUITemplates,
} from "../structuredPlan";

describe("structuredPlan helpers", () => {
  it("normalizes replacement actions from delete and create", () => {
    expect(getPlanChangeActionLabel(["delete", "create"])).toBe("replace");
    expect(getPlanChangeActionColor(["delete", "create"])).toBe("orange");
  });

  it("filters malformed structured plan entries and keeps sensitive metadata", () => {
    const output = normalizeStructuredPlanOutput({
      "step-1": [
        {
          address: "aws_instance.example",
          actions: ["update"],
          beforeSensitive: { password: true },
          changedSensitive: { password: true },
          afterSensitive: { password: true },
        },
        "invalid-entry",
      ],
      "step-2": "invalid-step",
    });

    expect(output["step-1"]).toHaveLength(1);
    expect(output["step-1"][0]).toEqual(
      expect.objectContaining({
        address: "aws_instance.example",
        action: "update",
        beforeSensitive: { password: true },
        changedSensitive: { password: true },
        afterSensitive: { password: true },
      })
    );
    expect(output["step-2"]).toBeUndefined();
  });

  it("normalizes ui templates and drops non-string values", () => {
    const templates = normalizeUITemplates({
      "step-1": "<div>template</div>",
      "step-2": 123,
    });

    expect(templates).toEqual({
      "step-1": "<div>template</div>",
    });
  });
});
