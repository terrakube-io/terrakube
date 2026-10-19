import { FlatJob, JobStatus } from "../../types";
import { selectCurrentRun } from "../currentRun";

const job = (id: number, status: JobStatus): FlatJob => ({
  id: String(id),
  title: `run ${id}`,
  status,
  latestChange: "",
  createdBy: "someone",
});

describe("selectCurrentRun", () => {
  it("returns undefined without runs", () => {
    expect(selectCurrentRun([])).toBeUndefined();
  });

  it("shows the newest run when every run is finished", () => {
    const result = selectCurrentRun([
      job(1, JobStatus.Failed),
      job(3, JobStatus.Completed),
      job(2, JobStatus.Rejected),
    ]);
    expect(result?.job.id).toBe("3");
    expect(result?.active).toBe(false);
    expect(result?.queuedBehind).toEqual([]);
  });

  it("keeps a run waiting for approval in the card when a newer run is pending behind it", () => {
    const result = selectCurrentRun([
      job(1, JobStatus.Completed),
      job(2, JobStatus.WaitingApproval),
      job(3, JobStatus.Pending),
    ]);
    expect(result?.job.id).toBe("2");
    expect(result?.active).toBe(true);
    expect(result?.queuedBehind.map((queued) => queued.id)).toEqual(["3"]);
  });

  it("does not let a newer finished plan-only run replace the waiting run", () => {
    const result = selectCurrentRun([job(2, JobStatus.WaitingApproval), job(3, JobStatus.Completed)]);
    expect(result?.job.id).toBe("2");
    expect(result?.queuedBehind).toEqual([]);
  });

  it("orders ids numerically, not as text", () => {
    const result = selectCurrentRun([job(9, JobStatus.Pending), job(10, JobStatus.Pending)]);
    expect(result?.job.id).toBe("9");
    expect(result?.queuedBehind.map((queued) => queued.id)).toEqual(["10"]);
  });

  it.each([JobStatus.Approved, JobStatus.Running, JobStatus.Queue, JobStatus.Pending])(
    "treats %s as active",
    (status) => {
      expect(selectCurrentRun([job(1, JobStatus.Completed), job(2, status)])?.job.id).toBe("2");
    }
  );

  it("ignores finished runs older than the current run", () => {
    const result = selectCurrentRun([job(1, JobStatus.Failed), job(2, JobStatus.Running)]);
    expect(result?.job.id).toBe("2");
    expect(result?.queuedBehind).toEqual([]);
  });

  it("does not count a run that is itself running or waiting for approval as queued behind", () => {
    const result = selectCurrentRun([
      job(1, JobStatus.WaitingApproval),
      job(2, JobStatus.Running),
      job(3, JobStatus.WaitingApproval),
      job(4, JobStatus.Queue),
      job(5, JobStatus.Pending),
    ]);
    expect(result?.job.id).toBe("1");
    expect(result?.queuedBehind.map((queued) => queued.id)).toEqual(["4", "5"]);
  });
});
