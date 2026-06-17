import { validateTerraformVersion } from "./versionValidation";

const valid = async (value: string) => expect(validateTerraformVersion()(null, value)).resolves.toBeUndefined();

const invalid = async (value: string) =>
  expect(validateTerraformVersion()(null, value)).rejects.toThrow("Invalid version format");

describe("validateTerraformVersion", () => {
  describe("empty / absent values are allowed (optional field)", () => {
    it("accepts undefined", () => expect(validateTerraformVersion()(null, undefined)).resolves.toBeUndefined());
    it("accepts empty string", () => expect(validateTerraformVersion()(null, "")).resolves.toBeUndefined());
  });

  describe("plain semantic versions", () => {
    it.each(["1.11.0", "1.11", "1", "0.15.0", "1.2.3.4"])("accepts %s", valid);
  });

  describe("NPM x-range", () => {
    it.each(["*", "1.x", "1.X", "1.2.x", "1.2.*", "1.2.X"])("accepts %s", valid);
  });

  describe("NPM tilde range", () => {
    it.each(["~1", "~1.2", "~1.2.3"])("accepts %s", valid);
  });

  describe("NPM caret range", () => {
    it.each(["^1.2.3", "^0.2.5", "^0.0.4", "^1"])("accepts %s", valid);
  });

  describe("NPM hyphen range", () => {
    it.each(["1.2.3 - 2.3.4", "1.0 - 2.0"])("accepts %s", valid);
  });

  describe("primitive operators and CocoaPods ~>", () => {
    it.each([
      ">=1.5.7",
      ">1.5.7",
      "<=1.9.0",
      "<1.9.0",
      "=1.9.0",
      "!=1.5.7",
      "~>1.11.0",
      "~> 1.0",
      ">=1.5.7 <1.9.0",
      ">=1.5.7 <=1.9.0",
    ])("accepts %s", valid);
  });

  describe("Ivy version ranges", () => {
    it.each(["[1.0,2.0]", "[1.0,2.0[", "]1.0,2.0]", "]1.0,2.0[", "[1.0,)", "]1.0,)", "(,2.0]", "(,2.0["])(
      "accepts %s",
      valid
    );
  });

  describe("invalid formats are rejected", () => {
    it.each(["latest", "v1.11.0", "1.11.0-rc1", "abc", ">=", "~>", "[]", "1..0", "x", "X"])("rejects %s", invalid);
  });

  describe("existence check when availableVersions is specified", () => {
    const versions = ["1.11.0", "1.12.0"];
    const check = (v: string) => validateTerraformVersion(versions)(null, v);

    it("accepts a version that exists in the list", () => expect(check("1.11.0")).resolves.toBeUndefined());
    it("rejects a version that does not exist in the list", () => expect(check("1.99.99")).rejects.toThrow("not available"));
    it("accepts a constraint string even if it is not in the list", () => expect(check("~>1.11.0")).resolves.toBeUndefined());
    it("skips the existence check when the list is empty", () =>
      expect(validateTerraformVersion([])(null, "1.99.99")).resolves.toBeUndefined());
  });
});
