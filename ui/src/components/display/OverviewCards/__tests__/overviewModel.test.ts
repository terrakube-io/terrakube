import { ringGradient, ringSegments } from "../overviewModel";

describe("ringSegments", () => {
  it("names running and queued, and puts only what is left, such as rejected or cancelled, under other", () => {
    const segments = ringSegments(31, { completed: 16, failed: 4, waiting: 1, running: 2, never: 6 });

    expect(segments.map((segment) => [segment.key, segment.count])).toEqual([
      ["completed", 16],
      ["failed", 4],
      ["waiting", 1],
      ["running", 2],
      ["other", 2],
      ["never", 6],
      ["noStatus", 0],
    ]);
  });

  it("keeps workspaces with no run status apart from the never executed ones and out of the leftover", () => {
    const segments = ringSegments(10, { completed: 4, failed: 1, waiting: 0, running: 0, never: 2, noStatus: 3 });

    expect(segments.map((segment) => [segment.key, segment.count])).toEqual([
      ["completed", 4],
      ["failed", 1],
      ["waiting", 0],
      ["running", 0],
      ["other", 0],
      ["never", 2],
      ["noStatus", 3],
    ]);
  });

  it("never draws a finished workspace as running or queued", () => {
    // 10 workspaces, none running or queued: the 3 that ended some other way are "other".
    const segments = ringSegments(10, { completed: 5, failed: 1, waiting: 1, running: 0, never: 0 });

    expect(segments.find((segment) => segment.key === "running")?.count).toBe(0);
    expect(segments.find((segment) => segment.key === "other")?.count).toBe(3);
  });

  it("never reports a negative remainder when the counts overshoot a moving total", () => {
    expect(ringSegments(5, { completed: 4, failed: 2, waiting: 0, running: 0, never: 0 })[4].count).toBe(0);
  });
});

describe("ringGradient", () => {
  it("is one grey ring for an organization without workspaces", () => {
    expect(ringGradient(ringSegments(0, { completed: 0, failed: 0, waiting: 0, running: 0, never: 0 }))).toMatch(
      /^conic-gradient\(var\(--tk-text-quaternary/
    );
  });

  it("gives each state the share of the ring that its workspaces have, and skips empty ones", () => {
    const gradient = ringGradient(ringSegments(4, { completed: 3, failed: 1, waiting: 0, running: 0, never: 0 }));

    expect(gradient).toContain("0.00% 75.00%");
    expect(gradient).toContain("75.00% 100.00%");
    expect(gradient).not.toContain("waiting");
  });
});
