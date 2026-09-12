import { apiPost } from "@/modules/api/apiWrapper";
import workspaceService from "../workspaceService";

jest.mock("@/modules/api/apiWrapper", () => ({
  __esModule: true,
  apiPost: jest.fn(),
}));

const mockApiPost = apiPost as jest.Mock;

describe("workspaceService.listWorkspaces", () => {
  it("maps the locked field from the GraphQL response", async () => {
    mockApiPost.mockResolvedValue({
      isError: false,
      responseCode: 200,
      data: {
        organization: {
          edges: [
            {
              node: {
                id: "org-1",
                name: "acme",
                workspace: {
                  edges: [
                    {
                      node: {
                        id: "ws-1",
                        name: "locked-ws",
                        description: null,
                        source: "",
                        branch: "main",
                        terraformVersion: "1.9.2",
                        iacType: "terraform",
                        lastJobStatus: null,
                        lastJobDate: null,
                        locked: true,
                        policyComplianceStatus: "COMPLIANT",
                        workspaceTag: { edges: [] },
                        project: { edges: [] },
                      },
                    },
                    {
                      node: {
                        id: "ws-2",
                        name: "unlocked-ws",
                        description: null,
                        source: "",
                        branch: "main",
                        terraformVersion: "1.9.2",
                        iacType: "terraform",
                        lastJobStatus: null,
                        lastJobDate: null,
                        locked: false,
                        policyComplianceStatus: "NON_COMPLIANT",
                        workspaceTag: { edges: [] },
                        project: { edges: [] },
                      },
                    },
                  ],
                },
              },
            },
          ],
        },
      },
    });

    const result = await workspaceService.listWorkspaces("org-1");

    expect(result.data!.workspaces.find((w) => w.id === "ws-1")?.locked).toBe(true);
    expect(result.data!.workspaces.find((w) => w.id === "ws-1")?.policyComplianceStatus).toBe("COMPLIANT");
    expect(result.data!.workspaces.find((w) => w.id === "ws-2")?.locked).toBe(false);
    expect(result.data!.workspaces.find((w) => w.id === "ws-2")?.policyComplianceStatus).toBe("NON_COMPLIANT");
  });
});
