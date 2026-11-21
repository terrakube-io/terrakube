import { WorkspaceListItem } from "@/modules/workspaces/types";

export const COST_ESTIMATION_DISCLAIMER =
  "Estimated pre-deployment monthly cost based on Terraform plan and on-demand list pricing. Does not reflect actual cloud billing, discounts, or runtime usage.";

const EPSILON = 0.005;

export function parseCostAmount(value?: number | string | null): number | null {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const num = typeof value === "number" ? value : Number(value);
  return Number.isFinite(num) ? num : null;
}

export function normalizeCurrencyCode(currency?: string | null): string {
  const trimmed = currency?.trim().toUpperCase();
  return trimmed && trimmed.length > 0 ? trimmed : "USD";
}

export function formatCurrency(amount?: number | string | null, currency?: string | null): string {
  const num = parseCostAmount(amount);
  if (num === null) {
    return "—";
  }
  const code = normalizeCurrencyCode(currency);
  const rounded = Object.is(num, -0) || Math.abs(num) < EPSILON ? 0 : num;
  try {
    return new Intl.NumberFormat("en-US", {
      style: "currency",
      currency: code,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(rounded);
  } catch {
    const sign = rounded < 0 ? "-" : "";
    return `${sign}${code} ${Math.abs(rounded).toFixed(2)}`;
  }
}

export function formatMonthlyCost(amount?: number | string | null, currency?: string | null): string {
  const formatted = formatCurrency(amount, currency);
  return formatted === "—" ? "—" : `${formatted}/mo`;
}

export type CostDeltaSummary = {
  text: string;
  direction: "increase" | "decrease" | "neutral";
  percentText?: string;
};

export function formatCostDelta(
  diff?: number | string | null,
  past?: number | string | null,
  currency?: string | null
): CostDeltaSummary {
  const diffNum = parseCostAmount(diff);
  if (diffNum === null) {
    return { text: "—", direction: "neutral" };
  }

  if (Math.abs(diffNum) < EPSILON) {
    return {
      text: `${formatCurrency(0, currency)}/mo`,
      direction: "neutral",
    };
  }

  const direction = diffNum > 0 ? "increase" : "decrease";
  const prefix = diffNum > 0 ? "+" : "-";
  const baseText = `${prefix}${formatCurrency(Math.abs(diffNum), currency)}/mo`;

  const pastNum = parseCostAmount(past);
  if (pastNum !== null && Math.abs(pastNum) >= EPSILON) {
    const pct = (diffNum / Math.abs(pastNum)) * 100;
    const pctSign = pct > 0 ? "+" : "";
    const percentText = `${pctSign}${pct.toFixed(1)}%`;
    return {
      text: `${baseText} (${percentText})`,
      direction,
      percentText,
    };
  }

  return { text: baseText, direction };
}

export type CurrencySpendTotal = {
  currency: string;
  totalMonthlyCost: number;
  pricedCount: number;
};

export type ProjectSpendTotal = {
  projectId: string;
  projectName: string;
  currencyTotals: CurrencySpendTotal[];
};

export type SpendAggregation = {
  currencyTotals: CurrencySpendTotal[];
  totalPricedCount: number;
  totalWorkspaces: number;
  projectTotals: ProjectSpendTotal[];
};

function sortCurrencyTotals(map: Map<string, { totalMonthlyCost: number; pricedCount: number }>): CurrencySpendTotal[] {
  return Array.from(map.entries())
    .map(([currency, stats]) => ({
      currency,
      totalMonthlyCost: Number(stats.totalMonthlyCost.toFixed(4)),
      pricedCount: stats.pricedCount,
    }))
    .sort((a, b) => {
      if (b.totalMonthlyCost !== a.totalMonthlyCost) {
        return b.totalMonthlyCost - a.totalMonthlyCost;
      }
      if (a.currency === "USD" && b.currency !== "USD") return -1;
      if (b.currency === "USD" && a.currency !== "USD") return 1;
      return a.currency.localeCompare(b.currency);
    });
}

export function aggregateSpendByCurrency(workspaces: WorkspaceListItem[]): SpendAggregation {
  const orgTotals = new Map<string, { totalMonthlyCost: number; pricedCount: number }>();
  const projectMap = new Map<
    string,
    { projectName: string; currencies: Map<string, { totalMonthlyCost: number; pricedCount: number }> }
  >();
  let totalPricedCount = 0;

  for (const workspace of workspaces) {
    const cost = parseCostAmount(workspace.currentMonthlyCost);
    if (cost === null) {
      continue;
    }
    totalPricedCount += 1;
    const currency = normalizeCurrencyCode(workspace.costCurrency);
    const currentOrg = orgTotals.get(currency) ?? { totalMonthlyCost: 0, pricedCount: 0 };
    orgTotals.set(currency, {
      totalMonthlyCost: currentOrg.totalMonthlyCost + cost,
      pricedCount: currentOrg.pricedCount + 1,
    });

    if (workspace.projectId && workspace.projectName) {
      const existingProject = projectMap.get(workspace.projectId) ?? {
        projectName: workspace.projectName,
        currencies: new Map<string, { totalMonthlyCost: number; pricedCount: number }>(),
      };
      const curProj = existingProject.currencies.get(currency) ?? { totalMonthlyCost: 0, pricedCount: 0 };
      existingProject.currencies.set(currency, {
        totalMonthlyCost: curProj.totalMonthlyCost + cost,
        pricedCount: curProj.pricedCount + 1,
      });
      projectMap.set(workspace.projectId, existingProject);
    }
  }

  const projectTotals: ProjectSpendTotal[] = Array.from(projectMap.entries())
    .map(([projectId, data]) => ({
      projectId,
      projectName: data.projectName,
      currencyTotals: sortCurrencyTotals(data.currencies),
    }))
    .sort((a, b) => {
      const aMax = a.currencyTotals[0]?.totalMonthlyCost ?? 0;
      const bMax = b.currencyTotals[0]?.totalMonthlyCost ?? 0;
      if (bMax !== aMax) return bMax - aMax;
      return a.projectName.localeCompare(b.projectName);
    });

  return {
    currencyTotals: sortCurrencyTotals(orgTotals),
    totalPricedCount,
    totalWorkspaces: workspaces.length,
    projectTotals,
  };
}
