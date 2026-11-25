import { PaletteItem, loadRecents, matchesQuery, pageItems, rank, rememberRecent } from "../paletteModel";

const item = (title: string, subtitle?: string, key = title): PaletteItem => ({
  key,
  kind: "workspace",
  title,
  subtitle,
  to: `/w/${key}`,
});

describe("matchesQuery", () => {
  it("matches every word, in any order, ignoring case, against title and subtitle", () => {
    expect(matchesQuery(item("ledger-db", "JXP"), "jxp ledger")).toBe(true);
    expect(matchesQuery(item("ledger-db", "JXP"), "LEDGER")).toBe(true);
    expect(matchesQuery(item("ledger-db", "JXP"), "ledger gateway")).toBe(false);
  });

  it("matches everything when there is nothing to match", () => {
    expect(matchesQuery(item("a"), "   ")).toBe(true);
  });
});

describe("rank", () => {
  it("puts names that start with the query first and otherwise keeps the order", () => {
    const ranked = rank([item("my-ledger"), item("ledger-db"), item("ledger-api")], "ledger");

    expect(ranked.map((entry) => entry.title)).toEqual(["ledger-db", "ledger-api", "my-ledger"]);
  });

  it("leaves the order alone for an empty query", () => {
    const items = [item("b"), item("a")];

    expect(rank(items, "")).toBe(items);
  });
});

describe("pageItems", () => {
  it("offers the pages of the current organization and all organizations", () => {
    expect(pageItems("org-1", "JXP").map((page) => page.title)).toEqual([
      "Projects",
      "Workspaces",
      "Registry",
      "Settings",
      "All organizations",
    ]);
    expect(pageItems("org-1", "JXP")[0].to).toBe("/organizations/org-1/projects");
  });

  it("offers only the organization list outside an organization", () => {
    expect(pageItems(null).map((page) => page.title)).toEqual(["All organizations"]);
  });
});

describe("recents", () => {
  beforeEach(() => localStorage.clear());

  it("drops stored entries that are not an address inside the app", () => {
    const good = { key: "a", kind: "workspace", title: "a", to: "/organizations/o/workspaces/a" };
    localStorage.setItem(
      "terrakube-palette-recent",
      JSON.stringify([
        good,
        { ...good, key: "protocol-relative", to: "//evil.example/x" },
        { ...good, key: "absolute", to: "https://evil.example/x" },
        { ...good, key: "backslash", to: "/\\evil.example" },
        { ...good, key: "no-title", title: 5 },
        { ...good, key: "odd-kind", kind: "script" },
      ])
    );

    expect(loadRecents().map((entry) => entry.key)).toEqual(["a"]);
  });

  it("remembers what was opened, newest first, once each", () => {
    rememberRecent(item("a"));
    rememberRecent(item("b"));
    rememberRecent(item("a"));

    expect(loadRecents().map((entry) => entry.title)).toEqual(["a", "b"]);
  });

  it("keeps only the last few", () => {
    for (let index = 0; index < 10; index++) rememberRecent(item(`w${index}`));

    expect(loadRecents()).toHaveLength(6);
    expect(loadRecents()[0].title).toBe("w9");
  });

  it("survives damaged storage", () => {
    localStorage.setItem("terrakube-palette-recent", "{not json");
    expect(loadRecents()).toEqual([]);

    localStorage.setItem(
      "terrakube-palette-recent",
      JSON.stringify([{ nope: true }, { key: "k", to: "/x", title: "ok", kind: "page" }])
    );
    expect(loadRecents().map((entry) => entry.title)).toEqual(["ok"]);
  });
});

describe("rememberRecent when storage refuses", () => {
  it("does not throw, so choosing a result still navigates", () => {
    const spy = jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("QuotaExceededError");
    });
    expect(() => rememberRecent(item("a"))).not.toThrow();
    spy.mockRestore();
  });
});
