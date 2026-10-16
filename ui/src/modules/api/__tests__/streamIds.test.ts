import { compareStreamIds } from "../streamIds";

describe("compareStreamIds", () => {
  it("orders by milliseconds then sequence", () => {
    expect(compareStreamIds("10-0", "9-5")).toBe(1);
    expect(compareStreamIds("10-1", "10-2")).toBe(-1);
    expect(compareStreamIds("10-2", "10-2")).toBe(0);
  });

  it("compares numerically, not as text", () => {
    expect(compareStreamIds("1000-0", "999-0")).toBe(1);
  });

  it("refuses to order ids that are not stream ids", () => {
    expect(compareStreamIds("abc", "10-0")).toBeUndefined();
    expect(compareStreamIds("1", "2")).toBeUndefined();
  });
});
