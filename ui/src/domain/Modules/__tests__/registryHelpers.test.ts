import { compareVersionsDesc, parseProviderDescription } from "../registryHelpers";

describe("parseProviderDescription", () => {
  it("drops a .git suffix so links built on the URL resolve", () => {
    expect(parseProviderDescription("Source: https://github.com/acme/terraform-provider-x.git").source).toEqual({
      url: "https://github.com/acme/terraform-provider-x",
      label: "acme/terraform-provider-x",
    });
  });
});

describe("compareVersionsDesc", () => {
  it("sorts newest first, numerically, with releases before their pre-releases", () => {
    expect(["1.9.0", "v1.10.0", "2.0.0-beta.2", "2.0.0", "2.0.0-beta.10"].sort(compareVersionsDesc)).toEqual([
      "2.0.0",
      "2.0.0-beta.10",
      "2.0.0-beta.2",
      "v1.10.0",
      "1.9.0",
    ]);
  });
});
