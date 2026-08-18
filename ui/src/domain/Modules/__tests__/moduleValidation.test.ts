import { isValidModuleSystem } from "../moduleValidation";

describe("isValidModuleSystem", () => {
  it("accepts a plain alphanumeric system", () => {
    expect(isValidModuleSystem("aws")).toBe(true);
  });

  it("accepts azurerm", () => {
    expect(isValidModuleSystem("azurerm")).toBe(true);
  });

  it("rejects a hyphenated repository-style value", () => {
    expect(isValidModuleSystem("aws-ecs")).toBe(false);
  });

  it("rejects an empty string", () => {
    expect(isValidModuleSystem("")).toBe(false);
  });

  it("rejects a value longer than 64 characters", () => {
    expect(isValidModuleSystem("a".repeat(65))).toBe(false);
  });

  it("accepts a value exactly 64 characters long", () => {
    expect(isValidModuleSystem("a".repeat(64))).toBe(true);
  });

  it("rejects values with underscores or other punctuation", () => {
    expect(isValidModuleSystem("aws_ecs")).toBe(false);
  });
});
