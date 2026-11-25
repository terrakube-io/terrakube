import { createdWithin } from "../jobWindow";

describe("createdWithin", () => {
  it("keeps jobs created since that many days before now", () => {
    expect(createdWithin(90, new Date("2026-11-22T12:00:00.000Z"))).toBe("createdDate=ge=2026-08-24T12:00:00.000Z");
  });
});
