import { buildResourceOptions, getResourceAddress, parseState, parseOldState, setupWorkspaceIncludes } from "../workspaceDataUtils";
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

  it("returns type.name[\"string_key\"] when index is a string", () => {
    const resource = { ...baseResource, module: "root_module", name: "users", index: "name.surname" };
    expect(getResourceAddress(resource)).toBe('random_pet.users["name.surname"]');
  });

  it("returns type.name[0] when index is a number", () => {
    const resource = { ...baseResource, module: "root_module", name: "servers", index: 0 };
    expect(getResourceAddress(resource)).toBe("random_pet.servers[0]");
  });

  it("returns module.type.name[\"string_key\"] for child module resource with index", () => {
    const resource = { ...baseResource, module: "module.cluster", name: "node", index: "primary" };
    expect(getResourceAddress(resource)).toBe('module.cluster.random_pet.node["primary"]');
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

  it("returns matching value and label for each resource including indexed resources", () => {
    const resources = [
      { ...baseResource, name: "users", index: "alice.smith", module: "root_module" },
      { ...baseResource, name: "users", index: "name.surname", module: "root_module" },
    ];
    expect(buildResourceOptions(resources)).toEqual([
      { value: 'random_pet.users["alice.smith"]', label: 'random_pet.users["alice.smith"]' },
      { value: 'random_pet.users["name.surname"]', label: 'random_pet.users["name.surname"]' },
    ]);
  });
});

describe("parseOldState", () => {
  it("correctly extracts index_key from all instances of each resource", () => {
    const rawState = {
      version: 4,
      terraform_version: "1.15.8",
      resources: [
        {
          mode: "managed",
          type: "random_pet",
          name: "user_pet",
          provider: 'provider["registry.terraform.io/hashicorp/random"]',
          instances: [
            { index_key: "alice.smith", attributes: { id: "alice" } },
            { index_key: "bob.jones", attributes: { id: "bob" } },
            { index_key: "name.surname", attributes: { id: "surname" } },
          ],
        },
        {
          mode: "managed",
          type: "terraform_data",
          name: "standalone",
          provider: 'provider["terraform.io/builtin/terraform"]',
          instances: [{ attributes: { id: "single" } }],
        },
      ],
    };

    const result = parseOldState(rawState);
    expect(result.resources).toHaveLength(4);
    expect(result.resources[0]).toMatchObject({
      name: "user_pet",
      type: "random_pet",
      index: "alice.smith",
    });
    expect(result.resources[1]).toMatchObject({
      name: "user_pet",
      type: "random_pet",
      index: "bob.jones",
    });
    expect(result.resources[2]).toMatchObject({
      name: "user_pet",
      type: "random_pet",
      index: "name.surname",
    });
    expect(result.resources[3]).toMatchObject({
      name: "standalone",
      type: "terraform_data",
      index: undefined,
    });
  });
});

describe("parseState", () => {
  it("extracts index field from root_module resources", () => {
    const jsonState = {
      values: {
        root_module: {
          resources: [
            {
              name: "users",
              type: "terraform_data",
              index: "name.surname",
              provider_name: "terraform.io/builtin/terraform",
              values: { id: "1" },
            },
          ],
        },
      },
    };

    const result = parseState(jsonState);
    expect(result.resources).toHaveLength(1);
    expect(result.resources[0]).toMatchObject({
      name: "users",
      type: "terraform_data",
      index: "name.surname",
    });
  });
});

describe("setupWorkspaceIncludes job and history titles", () => {
  const createPayload = (jobVia?: string, historyVia?: string) => ({
    data: {
      attributes: {
        iacType: "terraform",
      },
    },
    included: [
      {
        id: "job-1",
        type: "job",
        attributes: {
          via: jobVia,
          commitId: "abcdef123456",
          createdDate: "2026-09-08T12:00:00Z",
          updatedDate: "2026-09-08T12:05:00Z",
        },
      },
      {
        id: "hist-1",
        type: "history",
        attributes: {
          via: historyVia,
          createdDate: "2026-09-08T11:00:00Z",
        },
      },
    ],
  });

  const runSetup = async (payload: any) => {
    let capturedJobs: any[] = [];
    let capturedHistory: any[] = [];
    await setupWorkspaceIncludes(
      payload,
      () => {},
      (jobs) => {
        capturedJobs = jobs;
      },
      () => {},
      (history) => {
        capturedHistory = history;
      },
      () => {},
      [],
      () => {},
      () => {},
      () => {},
      "",
      { get: () => Promise.resolve({ data: {} }) },
      () => {},
      () => {},
      () => {},
      false,
      () => {},
      () => {},
      () => {},
      () => {},
      () => {}
    );
    return { jobs: capturedJobs, history: capturedHistory };
  };

  it("generates dynamic Triggered via <Source> titles for non-UI webhook runs", async () => {
    const { jobs, history } = await runSetup(createPayload("Github", "Gitlab"));
    expect(jobs[0].title).toBe("Triggered via GitHub");
    expect(history[0].title).toBe("Triggered via GitLab");
  });

  it("formats Azure DevOps and CLI titles properly", async () => {
    const { jobs, history } = await runSetup(createPayload("AzureDevops", "CLI"));
    expect(jobs[0].title).toBe("Triggered via Azure DevOps");
    expect(history[0].title).toBe("Triggered via CLI");
  });

  it("keeps 'Queue manually using Terraform' for UI runs or undefined via", async () => {
    const { jobs, history } = await runSetup(createPayload("UI", undefined));
    expect(jobs[0].title).toBe("Queue manually using Terraform");
    expect(history[0].title).toBe("Queue manually using Terraform");
  });
});

