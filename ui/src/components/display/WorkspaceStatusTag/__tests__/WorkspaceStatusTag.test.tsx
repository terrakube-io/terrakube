import { render, screen } from "@testing-library/react";
import { JobStatus } from "@/domain/types";
import { statusColors } from "@/modules/workspaces/utils/workspaceStatusColors";
import WorkspaceStatusTag from "../WorkspaceStatusTag";

describe("WorkspaceStatusTag", () => {
  it("colours the tag through the tk-status-tag recipe, not Ant's color prop", () => {
    const { container } = render(<WorkspaceStatusTag status={JobStatus.Failed} />);
    const tag = container.querySelector(".ant-tag") as HTMLElement;

    // A color prop would make Ant paint the tag itself and bypass the contrast-safe CSS.
    expect(tag).toHaveClass("tk-status-tag");
    expect(tag).not.toHaveClass("ant-tag-has-color");
    expect(tag.style.getPropertyValue("--status-color")).toBe(statusColors[JobStatus.Failed]);
    expect(screen.getByText(/failed/i)).toBeInTheDocument();
  });
});
