import { render, screen } from "@testing-library/react";
import { StateChanges } from "../StateChanges";
import { diffState } from "../stateDiff";

describe("StateChanges", () => {
  it("strikes removed values and marks added ones", () => {
    const diff = diffState({ serial: 1, gone: "a", list: [] }, { serial: 2, list: [{ id: "x" }] })!;
    const { container } = render(<StateChanges diff={diff} />);

    expect(container.querySelector("del")).toHaveTextContent("1");
    expect(container.querySelector("ins")).toHaveTextContent("2");
    expect(screen.getByText('"gone"').closest("del")).not.toBeNull();
    expect(screen.getByText("0:").closest("ins")).not.toBeNull();
  });

  it("shows sensitive output values masked", () => {
    const diff = diffState(
      { outputs: { token: { value: "old-secret", sensitive: true } } },
      { outputs: { token: { value: "new-secret", sensitive: true } } }
    )!;
    const { container } = render(<StateChanges diff={diff} />);

    expect(container).not.toHaveTextContent("secret");
    expect(container.querySelector("del")).toHaveTextContent("(sensitive value)");
    expect(container.querySelector("ins")).toHaveTextContent("(sensitive value)");
  });
});
