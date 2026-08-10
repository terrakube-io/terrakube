import formatVersion from "./formatVersion";

describe("formatVersion", () => {
  describe("plain exact versions get a v prefix", () => {
    it.each([
      ["1.2.3", "v1.2.3"],
      ["1", "v1"],
      ["0.15.0", "v0.15.0"],
      [" 1.2.3 ", "v1.2.3"],
    ])("%s -> %s", (input, expected) => expect(formatVersion(input)).toBe(expected));
  });

  describe("already v-prefixed exact versions are normalized, not doubled", () => {
    it.each([
      ["v1.2.3", "v1.2.3"],
      ["V1.2.3", "v1.2.3"],
    ])("%s -> %s", (input, expected) => expect(formatVersion(input)).toBe(expected));
  });

  describe("constraints are returned unchanged", () => {
    it.each([">=1", "~>1.11.0", "^1.2.3", "1.2.3 - 2.3.4", "*", "1.x", "latest"])("leaves %s as-is", (input) =>
      expect(formatVersion(input)).toBe(input)
    );
  });
});
