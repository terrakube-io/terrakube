import { parseClosedByTerrakube } from "../closedByTerrakube";

describe("parseClosedByTerrakube", () => {
  it("returns the recorded reason", () => {
    expect(
      parseClosedByTerrakube("Closed by Terrakube: Approval was not given within 48 hours, the run was cancelled.")
    ).toBe("Approval was not given within 48 hours, the run was cancelled.");
  });

  it("finds the reason after other output", () => {
    expect(
      parseClosedByTerrakube("Step 1 completed\nClosed by Terrakube: The run did not finish within 6 hours.")
    ).toBe("The run did not finish within 6 hours.");
  });

  it.each([undefined, null, "", "Step 1 completed", "Closed by Terrakube: "])("returns null for %p", (output) => {
    expect(parseClosedByTerrakube(output)).toBeNull();
  });
});
