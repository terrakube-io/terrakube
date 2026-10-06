import axiosInstance, { axiosAdmin } from "@/config/axiosConfig";
import {
  cancelCascade,
  getAdminErrorMessage,
  getCascadeEdges,
  getCascadeNodes,
  listCascadesForWorkspace,
  resumeNode,
  retryNode,
} from "../cascadeService";

jest.mock("@/config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn() },
  axiosAdmin: { post: jest.fn() },
  getErrorMessage: () => "fallback error",
}));

const mockGet = axiosInstance.get as jest.Mock;
const mockAdminPost = axiosAdmin.post as jest.Mock;

beforeEach(() => {
  mockGet.mockReset();
  mockAdminPost.mockReset();
});

describe("listCascadesForWorkspace", () => {
  it("maps each cascade and sorts most-recent-first via the API's own sort param", async () => {
    mockGet.mockResolvedValue({
      data: {
        data: [
          {
            id: "cascade-1",
            attributes: { status: "DEGRADED", createdDate: "2026-01-02T00:00:00Z" },
            relationships: { originJob: { data: { type: "job", id: "900" } } },
          },
        ],
      },
    });

    const result = await listCascadesForWorkspace("ws-1");

    expect(mockGet).toHaveBeenCalledWith("runCascade", {
      params: { "filter[runCascade]": "originJob.workspace.id==ws-1", sort: "-createdDate" },
    });
    expect(result).toEqual([
      { id: "cascade-1", status: "DEGRADED", originJobId: "900", createdDate: "2026-01-02T00:00:00Z" },
    ]);
  });

  it("returns an empty list when the workspace has no cascades", async () => {
    mockGet.mockResolvedValue({ data: { data: [] } });

    const result = await listCascadesForWorkspace("ws-1");

    expect(result).toEqual([]);
  });
});

describe("getCascadeNodes", () => {
  it("requests the workspace relationship included and returns both nodes and the include block", async () => {
    mockGet.mockResolvedValue({
      data: {
        data: [{ id: "node-1", attributes: { depth: 0, status: "SUCCEEDED" }, relationships: {} }],
        included: [{ type: "workspace", id: "ws-1", attributes: { name: "network" } }],
      },
    });

    const result = await getCascadeNodes("cascade-1");

    expect(mockGet).toHaveBeenCalledWith("runCascade/cascade-1/runCascadeNode", { params: { include: "workspace" } });
    expect(result.nodes).toHaveLength(1);
    expect(result.included).toEqual([{ type: "workspace", id: "ws-1", attributes: { name: "network" } }]);
  });
});

describe("getCascadeEdges", () => {
  it("fetches the cascade's nested edge collection", async () => {
    mockGet.mockResolvedValue({ data: { data: [{ id: "edge-1" }] } });

    const result = await getCascadeEdges("cascade-1");

    expect(mockGet).toHaveBeenCalledWith("runCascade/cascade-1/runCascadeEdge");
    expect(result).toEqual([{ id: "edge-1" }]);
  });
});

describe("admin actions", () => {
  it("cancelCascade posts to the cascade's cancel action", async () => {
    mockAdminPost.mockResolvedValue({ data: {} });

    await cancelCascade("cascade-1");

    expect(mockAdminPost).toHaveBeenCalledWith("/admin/v1/cascades/cascade-1/cancel", {});
  });

  it("retryNode posts to the node's retry action and returns the result", async () => {
    mockAdminPost.mockResolvedValue({ data: { status: "RUNNING", jobId: 42 } });

    const result = await retryNode("node-1");

    expect(mockAdminPost).toHaveBeenCalledWith("/admin/v1/cascades/nodes/node-1/retry", {});
    expect(result).toEqual({ status: "RUNNING", jobId: 42 });
  });

  it("resumeNode posts to the node's resume action and returns the result", async () => {
    mockAdminPost.mockResolvedValue({ data: { status: "PENDING" } });

    const result = await resumeNode("node-1");

    expect(mockAdminPost).toHaveBeenCalledWith("/admin/v1/cascades/nodes/node-1/resume", {});
    expect(result).toEqual({ status: "PENDING" });
  });
});

describe("getAdminErrorMessage", () => {
  it("surfaces the admin controller's plain-text 409 body", () => {
    const error = {
      isAxiosError: true,
      response: { status: 409, data: "Only a failed or skipped node can be retried, not RUNNING" },
    };

    expect(getAdminErrorMessage(error)).toBe("Only a failed or skipped node can be retried, not RUNNING");
  });

  it("surfaces the admin controller's plain-text 404 body", () => {
    const error = { isAxiosError: true, response: { status: 404, data: "No such cascade node: node-1" } };

    expect(getAdminErrorMessage(error)).toBe("No such cascade node: node-1");
  });

  it("falls back to the shared error helper when there is no plain-text body", () => {
    const error = { isAxiosError: true, response: { status: 500, data: {} } };

    expect(getAdminErrorMessage(error)).toBe("fallback error");
  });

  it("falls back to the shared error helper for a non-axios error", () => {
    expect(getAdminErrorMessage(new Error("boom"))).toBe("fallback error");
  });
});
