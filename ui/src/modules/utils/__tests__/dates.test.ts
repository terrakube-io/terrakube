import { formatDateTimeWithZone } from "../dates";

describe("formatDateTimeWithZone", () => {
  it("gives the date, the time and the timezone", () => {
    const text = formatDateTimeWithZone("2026-10-17T19:56:00.000Z");
    expect(text).toContain("2026");
    expect(text).toMatch(/\d{1,2}:\d{2}/);
    expect(text).toMatch(/UTC|GMT|[A-Z]{3,5}/);
  });

  it("is empty when there is no date and returns an unreadable one as it came", () => {
    expect(formatDateTimeWithZone(undefined)).toBe("");
    expect(formatDateTimeWithZone(null)).toBe("");
    expect(formatDateTimeWithZone("not a date")).toBe("not a date");
  });
});
