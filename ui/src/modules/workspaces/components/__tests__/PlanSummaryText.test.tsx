import { render, screen } from "@testing-library/react";
import { axe, toHaveNoViolations } from "jest-axe";
import PlanSummaryText from "../PlanSummaryText";

expect.extend(toHaveNoViolations);

describe("PlanSummaryText", () => {
  it("shows the counts and says them in words for a screen reader", () => {
    render(<PlanSummaryText add={3} change={1} destroy={0} />);

    expect(screen.getByText("3 to add, 1 to change, 0 to destroy")).toBeInTheDocument();
    expect(screen.getByText("+3").parentElement).toHaveTextContent("+3 ~1 -0");
  });

  it("says No changes for an empty plan", () => {
    render(<PlanSummaryText add={0} change={0} destroy={0} />);

    expect(screen.getByText("No changes")).toBeInTheDocument();
  });

  it("shows nothing when the run has no summary", () => {
    const { container } = render(<PlanSummaryText add={null} change={undefined} destroy={2} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("has no accessibility violations", async () => {
    const { container } = render(
      <ul>
        <li>
          <PlanSummaryText add={3} change={1} destroy={0} />
        </li>
        <li>
          <PlanSummaryText add={0} change={0} destroy={0} />
        </li>
      </ul>
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it("quietens the counts that are zero, so only the real changes stand out", () => {
    const { container } = render(<PlanSummaryText add={0} change={2} destroy={0} />);

    expect(container.querySelectorAll(".plan-summary-zero")).toHaveLength(2);
    expect(container.querySelector(".plan-summary-change")).toHaveTextContent("~2");
    expect(container.querySelector(".plan-summary-add")).toBeNull();
  });
});
