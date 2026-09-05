import { axiosGraphQL } from "@/config/axiosConfig";
import organizationService from "../organizationService";

jest.mock("@/config/axiosConfig", () => ({
  axiosGraphQL: { post: jest.fn() },
}));
const mockPost = axiosGraphQL.post as jest.Mock;

describe("organizationService.listOrganizationSummaries", () => {
  it("maps the organization GraphQL response to the picker model", async () => {
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
                  icon: "FaBuilding:#000000",
                  workspace: {
                    edges: [
                      { node: { id: "workspace-1", lastJobStatus: "failed" } },
                      { node: { id: "workspace-2", lastJobStatus: "completed" } },
                      { node: { id: "workspace-3" } },
                    ],
                  },
                },
              },
            ],
          },
        },
      },
    });

    const result = await organizationService.listOrganizationSummaries();

    expect(mockPost).toHaveBeenCalledWith(
      "",
      expect.objectContaining({ query: expect.stringContaining("lastJobStatus") }),
      { headers: { "Content-Type": "application/json" } }
    );
    expect(result).toEqual([
      {
        id: "org-1",
        name: "acme",
        description: "desc",
        executionMode: "remote",
        icon: "FaBuilding:#000000",
        workspaceCount: 3,
        workspaceStatusCounts: { failed: 1, completed: 1, NeverExecuted: 1 },
      },
    ]);
  });

  it("throws a useful error when GraphQL returns errors", async () => {
    mockPost.mockResolvedValue({
      data: { errors: [{ message: "Forbidden" }] },
    });

    await expect(organizationService.listOrganizationSummaries()).rejects.toThrow("Forbidden");
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
