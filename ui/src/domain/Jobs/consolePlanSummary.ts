/**
 * Reads a Terraform/OpenTofu plan or apply summary line out of raw console output.
 *
 * The structured plan view must not fall back to "No changes" just because the structured context
 * is missing or stale - if the console clearly shows a plan that *did* produce changes, the UI
 * should say "structured output temporarily unavailable" instead. This parser provides that signal.
 */
export type ConsolePlanSummary = {
  /** A plan/apply summary line was found at all. */
  hasPlan: boolean;
  /** The summary reports one or more resource changes. */
  declaresChanges: boolean;
  /** The summary explicitly reports no changes. */
  declaresNoChanges: boolean;
  add?: number;
  change?: number;
  destroy?: number;
};

// eslint-disable-next-line no-control-regex
const ANSI = /\x1b\[[0-9;]*m/g;
const PLAN_LINE = /Plan:\s+(\d+)\s+to add,\s+(\d+)\s+to change,\s+(\d+)\s+to destroy/i;
const APPLY_LINE = /Apply complete!\s+Resources:\s+(\d+)\s+added,\s+(\d+)\s+changed,\s+(\d+)\s+destroyed/i;
const NO_CHANGES = /No changes\.\s+Your infrastructure (?:still )?matches the configuration/i;

const EMPTY: ConsolePlanSummary = { hasPlan: false, declaresChanges: false, declaresNoChanges: false };

export const parseConsolePlanSummary = (consoleText: string | undefined | null): ConsolePlanSummary => {
  if (!consoleText) {
    return EMPTY;
  }

  const text = consoleText.replace(ANSI, "");
  const counts = text.match(PLAN_LINE) ?? text.match(APPLY_LINE);
  if (counts) {
    const add = Number(counts[1]);
    const change = Number(counts[2]);
    const destroy = Number(counts[3]);
    const total = add + change + destroy;
    return {
      hasPlan: true,
      declaresChanges: total > 0,
      declaresNoChanges: total === 0,
      add,
      change,
      destroy,
    };
  }

  if (NO_CHANGES.test(text)) {
    return { hasPlan: true, declaresChanges: false, declaresNoChanges: true };
  }

  return EMPTY;
};
