import { axiosGraphQL } from "@/config/axiosConfig";
import { apiGet } from "@/modules/api/apiWrapper";
import organizationService from "../organizationService";

jest.mock("@/config/axiosConfig", () => ({
  axiosGraphQL: { post: jest.fn() },
}));
jest.mock("@/modules/api/apiWrapper", () => ({
  apiGet: jest.fn(),
}));

const mockPost = axiosGraphQL.post as jest.Mock;
const mockApiGet = apiGet as jest.Mock;

describe("organizationService.listOrganizationSummaries", () => {
  it("maps the compact summary endpoint to the picker model without workspace edges", async () => {
    mockApiGet.mockResolvedValue({
      isError: false,
      responseCode: 200,
      data: [
        {
          id: "org-1",
          name: "acme",
          description: "desc",
          executionMode: "remote",
          icon: "FaBuilding:#000000",
          workspaceCount: 4,
          statusCounts: { failed: 2, completed: 1, NeverExecuted: 1 },
        },
      ],
    });

    const result = await organizationService.listOrganizationSummaries();

    expect(mockApiGet).toHaveBeenCalledWith("/ui/v1/organizations/summary", { contentType: "application/json" });
    expect(result).toEqual([
      {
        id: "org-1",
        name: "acme",
        description: "desc",
        executionMode: "remote",
        icon: "FaBuilding:#000000",
        workspaceCount: 4,
        workspaceStatusCounts: { failed: 2, completed: 1, NeverExecuted: 1 },
      },
    ]);
  });

  it("throws a useful error when the summary endpoint fails", async () => {
    mockApiGet.mockResolvedValue({
      isError: true,
      responseCode: 403,
      error: { message: "Forbidden" },
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
