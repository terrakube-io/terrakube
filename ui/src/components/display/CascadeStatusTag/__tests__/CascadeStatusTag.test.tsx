import { render, screen } from "@testing-library/react";
import { RunCascadeStatus } from "@/domain/types";
import { cascadeStatusColors } from "@/modules/cascades/utils/cascadeStatus";
import CascadeStatusTag from "../CascadeStatusTag";

describe("CascadeStatusTag", () => {
  it("colours the tag through the tk-status-tag recipe, not Ant's color prop", () => {
    const { container } = render(<CascadeStatusTag status={RunCascadeStatus.Degraded} />);
    const tag = container.querySelector(".ant-tag") as HTMLElement;

    expect(tag).toHaveClass("tk-status-tag");
    expect(tag.style.backgroundColor).toBe("");
    expect(tag.style.getPropertyValue("--status-color")).toBe(cascadeStatusColors[RunCascadeStatus.Degraded]);
    expect(screen.getByText("Degraded")).toBeInTheDocument();
  });
});
