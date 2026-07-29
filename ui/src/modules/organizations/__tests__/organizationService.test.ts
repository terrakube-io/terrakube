import { axiosGraphQL } from "@/config/axiosConfig";
import organizationService from "../organizationService";

jest.mock("@/config/axiosConfig", () => ({
  axiosGraphQL: { post: jest.fn() },
}));

const mockPost = axiosGraphQL.post as jest.Mock;

describe("organizationService.listOrganizationsGraphQL", () => {
  it("maps workspace.edges.length into workspaceCount", async () => {
    mockPost.mockResolvedValue({
      data: {
        data: {
          organization: {
            edges: [
              {
                node: {
                  id: "org-1",
                  name: "acme",
                  description: "desc",
                  executionMode: "remote",
                  icon: "",
                  workspace: {
                    edges: [{ node: { id: "ws-1" } }, { node: { id: "ws-2" } }],
                  },
                },
              },
            ],
          },
        },
      },
    });

    const result = await organizationService.listOrganizationsGraphQL();

    expect(result[0].workspaceCount).toBe(2);
  });

  it("groups workspace.edges by lastJobStatus into workspaceStatusCounts, treating a missing status as NeverExecuted", async () => {
    mockPost.mockResolvedValue({
      data: {
        data: {
          organization: {
            edges: [
              {
                node: {
                  id: "org-1",
                  name: "acme",
                  description: "desc",
                  executionMode: "remote",
                  icon: "",
                  workspace: {
                    edges: [
                      { node: { id: "ws-1", lastJobStatus: "failed" } },
                      { node: { id: "ws-2", lastJobStatus: "failed" } },
                      { node: { id: "ws-3", lastJobStatus: "completed" } },
                      { node: { id: "ws-4", lastJobStatus: null } },
                    ],
                  },
                },
              },
            ],
          },
        },
      },
    });

    const result = await organizationService.listOrganizationsGraphQL();

    expect(result[0].workspaceStatusCounts).toEqual({
      failed: 2,
      completed: 1,
      NeverExecuted: 1,
    });
  });
});

describe("organizationService.listOrganizationTags", () => {
  it("flattens the organization's tag edges into a plain list", async () => {
    mockPost.mockResolvedValue({
      data: {
        data: {
          organization: {
            edges: [
              {
                node: {
                  tag: {
                    edges: [{ node: { id: "tag-1", name: "billing" } }, { node: { id: "tag-2", name: "platform" } }],
                  },
                },
              },
            ],
          },
        },
      },
    });

    const result = await organizationService.listOrganizationTags("org-1");

    expect(result).toEqual([
      { id: "tag-1", name: "billing" },
      { id: "tag-2", name: "platform" },
    ]);
  });

  it("returns an empty array when the organization has no tags", async () => {
    mockPost.mockResolvedValue({
      data: { data: { organization: { edges: [{ node: { tag: { edges: [] } } }] } } },
    });

    const result = await organizationService.listOrganizationTags("org-1");

    expect(result).toEqual([]);
  });

  it("throws when the GraphQL response contains errors", async () => {
    mockPost.mockResolvedValue({
      data: { errors: [{ message: "Organization not found" }] },
    });

    await expect(organizationService.listOrganizationTags("org-1")).rejects.toThrow("Organization not found");
  });
});
