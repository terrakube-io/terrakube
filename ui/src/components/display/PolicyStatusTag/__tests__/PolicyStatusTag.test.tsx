import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import PolicyStatusTag from "../PolicyStatusTag";

describe("PolicyStatusTag", () => {
  it("renders COMPLIANT status badge", () => {
    render(<PolicyStatusTag status="COMPLIANT" />);
    expect(screen.getByText("Compliant")).toBeInTheDocument();
    expect(screen.getByTestId("policy-status-tag-compliant")).toBeInTheDocument();
  });

  it("renders NON-COMPLIANT status badge", () => {
    render(<PolicyStatusTag status="NON_COMPLIANT" />);
    expect(screen.getByText("Non-Compliant")).toBeInTheDocument();
    expect(screen.getByTestId("policy-status-tag-non-compliant")).toBeInTheDocument();
  });

  it("renders EXEMPTED status badge", () => {
    render(<PolicyStatusTag status="EXEMPTED" />);
    expect(screen.getByText("Exempted")).toBeInTheDocument();
    expect(screen.getByTestId("policy-status-tag-exempted")).toBeInTheDocument();
  });

  it("renders UNKNOWN status badge when status is null, undefined, or unknown", () => {
    const { rerender } = render(<PolicyStatusTag status={null} />);
    expect(screen.getByText("Unknown")).toBeInTheDocument();

    rerender(<PolicyStatusTag status={undefined} />);
    expect(screen.getByText("Unknown")).toBeInTheDocument();

    rerender(<PolicyStatusTag status="UNKNOWN" />);
    expect(screen.getByText("Unknown")).toBeInTheDocument();
  });

  it("renders clickable link when clickable is true and organizationId and workspaceId are provided", () => {
    const parentClickHandler = jest.fn();

    render(
      <MemoryRouter>
        <div onClick={parentClickHandler}>
          <PolicyStatusTag
            status="COMPLIANT"
            organizationId="org-1"
            workspaceId="ws-1"
            clickable
          />
        </div>
      </MemoryRouter>
    );

    const link = screen.getByRole("link", { name: /Policy compliance: Compliant/i });
    expect(link).toBeInTheDocument();
    expect(link).toHaveAttribute("href", "/organizations/org-1/workspaces/ws-1/settings/policies");

    // Clicking the link should stop propagation to parent container
    fireEvent.click(link);
    expect(parentClickHandler).not.toHaveBeenCalled();
  });

  it("renders non-interactive tag when clickable is false", () => {
    render(
      <MemoryRouter>
        <PolicyStatusTag
          status="COMPLIANT"
          organizationId="org-1"
          workspaceId="ws-1"
          clickable={false}
        />
      </MemoryRouter>
    );

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("Compliant")).toBeInTheDocument();
  });
});
