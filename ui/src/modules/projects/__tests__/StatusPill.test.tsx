import { render, screen } from "@testing-library/react";
import StatusPill from "../StatusPill";

describe("StatusPill", () => {
  it("is a status tag of the workspace list, with the icon of its tone", () => {
    const { container } = render(<StatusPill tone="danger">3 hard</StatusPill>);

    expect(screen.getByText("3 hard").closest(".tk-status-tag")).not.toBeNull();
    expect(container.querySelector(".anticon-close-circle")).not.toBeNull();
  });

  it("leaves the icon out for a bare number", () => {
    const { container } = render(
      <StatusPill tone="warning" icon={false}>
        2
      </StatusPill>
    );

    expect(container.querySelector(".anticon")).toBeNull();
  });

  it("is grey, not green or red, for a status that says nothing yet", () => {
    const { container } = render(<StatusPill tone="neutral">Not evaluated</StatusPill>);

    const tag = container.querySelector(".tk-status-tag") as HTMLElement;
    expect(tag.style.getPropertyValue("--status-color")).toBe("");
  });
});
