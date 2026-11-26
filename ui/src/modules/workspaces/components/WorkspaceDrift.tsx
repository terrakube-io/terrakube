import { Button, Card, Tag, Typography } from "antd";
import { SwapOutlined } from "@ant-design/icons";
import { StatusBadge } from "@/components/display/OverviewCards/OverviewCards";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Workspace } from "@/domain/types";
import { shortAgo } from "@/modules/projects/projectHealth";
import { formatDateTime } from "@/modules/utils/dates";
import { DriftCheck, listDriftChecks } from "../driftService";
import PlanSummaryText from "./PlanSummaryText";
import "./WorkspaceDrift.css";

const HISTORY = 6;

/** What the last drift check found, and the checks before it. Nothing for a workspace that was never checked. */
export default function WorkspaceDrift({ orgId, workspace }: { orgId: string; workspace: Workspace }) {
  const { driftStatus, driftFirstSeen, driftLastChecked, driftJobId } = workspace.attributes;
  const [checks, setChecks] = useState<DriftCheck[] | null>(null);
  const [showHistory, setShowHistory] = useState(false);

  useEffect(() => {
    if (!driftStatus) return;
    let alive = true;
    // Reloaded when a new check finished, which is when the last-checked time moves.
    listDriftChecks(orgId, workspace.id, HISTORY)
      .then((response) => alive && setChecks(response.isError ? [] : (response.data ?? [])))
      .catch(() => alive && setChecks([]));
    return () => {
      alive = false;
    };
  }, [orgId, workspace.id, driftStatus, driftLastChecked]);

  if (!driftStatus) return null;
  const drifted = driftStatus === "drifted";
  const latest = checks?.[0];
  const resources =
    latest && latest.add !== null ? (latest.add ?? 0) + (latest.change ?? 0) + (latest.destroy ?? 0) : null;

  return (
    <Card
      className={`overview-card overview-state workspace-drift${showHistory ? " overview-state-wide" : ""}`}
      role="region"
      aria-label="Drift"
    >
      <StatusBadge tone={drifted ? "warning" : "success"}>
        <SwapOutlined />
      </StatusBadge>
      <div className="overview-state-text">
        <div className="workspace-drift-line">
          {drifted ? <Tag color="warning">Drifted</Tag> : <Tag color="success">No drift</Tag>}
          <span>
            {drifted ? (
              <>
                First seen{" "}
                <time dateTime={driftFirstSeen ?? undefined} title={formatDateTime(driftFirstSeen)}>
                  {shortAgo(driftFirstSeen)}
                </time>
                {resources !== null && `, ${resources} resource${resources === 1 ? "" : "s"} differ`}.
              </>
            ) : (
              <>
                Checked{" "}
                <time dateTime={driftLastChecked ?? undefined} title={formatDateTime(driftLastChecked)}>
                  {shortAgo(driftLastChecked)}
                </time>
                .
              </>
            )}
          </span>
          {driftJobId != null && (
            <Link to={`/organizations/${orgId}/workspaces/${workspace.id}/runs/${driftJobId}`}>View plan</Link>
          )}
        </div>
        {checks && checks.length > 1 && (
          <div className="workspace-drift-bar">
            <div
              className="workspace-drift-blocks"
              role="img"
              aria-label={`Last ${checks.length} drift checks, ${checks.filter((check) => check.result === "drift").length} found drift`}
            >
              {/* Oldest first, so the newest check is the one on the right. */}
              {[...checks].reverse().map((check) => (
                <span
                  key={check.id}
                  className={`workspace-drift-block workspace-drift-block-${check.result}`}
                  title={`${formatDateTime(check.createdDate)}: ${check.result === "drift" ? "drift" : "clean"}`}
                />
              ))}
            </div>
            <Button
              type="link"
              size="small"
              aria-expanded={showHistory}
              aria-controls="workspace-drift-history"
              onClick={() => setShowHistory((open) => !open)}
            >
              {showHistory ? "Hide checks" : "Show checks"}
            </Button>
          </div>
        )}
        {checks && checks.length > 1 && showHistory && (
          <table id="workspace-drift-history" className="workspace-drift-history">
            <caption className="workspace-drift-caption">Drift checks</caption>
            <thead>
              <tr>
                <th scope="col">Checked</th>
                <th scope="col">Result</th>
                <th scope="col">Changes</th>
                <th scope="col">
                  <span className="workspace-drift-hidden">Plan</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {checks.map((check) => (
                <tr key={check.id}>
                  <td>{formatDateTime(check.createdDate)}</td>
                  <td>{check.result === "drift" ? "Drift" : "Clean"}</td>
                  <td>
                    {check.result === "clean" ? (
                      <Typography.Text type="secondary">-</Typography.Text>
                    ) : (
                      <PlanSummaryText add={check.add} change={check.change} destroy={check.destroy} />
                    )}
                  </td>
                  <td>
                    <Link to={`/organizations/${orgId}/workspaces/${workspace.id}/runs/${check.id}`}>Plan</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </Card>
  );
}
