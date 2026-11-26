import { InfoCircleOutlined } from "@ant-design/icons";
import { Tag, Tooltip, Typography } from "antd";
import { useMemo } from "react";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import {
  aggregateSpendByCurrency,
  COST_ESTIMATION_DISCLAIMER,
  formatMonthlyCost,
} from "@/modules/workspaces/utils/costFormat";
import "./EstimatedSpendRollupCard.css";

type Props = {
  workspaces: WorkspaceListItem[];
  title?: string;
  showProjectBreakdown?: boolean;
  costSortActive?: boolean;
};

export default function EstimatedSpendRollupCard({
  workspaces,
  title = "Estimated Monthly Spend",
  showProjectBreakdown = false,
  costSortActive = false,
}: Props) {
  const aggregation = useMemo(() => aggregateSpendByCurrency(workspaces), [workspaces]);

  if (aggregation.totalPricedCount === 0) {
    if (!costSortActive) {
      return null;
    }
    return (
      <section className="estimated-spend-rollup" aria-label={title} data-testid="estimated-spend-rollup">
        <div className="estimated-spend-rollup-header">
          <span className="estimated-spend-rollup-title">
            <span>{title}</span>
            <Tooltip title={COST_ESTIMATION_DISCLAIMER}>
              <InfoCircleOutlined className="estimated-spend-rollup-info" aria-label="Cost estimation disclaimer" />
            </Tooltip>
          </span>
          <Typography.Text className="estimated-spend-rollup-meta">
            Showing only workspaces with a cost estimate
          </Typography.Text>
        </div>
      </section>
    );
  }

  return (
    <section className="estimated-spend-rollup" aria-label={title} data-testid="estimated-spend-rollup">
      <div className="estimated-spend-rollup-header">
        <span className="estimated-spend-rollup-title">
          <span>{title}</span>
          <Tooltip title={COST_ESTIMATION_DISCLAIMER}>
            <InfoCircleOutlined className="estimated-spend-rollup-info" aria-label="Cost estimation disclaimer" />
          </Tooltip>
        </span>
        <Typography.Text className="estimated-spend-rollup-meta">
          {costSortActive
            ? `Showing only workspaces with a cost estimate · ${aggregation.totalPricedCount} priced on this page · Pre-deployment on-demand estimate`
            : `${aggregation.totalPricedCount} of ${aggregation.totalWorkspaces} workspaces priced · Pre-deployment on-demand estimate`}
        </Typography.Text>
      </div>

      <div className="estimated-spend-rollup-metrics">
        {aggregation.currencyTotals.map((ct) => (
          <div key={ct.currency} className="estimated-spend-currency-total">
            <span className="estimated-spend-amount">{formatMonthlyCost(ct.totalMonthlyCost, ct.currency)}</span>
            <span className="estimated-spend-currency-label">
              {ct.currency} ({ct.pricedCount} {ct.pricedCount === 1 ? "workspace" : "workspaces"})
            </span>
          </div>
        ))}
      </div>

      {aggregation.currencyTotals.length > 1 && (
        <Typography.Text className="estimated-spend-rollup-meta">
          Multiple currencies shown separately; cost sorting compares raw amounts without FX conversion.
        </Typography.Text>
      )}

      {showProjectBreakdown && aggregation.projectTotals.length > 1 && (
        <div className="estimated-spend-projects">
          <span className="estimated-spend-projects-label">By project:</span>
          {aggregation.projectTotals.map((project) => (
            <Tag key={project.projectId}>
              {project.projectName}:{" "}
              {project.currencyTotals.map((ct) => formatMonthlyCost(ct.totalMonthlyCost, ct.currency)).join(" + ")}
            </Tag>
          ))}
        </div>
      )}
    </section>
  );
}
