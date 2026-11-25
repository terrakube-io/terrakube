import { apiPost } from "@/modules/api/apiWrapper";
import projectService from "../projectService";

jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));
jest.mock("@/config/axiosConfig", () => ({ __esModule: true, default: {} }));

const post = apiPost as jest.Mock;

describe("listCollectionsForProject", () => {
  beforeEach(() => jest.clearAllMocks());

  it("quotes the project id in the filter and reads the variable count from the total", async () => {
    post.mockResolvedValue({
      isError: false,
      responseCode: 200,
      data: {
        organization: {
          edges: [
            {
              node: {
                collection: {
                  edges: [
                    {
                      node: {
                        id: "c1",
                        name: "Shared",
                        priority: 3,
                        item: { pageInfo: { totalRecords: 7 } },
                        reference: { edges: [{ node: { id: "r1" } }] },
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

    const result = await projectService.listCollectionsForProject("org", 'p"1');

    expect(post.mock.calls[0][1].variables.filter).toBe(`scope=='PROJECT';project.id=="p\\"1"`);
    expect(post.mock.calls[0][1].query).toContain('item(first: "1", after: "0")');
    expect(result.data).toEqual([
      { id: "c1", name: "Shared", description: undefined, priority: 3, variableCount: 7, referenceId: "r1" },
    ]);
  });
});
