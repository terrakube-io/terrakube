import {
  describeOutputCompleteness,
  getOutputCompleteness,
  isOutputSettled,
  isStructuredOutputStep,
  producesStructuredOutput,
  parseDeliveryState,
} from "../outputDelivery";

const base = {
  stepId: "plan-1",
  stepStatus: "completed",
  contextAvailability: "persisted" as const,
  reconciling: false,
};

describe("getOutputCompleteness", () => {
  it("is in-progress while the step is still running, whatever was stored", () => {
    expect(getOutputCompleteness({ ...base, stepStatus: "running", deliveryState: "ACKED", marker: undefined })).toBe(
      "in-progress"
    );
  });

  it("is final once the final output was acknowledged or recovered", () => {
    expect(getOutputCompleteness({ ...base, deliveryState: "ACKED" })).toBe("final");
    expect(getOutputCompleteness({ ...base, deliveryState: "RECOVERED" })).toBe("final");
  });

  it("says the recovery stalled, not that it is under way, once the page stopped checking", () => {
    expect(getOutputCompleteness({ ...base, deliveryState: "SPOOLED", recoveryGaveUp: true })).toBe("recovery-stalled");
    expect(describeOutputCompleteness("recovery-stalled")).toMatchObject({
      tone: "warning",
      label: "Recovery is taking longer than expected",
    });
    expect(isOutputSettled("recovery-stalled")).toBe(true);
  });

  it("is recovering while the output sits in storage waiting for the API", () => {
    expect(getOutputCompleteness({ ...base, deliveryState: "SPOOLED" })).toBe("recovering");
  });

  it("is expired when recovery gave up", () => {
    expect(getOutputCompleteness({ ...base, deliveryState: "EXPIRED" })).toBe("expired");
  });

  it("lets an explicit delivery state win over the stored marker", () => {
    const progressMarker = { stepId: "plan-1", finalSnapshot: false };
    expect(getOutputCompleteness({ ...base, deliveryState: "ACKED", marker: progressMarker })).toBe("final");
    expect(
      getOutputCompleteness({ ...base, deliveryState: "EXPIRED", marker: { ...progressMarker, finalSnapshot: true } })
    ).toBe("expired");
  });

  it("treats a final marker for this step as final when the delivery state is not tracked", () => {
    expect(
      getOutputCompleteness({
        ...base,
        deliveryState: "NOT_DURABLE",
        marker: { stepId: "plan-1", finalSnapshot: true },
      })
    ).toBe("final");
  });

  it("ignores a marker that names another step", () => {
    expect(
      getOutputCompleteness({
        ...base,
        deliveryState: "NOT_DURABLE",
        marker: { stepId: "apply-1", finalSnapshot: true },
      })
    ).toBe("unknown");
  });

  it("is awaiting-final while re-checking and the last stored write for the step was progress", () => {
    expect(
      getOutputCompleteness({
        ...base,
        reconciling: true,
        deliveryState: "NOT_DURABLE",
        marker: { stepId: "plan-1", finalSnapshot: false },
      })
    ).toBe("awaiting-final");
  });

  it("is awaiting-final while re-checking and nothing has been stored yet", () => {
    expect(getOutputCompleteness({ ...base, reconciling: true, contextAvailability: "pending" })).toBe(
      "awaiting-final"
    );
  });

  it("is partial once the re-checking stopped and only a progress snapshot was ever stored", () => {
    expect(
      getOutputCompleteness({
        ...base,
        deliveryState: "NOT_DURABLE",
        marker: { stepId: "plan-1", finalSnapshot: false },
      })
    ).toBe("partial");
  });

  it("is unavailable when the context cannot be read and nothing confirms the step", () => {
    expect(getOutputCompleteness({ ...base, contextAvailability: "unavailable" })).toBe("unavailable");
  });

  it("does not let an unreadable context hide an acknowledged delivery", () => {
    expect(getOutputCompleteness({ ...base, contextAvailability: "unavailable", deliveryState: "ACKED" })).toBe(
      "final"
    );
  });

  it("is unknown for a run from before delivery was tracked, so it looks exactly as it did", () => {
    expect(getOutputCompleteness({ ...base, deliveryState: undefined })).toBe("unknown");
    expect(getOutputCompleteness({ ...base, deliveryState: "NOT_DURABLE" })).toBe("unknown");
  });

  it("is final for an explicit no-change plan even when the context is unreadable", () => {
    expect(getOutputCompleteness({ ...base, contextAvailability: "unavailable", explicitNoChange: true })).toBe(
      "final"
    );
  });
});

describe("isOutputSettled", () => {
  it("stops re-checking only when nothing more is expected", () => {
    expect(isOutputSettled("final")).toBe(true);
    expect(isOutputSettled("expired")).toBe(true);
    expect(isOutputSettled("unknown")).toBe(true);
    expect(isOutputSettled("awaiting-final")).toBe(false);
    expect(isOutputSettled("recovering")).toBe(false);
    expect(isOutputSettled("unavailable")).toBe(false);
  });
});

describe("describeOutputCompleteness", () => {
  it("has no notice for states that need none", () => {
    expect(describeOutputCompleteness("unknown")).toBeNull();
    expect(describeOutputCompleteness("in-progress")).toBeNull();
  });

  it("tells partial, recovering, expired and final apart", () => {
    const labels = (["partial", "recovering", "expired", "final", "unavailable", "awaiting-final"] as const).map(
      (state) => describeOutputCompleteness(state)?.label
    );
    expect(new Set(labels).size).toBe(labels.length);
    expect(describeOutputCompleteness("expired")?.tone).toBe("error");
    expect(describeOutputCompleteness("final")?.tone).toBe("success");
  });
});

describe("parseDeliveryState / isStructuredOutputStep", () => {
  it("keeps only known delivery states", () => {
    expect(parseDeliveryState("ACKED")).toBe("ACKED");
    expect(parseDeliveryState("SOMETHING_ELSE")).toBeUndefined();
    expect(parseDeliveryState(undefined)).toBeUndefined();
  });

  it("recognises plan, apply and destroy steps by name", () => {
    expect(isStructuredOutputStep("Terraform Plan")).toBe(true);
    expect(isStructuredOutputStep("terraform apply")).toBe(true);
    expect(isStructuredOutputStep("Destroy")).toBe(true);
    expect(isStructuredOutputStep("Approval")).toBe(false);
    expect(isStructuredOutputStep("Planning notes")).toBe(false);
  });
});

describe("producesStructuredOutput", () => {
  const unnamed = { id: "step-1", name: "Running Step100" };

  it("recognises a step by its name", () => {
    expect(producesStructuredOutput({ id: "s", name: "Terraform Plan" })).toBe(true);
    expect(producesStructuredOutput({ id: "s", name: "Approval" })).toBe(false);
  });

  it("recognises an unnamed step by the delivery outcome the executor recorded for it", () => {
    for (const deliveryState of ["ACKED", "SPOOLED", "RECOVERED", "EXPIRED"] as const) {
      expect(producesStructuredOutput({ ...unnamed, deliveryState })).toBe(true);
    }
  });

  it("does not take the default NOT_DURABLE state as evidence", () => {
    expect(producesStructuredOutput({ ...unnamed, deliveryState: "NOT_DURABLE" })).toBe(false);
    expect(producesStructuredOutput(unnamed)).toBe(false);
  });

  it("recognises an unnamed step that the stored progress marker names", () => {
    expect(
      producesStructuredOutput({ ...unnamed, deliveryState: "NOT_DURABLE" }, { stepId: "step-1", finalSnapshot: false })
    ).toBe(true);
    expect(
      producesStructuredOutput({ ...unnamed, deliveryState: "NOT_DURABLE" }, { stepId: "other", finalSnapshot: false })
    ).toBe(false);
  });
});
