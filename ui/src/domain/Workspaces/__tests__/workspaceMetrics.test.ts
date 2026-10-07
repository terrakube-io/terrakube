import { computeWorkspaceMetrics } from "../workspaceMetrics";
import { FlatJob, JobStatus } from "../../types";

const job = (id: string, status: JobStatus, createdDate?: string, updatedDate?: string): FlatJob => ({
  id,
  title: "Run",
  status,
  latestChange: "",
  createdBy: "admin",
  createdDate,
  updatedDate,
});

describe("computeWorkspaceMetrics", () => {
  it("returns null when no run has finished", () => {
    expect(computeWorkspaceMetrics([])).toBeNull();
    expect(
      computeWorkspaceMetrics([job("1", JobStatus.Running, "2026-10-01T10:00:00Z", "2026-10-01T10:01:00Z")])
    ).toBeNull();
  });

  it("averages finished runs, counts failures and finds the last successful run", () => {
    const metrics = computeWorkspaceMetrics([
      job("1", JobStatus.Completed, "2026-10-01T10:00:00Z", "2026-10-01T10:00:30Z"),
      job("2", JobStatus.Failed, "2026-10-02T10:00:00Z", "2026-10-02T10:01:30Z"),
      job("3", JobStatus.NoChanges, "2026-10-03T10:00:00Z", "2026-10-03T10:01:00Z"),
      job("4", JobStatus.Running, "2026-10-04T10:00:00Z", "2026-10-04T11:00:00Z"),
    ]);
    expect(metrics).toEqual({
      runCount: 4,
      averageDuration: "1m 00s",
      failedRuns: 1,
      lastSuccessfulRun: "2026-10-03T10:01:00Z",
    });
  });

  it("skips runs with missing or invalid dates and reports no success when every run failed", () => {
    const metrics = computeWorkspaceMetrics([
      job("1", JobStatus.Failed, "2026-10-01T10:00:00Z", "2026-10-01T10:00:20Z"),
      job("2", JobStatus.Failed, undefined, "2026-10-02T10:00:00Z"),
      job("3", JobStatus.Cancelled, "2026-10-03T10:00:00Z", "2026-10-03T09:00:00Z"),
    ]);
    expect(metrics).toEqual({ runCount: 3, averageDuration: "20s", failedRuns: 2, lastSuccessfulRun: null });
  });

  it("leaves rejected, cancelled and not-executed runs out of the average, which include approval wait", () => {
    const metrics = computeWorkspaceMetrics([
      job("1", JobStatus.Completed, "2026-10-01T10:00:00Z", "2026-10-01T10:00:40Z"),
      job("2", JobStatus.Rejected, "2026-10-02T10:00:00Z", "2026-10-03T10:00:00Z"),
      job("3", JobStatus.Cancelled, "2026-10-03T10:00:00Z", "2026-10-03T12:00:00Z"),
      job("4", JobStatus.NotExecuted, "2026-10-04T10:00:00Z", "2026-10-04T13:00:00Z"),
    ]);
    expect(metrics).toMatchObject({ runCount: 4, averageDuration: "40s", failedRuns: 0 });
    expect(
      computeWorkspaceMetrics([job("1", JobStatus.Rejected, "2026-10-02T10:00:00Z", "2026-10-03T10:00:00Z")])
        ?.averageDuration
    ).toBeNull();
  });

  it("has no average duration when no finished run has usable dates", () => {
    expect(computeWorkspaceMetrics([job("1", JobStatus.Completed)])?.averageDuration).toBeNull();
  });
});
