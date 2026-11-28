import { render, screen } from "@testing-library/react";
import EstimatedSpendRollupCard from "../EstimatedSpendRollupCard";
import { WorkspaceListItem } from "@/modules/workspaces/types";

describe("EstimatedSpendRollupCard", () => {
  it("renders nothing when no workspace has cost data", () => {
    const { container } = render(
      <EstimatedSpendRollupCard
        workspaces={[
          {
            id: "ws-1",
            name: "unpriced",
            iacType: "terraform",
            source: "",
            currentMonthlyCost: null,
          },
        ]}
      />
    );

    expect(container.firstChild).toBeNull();
  });

  it("renders separate currency totals and project breakdown without cross-currency summing", () => {
    const workspaces: WorkspaceListItem[] = [
      {
        id: "ws-1",
        name: "us-app",
        iacType: "terraform",
        source: "",
        projectId: "p1",
        projectName: "Payments",
        currentMonthlyCost: 150.25,
        costCurrency: "USD",
      },
      {
        id: "ws-2",
        name: "eu-app",
        iacType: "terraform",
        source: "",
        projectId: "p2",
        projectName: "Compliance",
        currentMonthlyCost: 80,
        costCurrency: "EUR",
      },
      {
        id: "ws-3",
        name: "no-cost",
        iacType: "terraform",
        source: "",
        projectId: "p2",
        projectName: "Compliance",
        currentMonthlyCost: null,
      },
    ];

    render(
      <EstimatedSpendRollupCard
        workspaces={workspaces}
        title="Estimated Monthly Spend (Current Page)"
        showProjectBreakdown
      />
    );

    expect(screen.getByText("Estimated Monthly Spend (Current Page)")).toBeInTheDocument();
    expect(screen.getByText(/2 of 3 workspaces priced/i)).toBeInTheDocument();
    expect(screen.getByText("$150.25/mo")).toBeInTheDocument();
    expect(screen.getByText("€80.00/mo")).toBeInTheDocument();
    expect(screen.getByText("USD (1 workspace)")).toBeInTheDocument();
    expect(screen.getByText("EUR (1 workspace)")).toBeInTheDocument();
    expect(screen.getByText("Payments: $150.25/mo")).toBeInTheDocument();
    expect(screen.getByText("Compliance: €80.00/mo")).toBeInTheDocument();
    expect(screen.getByText(/cost sorting compares raw amounts without FX conversion/i)).toBeInTheDocument();
  });

  it("omits the multi-currency note when only a single currency is present", () => {
    render(
      <EstimatedSpendRollupCard
        workspaces={[
          {
            id: "ws-1",
            name: "us-app",
            iacType: "terraform",
            source: "",
            currentMonthlyCost: 150.25,
            costCurrency: "USD",
          },
        ]}
      />
    );

    expect(screen.getByText("$150.25/mo")).toBeInTheDocument();
    expect(screen.queryByText(/cost sorting compares raw amounts without FX conversion/i)).not.toBeInTheDocument();
  });

  it("explains that unpriced workspaces are excluded when cost sort is active", () => {
    const { rerender } = render(
      <EstimatedSpendRollupCard
        workspaces={[
          {
            id: "ws-1",
            name: "us-app",
            iacType: "terraform",
            source: "",
            currentMonthlyCost: 150.25,
            costCurrency: "USD",
          },
        ]}
        costSortActive
      />
    );

    expect(
      screen.getByText(/Showing only workspaces with a cost estimate · 1 priced on this page/i)
    ).toBeInTheDocument();

    rerender(<EstimatedSpendRollupCard workspaces={[]} costSortActive />);
    expect(screen.getByText("Showing only workspaces with a cost estimate")).toBeInTheDocument();
  });
});
