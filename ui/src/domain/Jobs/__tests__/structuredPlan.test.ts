import {
  getPlanChangeActionColor,
  getPlanChangeActionLabel,
  normalizeStructuredApplyOutput,
  normalizeStructuredOutputs,
  normalizeStructuredPlanOutput,
  normalizeUITemplates,
} from "../structuredPlan";

describe("structuredPlan helpers", () => {
  it("normalizes replacement actions from delete and create", () => {
    expect(getPlanChangeActionLabel(["delete", "create"])).toBe("replace");
    expect(getPlanChangeActionColor(["delete", "create"])).toBe("orange");
  });

  it("recognizes a clean import (actions: [no-op] + fallback \"import\") instead of collapsing it to no-op", () => {
    expect(getPlanChangeActionLabel(["no-op"], "import")).toBe("import");
  });

  it("still returns no-op for a genuine no-op with no import fallback", () => {
    expect(getPlanChangeActionLabel(["no-op"])).toBe("no-op");
    expect(getPlanChangeActionLabel(["no-op"], "no-op")).toBe("no-op");
  });

  it("normalizes a plan change with an import fallback all the way through", () => {
    const output = normalizeStructuredPlanOutput({
      "step-1": [
        {
          address: "random_string.imported_example",
          actions: ["no-op"],
          action: "import",
          importing: { id: "AbcXyz1234567890" },
          before: { id: "AbcXyz1234567890" },
          after: { id: "AbcXyz1234567890" },
        },
      ],
    });

    expect(output["step-1"][0].action).toBe("import");
    expect(output["step-1"][0].importing).toEqual({ id: "AbcXyz1234567890" });
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

  describe("normalizeStructuredApplyOutput", () => {
    it("normalizes apply changes and defaults an unrecognized status to pending", () => {
      const result = normalizeStructuredApplyOutput({
        "step-1": [
          {
            address: "aws_instance.example",
            action: "create",
            actions: ["create"],
            after: { id: "i-123" },
            status: "applied",
          },
          {
            address: "aws_instance.other",
            action: "update",
            actions: ["update"],
            status: "not-a-real-status",
          },
        ],
      });

      expect(result["step-1"]).toHaveLength(2);
      expect(result["step-1"][0].status).toBe("applied");
      expect(result["step-1"][1].status).toBe("pending");
    });

    it("returns an empty object for non-record input", () => {
      expect(normalizeStructuredApplyOutput(null)).toEqual({});
      expect(normalizeStructuredApplyOutput(undefined)).toEqual({});
    });
  });

  describe("normalizeStructuredOutputs", () => {
    it("normalizes terraform outputs and drops entries without a name", () => {
      const result = normalizeStructuredOutputs({
        "step-1": [
          { name: "random_value", value: "sad-otter", sensitive: false, type: "string" },
          { name: "random_password_result", value: null, sensitive: true, type: "string" },
          { value: "no-name-here", sensitive: false },
        ],
      });

      expect(result["step-1"]).toHaveLength(2);
      expect(result["step-1"][0]).toEqual({
        name: "random_value",
        value: "sad-otter",
        sensitive: false,
        type: "string",
      });
      expect(result["step-1"][1].sensitive).toBe(true);
      expect(result["step-1"][1].value).toBeNull();
    });

    it("returns an empty object for non-record input", () => {
      expect(normalizeStructuredOutputs(null)).toEqual({});
      expect(normalizeStructuredOutputs(undefined)).toEqual({});
    });
  });
});
