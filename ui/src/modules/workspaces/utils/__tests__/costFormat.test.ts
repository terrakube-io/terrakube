import {
  aggregateSpendByCurrency,
  COST_ESTIMATION_DISCLAIMER,
  formatCostDelta,
  formatCurrency,
  formatMonthlyCost,
  parseCostAmount,
} from "../costFormat";
import { WorkspaceListItem } from "@/modules/workspaces/types";

describe("costFormat utilities", () => {
  it("parses numeric and string amounts safely", () => {
    expect(parseCostAmount(42.5)).toBe(42.5);
    expect(parseCostAmount("123.45")).toBe(123.45);
    expect(parseCostAmount(null)).toBeNull();
    expect(parseCostAmount(undefined)).toBeNull();
    expect(parseCostAmount("")).toBeNull();
    expect(parseCostAmount("not-a-number")).toBeNull();
  });

  it("formats USD and non-USD currencies and returns em-dash for null", () => {
    expect(formatCurrency(1234.5, "USD")).toBe("$1,234.50");
    expect(formatCurrency(89.1, "EUR")).toBe("€89.10");
    expect(formatCurrency(null, "USD")).toBe("—");
    expect(formatMonthlyCost(73, "USD")).toBe("$73.00/mo");
    expect(formatMonthlyCost(undefined)).toBe("—");
  });

  it("formats positive, negative, and zero deltas with percentage when baseline is non-zero", () => {
    const increase = formatCostDelta("42.50", "120.00", "USD");
    expect(increase).toEqual({
      text: "+$42.50/mo (+35.4%)",
      direction: "increase",
      percentText: "+35.4%",
    });

    const decrease = formatCostDelta("-10", "100", "USD");
    expect(decrease).toEqual({
      text: "-$10.00/mo (-10.0%)",
      direction: "decrease",
      percentText: "-10.0%",
    });

    const zero = formatCostDelta("0.00", "50.00", "USD");
    expect(zero).toEqual({
      text: "$0.00/mo",
      direction: "neutral",
    });

    const missingPast = formatCostDelta("15.00", null, "USD");
    expect(missingPast).toEqual({
      text: "+$15.00/mo",
      direction: "increase",
    });
  });

  it("aggregates spend grouped per currency without mixing currencies and groups by project", () => {
    const workspaces: WorkspaceListItem[] = [
      {
        id: "w1",
        name: "prod-us",
        iacType: "terraform",
        source: "",
        projectId: "p1",
        projectName: "Core Platform",
        currentMonthlyCost: 200,
        costCurrency: "USD",
      },
      {
        id: "w2",
        name: "staging-us",
        iacType: "terraform",
        source: "",
        projectId: "p1",
        projectName: "Core Platform",
        currentMonthlyCost: 50.5,
        costCurrency: "USD",
      },
      {
        id: "w3",
        name: "prod-eu",
        iacType: "terraform",
        source: "",
        projectId: "p2",
        projectName: "EU Edge",
        currentMonthlyCost: 110,
        costCurrency: "EUR",
      },
      {
        id: "w4",
        name: "unpriced-ws",
        iacType: "terraform",
        source: "",
        projectId: "p2",
        projectName: "EU Edge",
        currentMonthlyCost: null,
        costCurrency: null,
      },
    ];

    const summary = aggregateSpendByCurrency(workspaces);
    expect(summary.totalPricedCount).toBe(3);
    expect(summary.totalWorkspaces).toBe(4);
    expect(summary.currencyTotals).toEqual([
      { currency: "USD", totalMonthlyCost: 250.5, pricedCount: 2 },
      { currency: "EUR", totalMonthlyCost: 110, pricedCount: 1 },
    ]);
    expect(summary.projectTotals).toHaveLength(2);
    expect(COST_ESTIMATION_DISCLAIMER).toContain("Estimated pre-deployment monthly cost");
  });
});
