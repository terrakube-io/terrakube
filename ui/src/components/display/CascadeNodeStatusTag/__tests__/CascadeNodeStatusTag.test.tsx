import { render, screen } from "@testing-library/react";
import { RunCascadeNodeStatus } from "@/domain/types";
import { cascadeNodeStatusColors } from "@/modules/cascades/utils/cascadeNodeStatus";
import CascadeNodeStatusTag from "../CascadeNodeStatusTag";

describe("CascadeNodeStatusTag", () => {
  it("colours the tag through the tk-status-tag recipe, not Ant's color prop", () => {
    const { container } = render(<CascadeNodeStatusTag status={RunCascadeNodeStatus.Blocked} />);
    const tag = container.querySelector(".ant-tag") as HTMLElement;

    expect(tag).toHaveClass("tk-status-tag");
    expect(tag.style.backgroundColor).toBe("");
    expect(tag.style.getPropertyValue("--status-color")).toBe(cascadeNodeStatusColors[RunCascadeNodeStatus.Blocked]);
    expect(screen.getByText("Blocked")).toBeInTheDocument();
  });
});
