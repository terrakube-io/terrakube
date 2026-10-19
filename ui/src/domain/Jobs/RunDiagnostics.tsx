import { WarningOutlined } from "@ant-design/icons";
import { Button, Collapse, Tag } from "antd";
import { useState } from "react";
import { RunDiagnostic, countBySeverity } from "./collectDiagnostics";
// The diagnostic rows share their styling with the per-step view, so this panel does not depend on
// that view having been loaded first.
import "./StructuredPlanOutput.css";

// A run can report hundreds of deprecation warnings; the first ones are enough to see what is wrong.
export const DIAGNOSTICS_SHOWN_AT_FIRST = 50;

type Props = {
  diagnostics: RunDiagnostic[];
};

/**
 * Every error and warning captured for the run, in one place. It reads only from the diagnostics it
 * is given, so the resource search box, the operation filters and whether a row is expanded have no
 * effect on it (#3693). It opens by default when the run has an error.
 */
export const RunDiagnostics = ({ diagnostics }: Props) => {
  const [showAll, setShowAll] = useState(false);
  if (diagnostics.length === 0) {
    return null;
  }
  const shown = showAll ? diagnostics : diagnostics.slice(0, DIAGNOSTICS_SHOWN_AT_FIRST);
  const hidden = diagnostics.length - shown.length;

  const { errors, warnings } = countBySeverity(diagnostics);

  return (
    <Collapse
      className="run-panel"
      data-testid="run-diagnostics"
      defaultActiveKey={errors > 0 ? ["run-diagnostics"] : []}
      items={[
        {
          key: "run-diagnostics",
          label: (
            <span className="run-panel-label">
              <WarningOutlined className="run-panel-icon" aria-hidden />
              <h3 className="run-panel-title">Diagnostics</h3>
              {errors > 0 ? <Tag color="red">{errors === 1 ? "1 error" : `${errors} errors`}</Tag> : null}
              {warnings > 0 ? <Tag color="orange">{warnings === 1 ? "1 warning" : `${warnings} warnings`}</Tag> : null}
            </span>
          ),
          children: (
            <div className="structured-plan-jobDiagnostics">
              {shown.map((diagnostic) => (
                <div
                  key={diagnostic.key}
                  className={`structured-plan-jobDiagnostic structured-plan-jobDiagnostic--${diagnostic.severity}`}
                  data-testid="run-diagnostic"
                >
                  <span className="structured-plan-diagnostic-summary">
                    {diagnostic.address ? (
                      <>
                        <code>{diagnostic.address}</code>:{" "}
                      </>
                    ) : null}
                    {diagnostic.summary}
                    {diagnostic.location ? (
                      <span className="structured-plan-diagnostic-location"> ({diagnostic.location})</span>
                    ) : null}
                  </span>
                  {diagnostic.detail ? (
                    <span className="structured-plan-diagnostic-detail">{diagnostic.detail}</span>
                  ) : null}
                  {diagnostic.stepNames.length > 0 ? (
                    <span className="structured-plan-diagnostic-steps" data-testid="run-diagnostic-steps">
                      Reported by {diagnostic.stepNames.join(", ")}
                    </span>
                  ) : null}
                </div>
              ))}
              {hidden > 0 ? (
                <Button type="link" onClick={() => setShowAll(true)} data-testid="run-diagnostics-show-all">
                  {`Show ${hidden} more`}
                </Button>
              ) : null}
            </div>
          ),
        },
      ]}
    />
  );
};
