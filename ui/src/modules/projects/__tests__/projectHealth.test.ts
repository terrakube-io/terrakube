import dayjs from "dayjs";
import { ProjectSummaryModel } from "@/domain/types";
import {
  attentionBadge,
  attentionTone,
  defaultView,
  healthSegments,
  policyRollup,
  shortAgo,
  shortDuration,
} from "../projectHealth";

const base: ProjectSummaryModel = {
  id: "p",
  name: "p",
  workspaceCount: 10,
  completedCount: 6,
  failedCount: 0,
  runningCount: 0,
  queuedCount: 0,
  waitingApprovalCount: 0,
  neverRunCount: 4,
  nonCompliantCount: 0,
  hardViolationCount: 0,
  softViolationCount: 0,
  compliantCount: 0,
  exemptedCount: 0,
  queuedJobCount: 0,
  lockedCount: 0,
  attentionCount: 0,
  teamCount: 0,
};

describe("healthSegments", () => {
  it("keeps queued apart from running, in the order of the legend", () => {
    const segments = healthSegments({ ...base, runningCount: 2, queuedCount: 3 });

    expect(segments.map((segment) => segment.key)).toEqual([
      "completed",
      "failed",
      "running",
      "queued",
      "waiting",
      "never",
    ]);
    expect(segments.find((segment) => segment.key === "queued")?.count).toBe(3);
  });
});

describe("attentionBadge", () => {
  it("is red when runs failed or wait for a person", () => {
    expect(attentionBadge({ ...base, failedCount: 1, attentionCount: 3 })).toEqual({
      tone: "danger",
      text: "3 need attention",
    });
    expect(attentionBadge({ ...base, waitingApprovalCount: 1, attentionCount: 1 })?.tone).toBe("danger");
  });

  it("is amber when the only issue is policy", () => {
    expect(attentionBadge({ ...base, nonCompliantCount: 2, attentionCount: 2 })).toEqual({
      tone: "warning",
      text: "2 non-compliant",
    });
  });

  it("is absent when all is well, even with locked workspaces", () => {
    expect(attentionBadge({ ...base, lockedCount: 4 })).toBeNull();
  });
});

describe("attentionTone", () => {
  it("grades 0, 1 and 2 or more", () => {
    expect([attentionTone(0), attentionTone(1), attentionTone(2), attentionTone(9)]).toEqual([
      "neutral",
      "warning",
      "danger",
      "danger",
    ]);
  });
});

describe("policyRollup", () => {
  it("shows hard before soft, and falls back to non-compliant for evaluations without a split", () => {
    expect(policyRollup({ ...base, nonCompliantCount: 3, hardViolationCount: 1, softViolationCount: 2 })).toEqual({
      tone: "danger",
      text: "1 hard",
    });
    expect(policyRollup({ ...base, nonCompliantCount: 2, softViolationCount: 2 })).toEqual({
      tone: "warning",
      text: "2 soft",
    });
    expect(policyRollup({ ...base, nonCompliantCount: 2 }).text).toBe("2 non-compliant");
  });

  it("reports non-compliant first, then compliant, then not evaluated", () => {
    expect(policyRollup({ ...base, nonCompliantCount: 1, compliantCount: 5 })).toEqual({
      tone: "danger",
      text: "1 non-compliant",
    });
    expect(policyRollup({ ...base, compliantCount: 5 })).toEqual({ tone: "success", text: "Compliant" });
    expect(policyRollup({ ...base, exemptedCount: 1 }).text).toBe("Compliant");
    expect(policyRollup(base)).toEqual({ tone: "neutral", text: "Not evaluated" });
  });
});

describe("relative times", () => {
  const now = dayjs("2026-11-08T12:00:00Z");

  it("uses the compact forms of the design", () => {
    expect(shortAgo("2026-11-08T11:55:30Z", now)).toBe("4m ago");
    expect(shortAgo("2026-11-08T09:00:00Z", now)).toBe("3h ago");
    expect(shortAgo("2026-11-06T12:00:00Z", now)).toBe("2d ago");
    expect(shortAgo("2026-11-08T11:59:50Z", now)).toBe("just now");
  });

  it("shows a dash when there is no time", () => {
    expect(shortAgo(undefined, now)).toBe("-");
    expect(shortDuration("not a date", now)).toBeNull();
  });
});

describe("defaultView", () => {
  it("is cards for a small catalogue and a table for a large one", () => {
    expect(defaultView(6)).toBe("cards");
    expect(defaultView(24)).toBe("cards");
    expect(defaultView(25)).toBe("table");
  });
});
