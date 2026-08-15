import { buildResourceOptions, getResourceAddress } from "../workspaceDataUtils";
import { Resource } from "../../types";

const baseResource: Resource = {
  name: "example",
  provider: "registry.terraform.io/hashicorp/random",
  type: "random_pet",
  values: {},
  depends_on: "",
  showDrawer: () => {},
};

describe("getResourceAddress", () => {
  it("returns type.name for a root module resource", () => {
    const resource = { ...baseResource, module: "root_module" };
    expect(getResourceAddress(resource)).toBe("random_pet.example");
  });

  it("returns module.address.type.name for a child module resource", () => {
    const resource = { ...baseResource, module: "module.instances" };
    expect(getResourceAddress(resource)).toBe("module.instances.random_pet.example");
  });
});

describe("buildResourceOptions", () => {
  it("sorts options alphabetically by address, regardless of input order", () => {
    const resources = [
      { ...baseResource, name: "zebra", module: "root_module" },
      { ...baseResource, name: "alpha", module: "root_module" },
      { ...baseResource, name: "middle", module: "root_module" },
    ];

    expect(buildResourceOptions(resources).map((option) => option.value)).toEqual([
      "random_pet.alpha",
      "random_pet.middle",
      "random_pet.zebra",
    ]);
  });

  it("returns matching value and label for each resource", () => {
    const resources = [{ ...baseResource, name: "example", module: "root_module" }];
    expect(buildResourceOptions(resources)).toEqual([{ value: "random_pet.example", label: "random_pet.example" }]);
  });
});
