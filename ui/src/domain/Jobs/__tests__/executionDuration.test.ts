import { formatExecutionDuration } from "../executionDuration";

describe("formatExecutionDuration", () => {
  it.each([undefined, null, -1, NaN, Infinity])("leaves missing or invalid duration %s unknown", (value) => {
    expect(formatExecutionDuration(value)).toBe("—");
  });

  it.each([
    [0, "<1s"],
    [999, "<1s"],
    [1000, "1s"],
    [30000, "30s"],
    [120000, "2m"],
    [263000, "4m 23s"],
    [202000, "3m 22s"],
    [3600000, "1h"],
    [3723000, "1h 2m 3s"],
  ])("formats recorded milliseconds %s as %s", (value, expected) => {
    expect(formatExecutionDuration(value)).toBe(expected);
  });
});
