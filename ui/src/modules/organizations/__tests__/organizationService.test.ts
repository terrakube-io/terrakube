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
});
