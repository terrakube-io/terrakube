import { InboxItem, emptyCounts, sortItems, totalCount } from "../inboxModel";

const item = (name: string, at: string | undefined, kind: InboxItem["kind"] = "failed"): InboxItem => ({
  key: `${kind}:${name}`,
  kind,
  orgId: "o",
  orgName: "JXP",
  workspaceId: name,
  name,
  at,
});

describe("sortItems", () => {
  it("lists the run that has waited longest first, because someone is blocked on it", () => {
    const sorted = sortItems("waiting", [
      item("recent", "2026-11-08T10:00:00Z", "waiting"),
      item("oldest", "2026-11-08T06:00:00Z", "waiting"),
    ]);

    expect(sorted.map((entry) => entry.name)).toEqual(["oldest", "recent"]);
  });

  it("lists the most recent failure first", () => {
    const sorted = sortItems("failed", [item("old", "2026-11-08T06:00:00Z"), item("new", "2026-11-08T10:00:00Z")]);

    expect(sorted.map((entry) => entry.name)).toEqual(["new", "old"]);
  });

  it("breaks ties by name and puts items with no time last among recent ones", () => {
    const sorted = sortItems("failed", [
      item("b", "2026-11-08T10:00:00Z"),
      item("a", "2026-11-08T10:00:00Z"),
      item("none", undefined),
    ]);

    expect(sorted.map((entry) => entry.name)).toEqual(["a", "b", "none"]);
  });

  it("does not change the list it was given", () => {
    const items = [item("b", "2026-11-08T10:00:00Z"), item("a", "2026-11-08T11:00:00Z")];

    sortItems("failed", items);

    expect(items.map((entry) => entry.name)).toEqual(["b", "a"]);
  });
});

describe("counts", () => {
  it("adds the three kinds", () => {
    expect(totalCount({ waiting: 1, failed: 2, policy: 3 })).toBe(6);
    expect(totalCount(emptyCounts())).toBe(0);
  });
});
