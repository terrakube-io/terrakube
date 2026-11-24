import { DeliveryJob, formatSpan, median, summarizeDelivery } from "../deliveryStats";

const job = (status: string, created: string, updated?: string, approved?: string): DeliveryJob => ({
  status,
  createdDate: created,
  updatedDate: updated,
  approvedAt: approved,
});

describe("median", () => {
  it("takes the middle value, or the mean of the two in the middle", () => {
    expect(median([3, 1, 2])).toBe(2);
    expect(median([4, 1, 2, 3])).toBe(2.5);
  });

  it("is null for nothing, and ignores values that are not times", () => {
    expect(median([])).toBeNull();
    expect(median([NaN, -5])).toBeNull();
  });
});

describe("summarizeDelivery", () => {
  const jobs = [
    job("completed", "2026-11-01T10:00:00Z", "2026-11-01T10:10:00Z", "2026-11-01T10:02:00Z"),
    job("completed", "2026-11-02T10:00:00Z", "2026-11-02T10:30:00Z", "2026-11-02T10:06:00Z"),
    job("noChanges", "2026-11-03T10:00:00Z", "2026-11-03T10:01:00Z"),
    job("failed", "2026-11-04T10:00:00Z", "2026-11-04T10:02:00Z"),
    job("cancelled", "2026-11-05T10:00:00Z", "2026-11-05T10:01:00Z"),
    job("running", "2026-11-06T10:00:00Z", "2026-11-06T10:00:30Z"),
  ];

  it("counts a failure against the finished runs and leaves out runs a person stopped or that still run", () => {
    // Three of four finished runs did not fail.
    expect(summarizeDelivery(jobs, 6).succeededPercent).toBe(75);
  });

  it("takes the medians from the runs that have the times", () => {
    const delivery = summarizeDelivery(jobs, 6);

    // Approved after 2 and 6 minutes.
    expect(delivery.medianApproveMs).toBe(4 * 60 * 1000);
    // Finished after 10, 30, 1 and 2 minutes: the middle two are 2 and 10.
    expect(delivery.medianRunMs).toBe(6 * 60 * 1000);
  });

  it("has nothing to say about a window without finished runs", () => {
    const delivery = summarizeDelivery([job("running", "2026-11-06T10:00:00Z")], 1);

    expect(delivery).toMatchObject({ succeededPercent: null, medianApproveMs: null, medianRunMs: null });
  });

  it("says when more runs started than were read", () => {
    expect(summarizeDelivery(jobs, 6).partial).toBe(false);
    expect(summarizeDelivery(jobs, 1200).partial).toBe(true);
    // A read that came back with as many rows as Elide returns may have been cut short, whatever the total says.
    expect(
      summarizeDelivery(
        Array.from({ length: 500 }, () => jobs[0]),
        500,
        500
      ).partial
    ).toBe(true);
    expect(
      summarizeDelivery(
        Array.from({ length: 499 }, () => jobs[0]),
        499,
        500
      ).partial
    ).toBe(false);
  });
});

describe("formatSpan", () => {
  it("uses the unit a person would say", () => {
    expect(formatSpan(null)).toBe("-");
    expect(formatSpan(45_000)).toBe("45 s");
    expect(formatSpan(22 * 60_000)).toBe("22 min");
    expect(formatSpan(3 * 3_600_000)).toBe("3 h");
    expect(formatSpan(72 * 3_600_000)).toBe("3 d");
  });
});
