import "./PlanSummaryText.css";

type Props = {
  add?: number | null;
  change?: number | null;
  destroy?: number | null;
};

/** "+3 ~1 -0", or "No changes" when the plan changes nothing. Nothing at all when the run has no summary. */
export default function PlanSummaryText({ add, change, destroy }: Props) {
  if (add == null || change == null || destroy == null) return null;
  if (add + change + destroy === 0) return <span className="plan-summary">No changes</span>;
  const words = `${add} to add, ${change} to change, ${destroy} to destroy`;
  return (
    <span className="plan-summary" title={words}>
      <span aria-hidden="true">
        <span className={add ? "plan-summary-add" : "plan-summary-zero"}>+{add}</span>{" "}
        <span className={change ? "plan-summary-change" : "plan-summary-zero"}>~{change}</span>{" "}
        <span className={destroy ? "plan-summary-destroy" : "plan-summary-zero"}>-{destroy}</span>
      </span>
      <span className="plan-summary-words">{words}</span>
    </span>
  );
}
