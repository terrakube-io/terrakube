import { MAX_SAVED_VIEWS, addView, loadViews, removeView, validateViewName, viewQuery } from "../viewStore";

describe("saved views", () => {
  beforeEach(() => localStorage.clear());

  it("keeps only what is listed, not the page, size or layout", () => {
    const params = new URLSearchParams("q=pay&chip=attention&sort=-name&page=3&size=50&view=table");
    expect(viewQuery(params)).toBe("q=pay&chip=attention&sort=-name");
  });

  it("saves, lists per organization and deletes", () => {
    addView("org-a", "Failing", "chip=attention");
    addView("org-b", "Stale", "chip=stale");

    expect(loadViews("org-a")).toEqual([{ name: "Failing", query: "chip=attention" }]);
    expect(loadViews("org-b")).toEqual([{ name: "Stale", query: "chip=stale" }]);
    expect(removeView("org-a", "Failing")).toEqual([]);
    expect(loadViews("org-b")).toHaveLength(1);
  });

  it("rejects empty, long, duplicate (any case) and too many names", () => {
    const views = [{ name: "Failing", query: "chip=attention" }];
    expect(validateViewName("  ", views)).toMatch(/name/);
    expect(validateViewName("x".repeat(41), views)).toMatch(/40/);
    expect(validateViewName("failing", views)).toMatch(/exists/);
    expect(validateViewName("New", views)).toBeNull();
    const full = Array.from({ length: MAX_SAVED_VIEWS }, (_, i) => ({ name: `v${i}`, query: "q=a" }));
    expect(validateViewName("one more", full)).toMatch(/up to/);
  });

  it("does not add a view that fails validation", () => {
    addView("org", "Failing", "chip=attention");
    expect(addView("org", "FAILING", "chip=stale")).toHaveLength(1);
  });

  it("survives corrupt storage and strips unknown keys on load", () => {
    localStorage.setItem("terrakube-saved-views:projects:org", "{not json");
    expect(loadViews("org")).toEqual([]);
    localStorage.setItem(
      "terrakube-saved-views:projects:org",
      JSON.stringify([{ name: "A", query: "q=x&page=9" }, { name: 5 }, null])
    );
    expect(loadViews("org")).toEqual([{ name: "A", query: "q=x" }]);
  });
});
