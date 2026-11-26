import React, { useMemo, useState } from "react";
import { Alert, Button, Empty, Input, Tag, Typography } from "antd";
import clsx from "classnames";
import {
  DownOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  LinkOutlined,
  RightOutlined,
  SearchOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { InfracostContext, InfracostCostComponent, InfracostProject, InfracostResource } from "../types";
import {
  COST_ESTIMATION_DISCLAIMER,
  formatCostDelta,
  formatCurrency,
  formatMonthlyCost,
  normalizeCurrencyCode,
  parseCostAmount,
} from "@/modules/workspaces/utils/costFormat";
import "@/modules/workspaces/components/WorkspaceFilter.css";
import "./runTokens.css";
import "./CostEstimationOutput.css";

type Props = {
  infracost: InfracostContext;
};

type ChangeKind = "created" | "deleted" | "changed" | "unchanged";
type ResourceFilter = "all" | "changed" | "created" | "deleted" | "unpriced";

type FlattenedCostResource = {
  key: string;
  projectName: string;
  name: string;
  resourceType?: string;
  changeKind: ChangeKind;
  isUnpriced: boolean;
  monthlyCost: number | string | null;
  hourlyCost: number | string | null;
  diffMonthlyCost: number | string | null;
  costComponents: InfracostCostComponent[];
  subresources: InfracostResource[];
};

const EPSILON = 0.005;

export function sanitizeInfracostShareUrl(raw?: string): string | undefined {
  if (!raw) {
    return undefined;
  }
  const trimmed = raw.trim();
  if (!trimmed) {
    return undefined;
  }
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password) {
      return undefined;
    }
    const host = parsed.hostname.toLowerCase();
    if (host === "infracost.io" || host.endsWith(".infracost.io")) {
      return parsed.toString();
    }
    return undefined;
  } catch {
    return undefined;
  }
}

function sanitizeErrorMessage(raw?: string): string {
  if (!raw || !raw.trim()) {
    return "Infracost cost estimation failed for this run. Terraform plan execution continued normally.";
  }
  return raw.replace(/ico-[A-Za-z0-9_-]{8,}/g, "[REDACTED]");
}

function hasAnyPrice(resource?: InfracostResource): boolean {
  if (!resource) return false;
  if (parseCostAmount(resource.monthlyCost) !== null) return true;
  if (resource.costComponents?.some((component) => parseCostAmount(component.monthlyCost) !== null)) return true;
  return Boolean(resource.subresources?.some((sub) => hasAnyPrice(sub)));
}

function resolveChangeKind(
  currentRes: InfracostResource | undefined,
  pastRes: InfracostResource | undefined,
  diffRes: InfracostResource | undefined,
  project: InfracostProject
): ChangeKind {
  if (!currentRes && pastRes) {
    return "deleted";
  }

  const diffNum = parseCostAmount(diffRes?.monthlyCost);
  if (currentRes && !pastRes) {
    if (project.pastBreakdown !== undefined) {
      return "created";
    }
    const curNum = parseCostAmount(currentRes.monthlyCost);
    if (diffNum !== null && curNum !== null && Math.abs(diffNum - curNum) < EPSILON && curNum > EPSILON) {
      return "created";
    }
    if (diffNum !== null && Math.abs(diffNum) >= EPSILON) {
      return diffNum > 0 ? "changed" : "deleted";
    }
    return "unchanged";
  }

  if (diffNum !== null && Math.abs(diffNum) >= EPSILON) {
    return "changed";
  }
  const curNum = parseCostAmount(currentRes?.monthlyCost);
  const pastNum = parseCostAmount(pastRes?.monthlyCost);
  if (curNum !== null && pastNum !== null && Math.abs(curNum - pastNum) >= EPSILON) {
    return "changed";
  }
  return "unchanged";
}

function flattenProjectResources(infracost: InfracostContext): FlattenedCostResource[] {
  const rows: FlattenedCostResource[] = [];
  const projects = infracost.projects ?? [];

  projects.forEach((project, projectIdx) => {
    const projectName = project.name || `Project ${projectIdx + 1}`;
    const currentList = project.breakdown?.resources ?? [];
    const pastList = project.pastBreakdown?.resources ?? [];
    const diffList = project.diff?.resources ?? [];

    const currentByName = new Map<string, InfracostResource>();
    for (const r of currentList) {
      if (r.name) currentByName.set(r.name, r);
    }
    const pastByName = new Map<string, InfracostResource>();
    for (const r of pastList) {
      if (r.name) pastByName.set(r.name, r);
    }
    const diffByName = new Map<string, InfracostResource>();
    for (const r of diffList) {
      if (r.name) diffByName.set(r.name, r);
    }

    const orderedNames: string[] = [];
    const seen = new Set<string>();
    for (const list of [currentList, diffList, pastList]) {
      for (const r of list) {
        if (r.name && !seen.has(r.name)) {
          seen.add(r.name);
          orderedNames.push(r.name);
        }
      }
    }

    for (const name of orderedNames) {
      const currentRes = currentByName.get(name);
      const pastRes = pastByName.get(name);
      const diffRes = diffByName.get(name);
      const displayRes = currentRes ?? diffRes ?? pastRes;
      if (!displayRes) continue;

      const changeKind = resolveChangeKind(currentRes, pastRes, diffRes, project);
      const isUnpriced = !hasAnyPrice(currentRes) && !hasAnyPrice(pastRes) && !hasAnyPrice(diffRes);
      const computedDiff =
        diffRes?.monthlyCost ??
        (parseCostAmount(currentRes?.monthlyCost) !== null && parseCostAmount(pastRes?.monthlyCost) !== null
          ? String((parseCostAmount(currentRes?.monthlyCost) ?? 0) - (parseCostAmount(pastRes?.monthlyCost) ?? 0))
          : null);

      rows.push({
        key: `${projectIdx}:${projectName}:${name}`,
        projectName,
        name,
        resourceType: displayRes.resourceType,
        changeKind,
        isUnpriced,
        monthlyCost: currentRes?.monthlyCost ?? (changeKind === "deleted" ? "0" : null),
        hourlyCost: currentRes?.hourlyCost ?? null,
        diffMonthlyCost: computedDiff,
        costComponents: displayRes.costComponents ?? [],
        subresources: displayRes.subresources ?? [],
      });
    }
  });

  return rows;
}

function formatQuantity(qty?: string | null, unit?: string): string {
  if (!qty) return "—";
  const num = Number(qty);
  const formatted = Number.isFinite(num)
    ? Number.isInteger(num)
      ? String(num)
      : num.toLocaleString("en-US", { maximumFractionDigits: 4 })
    : qty;
  return unit ? `${formatted} ${unit}` : formatted;
}

function getChangeBadge(kind: ChangeKind, isUnpriced: boolean) {
  if (isUnpriced) {
    return { label: "Unpriced", color: "default" as const };
  }
  switch (kind) {
    case "created":
      return { label: "+ Created", color: "success" as const };
    case "deleted":
      return { label: "- Deleted", color: "error" as const };
    case "changed":
      return { label: "~ Changed", color: "blue" as const };
    default:
      return { label: "Unchanged", color: "default" as const };
  }
}

export const CostEstimationOutput: React.FC<Props> = ({ infracost }) => {
  const [filter, setFilter] = useState<ResourceFilter>("all");
  const [searchQuery, setSearchQuery] = useState("");
  const [expandedResources, setExpandedResources] = useState<Record<string, boolean>>({});
  const [showUnpricedDetails, setShowUnpricedDetails] = useState(false);

  const currency = normalizeCurrencyCode(infracost.currency);
  const safeShareUrl = sanitizeInfracostShareUrl(infracost.shareUrl);

  const resources = useMemo(() => flattenProjectResources(infracost), [infracost]);

  const summary = infracost.summary;
  const unsupportedCount = summary?.totalUnsupportedResources ?? 0;
  const noPriceCount = summary?.totalNoPriceResources ?? 0;
  const totalUnpricedResources = unsupportedCount + noPriceCount;

  const unpricedEntries = useMemo(() => {
    const entries: Array<{ kind: "Unsupported" | "No price"; resourceType: string; count: number }> = [];
    for (const [resourceType, count] of Object.entries(summary?.unsupportedResourceCounts ?? {})) {
      entries.push({ kind: "Unsupported", resourceType, count });
    }
    for (const [resourceType, count] of Object.entries(summary?.noPriceResourceCounts ?? {})) {
      entries.push({ kind: "No price", resourceType, count });
    }
    return entries.sort((a, b) => b.count - a.count || a.resourceType.localeCompare(b.resourceType));
  }, [summary]);

  const counts = useMemo(() => {
    return {
      all: resources.length,
      changed: resources.filter((r) => r.changeKind === "changed").length,
      created: resources.filter((r) => r.changeKind === "created").length,
      deleted: resources.filter((r) => r.changeKind === "deleted").length,
      unpriced: resources.filter((r) => r.isUnpriced).length,
    };
  }, [resources]);

  const filteredResources = useMemo(() => {
    const needle = searchQuery.trim().toLowerCase();
    return resources.filter((row) => {
      if (filter === "changed" && row.changeKind !== "changed") return false;
      if (filter === "created" && row.changeKind !== "created") return false;
      if (filter === "deleted" && row.changeKind !== "deleted") return false;
      if (filter === "unpriced" && !row.isUnpriced) return false;
      if (!needle) return true;
      return (
        row.name.toLowerCase().includes(needle) ||
        row.projectName.toLowerCase().includes(needle) ||
        (row.resourceType?.toLowerCase().includes(needle) ?? false)
      );
    });
  }, [filter, resources, searchQuery]);

  const toggleResource = (key: string) => {
    setExpandedResources((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  };

  if (infracost.status === "ERROR") {
    return (
      <div className="cost-estimation" data-testid="cost-estimation-output">
        <Alert
          type="warning"
          showIcon
          icon={<ExclamationCircleOutlined />}
          title="Cost estimation unavailable"
          description={sanitizeErrorMessage(infracost.errorMessage)}
        />
        <div className="cost-estimation-disclaimer">
          <span className="cost-estimation-disclaimer-text">
            <InfoCircleOutlined aria-hidden />
            <span>{COST_ESTIMATION_DISCLAIMER}</span>
          </span>
        </div>
      </div>
    );
  }

  const delta = formatCostDelta(infracost.diffTotalMonthlyCost, infracost.pastTotalMonthlyCost, currency);
  const pricedResourcesCount = summary?.totalSupportedResources ?? resources.filter((r) => !r.isUnpriced).length;
  const detectedResourcesCount = summary?.totalDetectedResources ?? resources.length;
  const multiProject = (infracost.projects?.length ?? 0) > 1;

  return (
    <div className="cost-estimation" data-testid="cost-estimation-output">
      <div className="cost-estimation-disclaimer">
        <span className="cost-estimation-disclaimer-text">
          <InfoCircleOutlined aria-hidden />
          <span>{COST_ESTIMATION_DISCLAIMER}</span>
        </span>
        {safeShareUrl && (
          <a href={safeShareUrl} target="_blank" rel="noopener noreferrer">
            <LinkOutlined /> View in Infracost Cloud
          </a>
        )}
      </div>

      {infracost.truncated && (
        <Alert
          type="info"
          showIcon
          data-testid="cost-truncated-notice"
          title="Showing top priced resources in summary"
          description="This plan exceeded the job context size limit, so only the highest-cost resources are displayed below."
          style={{ marginBottom: 12 }}
        />
      )}

      <div className="cost-estimation-kpis">
        <div className="cost-kpi-card">
          <span className="cost-kpi-label">Monthly Estimate</span>
          <span className="cost-kpi-value">{formatMonthlyCost(infracost.totalMonthlyCost, currency)}</span>
          {infracost.totalHourlyCost && (
            <span className="cost-kpi-sub">{formatCurrency(infracost.totalHourlyCost, currency)}/hr</span>
          )}
        </div>
        <div className="cost-kpi-card">
          <span className="cost-kpi-label">Monthly Change</span>
          <span
            className={clsx("cost-kpi-value", {
              "cost-kpi-value--increase": delta.direction === "increase",
              "cost-kpi-value--decrease": delta.direction === "decrease",
            })}
          >
            {delta.text}
          </span>
          <span className="cost-kpi-sub">vs. previous baseline ({currency})</span>
        </div>
        <div className="cost-kpi-card">
          <span className="cost-kpi-label">Previous Monthly</span>
          <span className="cost-kpi-value">{formatMonthlyCost(infracost.pastTotalMonthlyCost, currency)}</span>
          {infracost.pastTotalHourlyCost && (
            <span className="cost-kpi-sub">{formatCurrency(infracost.pastTotalHourlyCost, currency)}/hr</span>
          )}
        </div>
        <div className="cost-kpi-card">
          <span className="cost-kpi-label">Priced Resources</span>
          <span className="cost-kpi-value">
            {pricedResourcesCount} / {detectedResourcesCount}
          </span>
          <span className="cost-kpi-sub">
            {totalUnpricedResources > 0
              ? `${totalUnpricedResources} unpriced / unsupported`
              : "All detected resources priced"}
          </span>
        </div>
      </div>

      {(totalUnpricedResources > 0 || unpricedEntries.length > 0) && (
        <div className="cost-unpriced-banner" data-testid="cost-unpriced-banner">
          <div className="cost-unpriced-header">
            <span className="cost-unpriced-title">
              <WarningOutlined aria-hidden />
              <span>
                {totalUnpricedResources > 0
                  ? `${totalUnpricedResources} unpriced or unsupported resource(s) omitted from total cost`
                  : "Unpriced resource details"}
              </span>
            </span>
            {unpricedEntries.length > 0 && (
              <Button
                type="link"
                size="small"
                onClick={() => setShowUnpricedDetails((prev) => !prev)}
                aria-expanded={showUnpricedDetails}
              >
                {showUnpricedDetails ? "Hide details" : "Show details"}
              </Button>
            )}
          </div>
          {showUnpricedDetails && unpricedEntries.length > 0 && (
            <div className="cost-unpriced-details">
              {unpricedEntries.map((entry) => (
                <Tag key={`${entry.kind}:${entry.resourceType}`} color="warning">
                  {entry.resourceType}: {entry.count} ({entry.kind})
                </Tag>
              ))}
            </div>
          )}
        </div>
      )}

      <div className="cost-toolbar">
        <div className="workspace-status-pills" role="group" aria-label="Filter resources by cost change">
          {(
            [
              { key: "all", label: "All", count: counts.all },
              { key: "changed", label: "Changed", count: counts.changed },
              { key: "created", label: "Created", count: counts.created },
              { key: "deleted", label: "Deleted", count: counts.deleted },
              { key: "unpriced", label: "Unpriced", count: counts.unpriced },
            ] as const
          ).map((pill) => (
            <button
              key={pill.key}
              type="button"
              aria-pressed={filter === pill.key}
              className={clsx("workspace-status-pill", { "workspace-status-pill--active": filter === pill.key })}
              onClick={() => setFilter(pill.key)}
            >
              {pill.label}
              <span className="workspace-status-count">{pill.count}</span>
            </button>
          ))}
        </div>

        <Input
          allowClear
          className="cost-search"
          prefix={<SearchOutlined />}
          placeholder="Filter by resource or type"
          aria-label="Filter cost resources"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
        />
      </div>

      {filteredResources.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No matching resources in cost breakdown" />
      ) : (
        <ul className="cost-resource-list">
          {filteredResources.map((row) => {
            const hasDetails = row.costComponents.length > 0 || row.subresources.length > 0;
            const isExpanded = Boolean(expandedResources[row.key]);
            const badge = getChangeBadge(row.changeKind, row.isUnpriced);
            const rowDiff = formatCostDelta(row.diffMonthlyCost, null, currency);

            return (
              <li key={row.key} className="cost-resource-item">
                <button
                  type="button"
                  className="cost-resource-row"
                  aria-expanded={hasDetails ? isExpanded : undefined}
                  onClick={() => {
                    if (hasDetails) toggleResource(row.key);
                  }}
                >
                  <span className="cost-resource-main">
                    {hasDetails ? isExpanded ? <DownOutlined aria-hidden /> : <RightOutlined aria-hidden /> : null}
                    <code className="cost-resource-address">{row.name}</code>
                    {multiProject && <span className="cost-resource-project">{row.projectName}</span>}
                    <Tag color={badge.color}>{badge.label}</Tag>
                  </span>
                  <span className="cost-resource-costs">
                    {row.diffMonthlyCost && rowDiff.direction !== "neutral" && (
                      <Typography.Text
                        className={clsx({
                          "cost-resource-diff--increase": rowDiff.direction === "increase",
                          "cost-resource-diff--decrease": rowDiff.direction === "decrease",
                        })}
                      >
                        {rowDiff.text}
                      </Typography.Text>
                    )}
                    <span className="cost-resource-monthly">{formatMonthlyCost(row.monthlyCost, currency)}</span>
                  </span>
                </button>

                {hasDetails && isExpanded && (
                  <div className="cost-components-wrapper">
                    <table className="cost-components-table">
                      <thead>
                        <tr>
                          <th>Component</th>
                          <th>Quantity</th>
                          <th className="cost-numeric">Unit Price</th>
                          <th className="cost-numeric">Monthly Cost</th>
                        </tr>
                      </thead>
                      <tbody>
                        {row.costComponents.map((component, idx) => (
                          <tr key={`${row.key}:comp:${idx}:${component.name}`}>
                            <td>{component.name}</td>
                            <td>
                              {formatQuantity(component.monthlyQuantity ?? component.hourlyQuantity, component.unit)}
                            </td>
                            <td className="cost-numeric">{formatCurrency(component.price, currency)}</td>
                            <td className="cost-numeric">{formatMonthlyCost(component.monthlyCost, currency)}</td>
                          </tr>
                        ))}
                        {row.subresources.map((sub, subIdx) => (
                          <React.Fragment key={`${row.key}:sub:${subIdx}:${sub.name}`}>
                            <tr>
                              <td colSpan={3} className="cost-subresource-heading">
                                {sub.name}
                              </td>
                              <td className="cost-numeric cost-subresource-heading">
                                {formatMonthlyCost(sub.monthlyCost, currency)}
                              </td>
                            </tr>
                            {(sub.costComponents ?? []).map((component, cIdx) => (
                              <tr key={`${row.key}:sub:${subIdx}:comp:${cIdx}:${component.name}`}>
                                <td>{component.name}</td>
                                <td>
                                  {formatQuantity(
                                    component.monthlyQuantity ?? component.hourlyQuantity,
                                    component.unit
                                  )}
                                </td>
                                <td className="cost-numeric">{formatCurrency(component.price, currency)}</td>
                                <td className="cost-numeric">{formatMonthlyCost(component.monthlyCost, currency)}</td>
                              </tr>
                            ))}
                          </React.Fragment>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
};
