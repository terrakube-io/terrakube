import containsFilter from "../containsFilter";

describe("containsFilter", () => {
  it("keeps the words and the spaces between them in one quoted value", () => {
    expect(containsFilter("prod network")).toBe('"*prod network*"');
  });

  it("keeps punctuation that would end an unquoted value, and escapes quotes and backslashes", () => {
    expect(containsFilter("app(1), a;b")).toBe('"*app(1), a;b*"');
    expect(containsFilter('say "hi"\\')).toBe('"*say \\"hi\\"\\\\*"');
  });

  it("does not let the person add their own wildcards", () => {
    expect(containsFilter("a*b")).toBe('"*a b*"');
  });

  it("has nothing to search for in an empty or blank text", () => {
    expect(containsFilter("")).toBeNull();
    expect(containsFilter("  *  ")).toBeNull();
  });
});
