import { Card, Skeleton, Tooltip, Typography } from "antd";
import { useEffect, useId, useState } from "react";
import "@/components/display/OverviewCards/OverviewCards.css";
import { Delivery, formatSpan } from "./deliveryStats";
import projectService from "./projectService";

const DAYS = 30;

function Figure({ value, label, hint }: { value: string; label: string; hint: string }) {
  const hintId = useId();
  return (
    <Tooltip title={hint} trigger={["hover", "focus"]}>
      {/* Focusable, so the definition opens from the keyboard too, and the same words are read out with the figure. */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
      <div className="overview-figure" tabIndex={0} aria-describedby={hintId}>
        <div className="overview-figure-number">{value}</div>
        <Typography.Text type="secondary">{label}</Typography.Text>
        <span id={hintId} className="overview-figure-hint">
          {hint}
        </span>
      </div>
    </Tooltip>
  );
}

/** Four numbers about how a project's runs went lately, with where each comes from a hover away. */
export default function ProjectDelivery({ orgid, projectId }: { orgid: string; projectId: string }) {
  const [state, setState] = useState<{ delivery?: Delivery; failed?: boolean } | null>(null);

  useEffect(() => {
    let alive = true;
    setState(null);
    projectService
      .getDelivery(orgid, projectId, DAYS)
      .then((response) => {
        if (alive) setState(response.isError ? { failed: true } : { delivery: response.data });
      })
      .catch(() => alive && setState({ failed: true }));
    return () => {
      alive = false;
    };
  }, [orgid, projectId]);

  const delivery = state?.delivery;
  return (
    <Card className="overview-card" role="region" aria-label="Delivery">
      <h3 className="overview-card-title">{`Delivery, last ${DAYS} days`}</h3>
      {state === null && <Skeleton active paragraph={{ rows: 1 }} />}
      {state?.failed && <Typography.Text type="secondary">Not available right now.</Typography.Text>}
      {delivery && delivery.runs === 0 && <Typography.Text type="secondary">No runs in this time.</Typography.Text>}
      {delivery && delivery.runs > 0 && (
        <>
          <div className="overview-figures">
            <Figure
              value={delivery.partial ? `${delivery.runs}+` : String(delivery.runs)}
              label="Runs"
              hint="Runs started in this time."
            />
            <Figure
              value={delivery.succeededPercent === null ? "-" : `${delivery.succeededPercent}%`}
              label="Succeeded"
              hint="Of the runs that finished. Runs a person rejected or cancelled are left out."
            />
            <Figure
              value={formatSpan(delivery.medianApproveMs)}
              label="Median time to approve"
              hint="From a run starting to someone approving it, so it includes the plan."
            />
            <Figure
              value={formatSpan(delivery.medianRunMs)}
              label="Median run time"
              hint="From a run starting to it finishing, so it includes any wait for approval."
            />
          </div>
          {delivery.partial && (
            <Typography.Text type="secondary" className="project-overview-meta">
              Based on the latest runs only: there were more than could be read, so the count is at least this.
            </Typography.Text>
          )}
        </>
      )}
    </Card>
  );
}
