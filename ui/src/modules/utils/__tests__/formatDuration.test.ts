import { formatDuration } from "../dates";

describe("formatDuration", () => {
  it.each([
    ["2026-01-01T00:00:00Z", "2026-01-01T00:00:42Z", "42s"],
    ["2026-01-01T00:00:00Z", "2026-01-01T00:03:05Z", "3m 05s"],
    ["2026-01-01T00:00:00Z", "2026-01-01T01:02:30Z", "1h 02m"],
  ])("%s → %s is %s", (start, end, expected) => {
    expect(formatDuration(start, end)).toBe(expected);
  });

  it("returns null for missing, invalid or reversed times", () => {
    expect(formatDuration(undefined, "2026-01-01T00:00:00Z")).toBeNull();
    expect(formatDuration("nope", "2026-01-01T00:00:00Z")).toBeNull();
    expect(formatDuration("2026-01-01T00:01:00Z", "2026-01-01T00:00:00Z")).toBeNull();
  });
});
