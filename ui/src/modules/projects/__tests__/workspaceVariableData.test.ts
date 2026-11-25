import { apiPost } from "@/modules/api/apiWrapper";
import projectService from "../projectService";

jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));

const post = apiPost as jest.Mock;

const edges = (...nodes: unknown[]) => ({ edges: nodes.map((node) => ({ node })) });
const entry = (key: string, extra = {}) => ({ id: key, key, category: "ENV", sensitive: false, hcl: false, ...extra });

function answer() {
  return {
    isError: false,
    data: {
      organization: {
        edges: [
          {
            node: {
              globalvarPlain: edges(entry("TF_VAR_env", { value: "prod" })),
              globalvarHidden: edges(),
              collection: edges(
                {
                  id: "c1",
                  name: "payments-common",
                  priority: 10,
                  itemPlain: edges(entry("LOG_LEVEL", { value: "info" })),
                  itemHidden: edges(entry("DB_PASSWORD", { sensitive: true })),
                  reference: edges(
                    { scope: "PROJECT", workspace: edges(), project: edges({ id: "p1" }) },
                    { scope: "PROJECT", workspace: edges(), project: edges({ id: "other" }) }
                  ),
                },
                {
                  id: "c2",
                  name: "someone-elses",
                  priority: 1,
                  itemPlain: edges(entry("X", { value: "1" })),
                  itemHidden: edges(),
                  reference: edges({ scope: "WORKSPACE", workspace: edges({ id: "another-ws" }), project: edges() }),
                }
              ),
              workspace: edges({
                project: edges({ id: "p1" }),
                variablePlain: edges(entry("AWS_REGION", { value: "eu-west-1" })),
                variableHidden: edges(entry("TOKEN", { sensitive: true })),
              }),
            },
          },
        ],
      },
    },
  };
}

describe("getWorkspaceVariableData", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    post.mockResolvedValue(answer());
  });

  it("never asks for the value of a sensitive variable, because the API denies it and fails the response", async () => {
    await projectService.getWorkspaceVariableData("org", "ws-1");

    const query: string = post.mock.calls[0][1].query;
    for (const name of ["globalvar", "item", "variable"]) {
      expect(query).toContain(
        `${name}Hidden: ${name}(filter: "sensitive==true") { edges { node { id key category sensitive hcl } } }`
      );
      expect(query).toContain(`${name}Plain: ${name}(filter: "sensitive==false")`);
    }
  });

  it("merges plain and hidden rows, with the sensitive ones carrying no value", async () => {
    const response = await projectService.getWorkspaceVariableData("org", "ws-1");

    expect(response.data?.variables.map((item) => item.key).sort()).toEqual(["AWS_REGION", "TOKEN"]);
    expect(response.data?.variables.find((item) => item.key === "TOKEN")).toMatchObject({
      sensitive: true,
      value: null,
    });
    expect(response.data?.globals).toHaveLength(1);
  });

  it("keeps only the collections that apply to this workspace, at the narrowest scope that matches", async () => {
    const response = await projectService.getWorkspaceVariableData("org", "ws-1");

    expect(response.data?.projectId).toBe("p1");
    expect(response.data?.collections).toHaveLength(1);
    expect(response.data?.collections[0]).toMatchObject({ id: "c1", scope: "PROJECT", priority: 10 });
    expect(response.data?.collections[0].items.map((item) => item.key).sort()).toEqual(["DB_PASSWORD", "LOG_LEVEL"]);
  });

  it("reports a failed request instead of an empty workspace", async () => {
    post.mockResolvedValue({ isError: true, error: { message: "boom" } });

    const response = await projectService.getWorkspaceVariableData("org", "ws-1");

    expect(response.isError).toBe(true);
  });
});
