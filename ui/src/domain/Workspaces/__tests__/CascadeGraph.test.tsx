import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { CascadeGraph } from "../CascadeGraph";

const getCascadeNodesMock = jest.fn();
const getCascadeEdgesMock = jest.fn();
const retryNodeMock = jest.fn();
const resumeNodeMock = jest.fn();

jest.mock("@/modules/cascades/cascadeService", () => ({
  getCascadeNodes: (...args: unknown[]) => getCascadeNodesMock(...args),
  getCascadeEdges: (...args: unknown[]) => getCascadeEdgesMock(...args),
  retryNode: (...args: unknown[]) => retryNodeMock(...args),
  resumeNode: (...args: unknown[]) => resumeNodeMock(...args),
  getAdminErrorMessage: () => "error",
}));

const nodeResponse = (overrides: Partial<{ id: string; depth: number; status: string; workspaceId: string }> = {}) => {
  const node = { id: "node-1", depth: 0, status: "SUCCEEDED", workspaceId: "ws-1", ...overrides };
  return {
    id: node.id,
    attributes: { depth: node.depth, status: node.status },
    relationships: { workspace: { data: { type: "workspace", id: node.workspaceId } } },
  };
};

const renderGraph = (cascadeId = "cascade-1") =>
  render(
    <MemoryRouter>
      <CascadeGraph cascadeId={cascadeId} organizationId="org-1" />
    </MemoryRouter>
  );

// reactflow never actually measures its nodes in jsdom (no ResizeObserver callback - see
// jest.setup.ts), so every node stays visibility:hidden forever and getByRole excludes it from
// the accessibility tree. getByText has no such filtering, so locate the button by its own text
// and climb to the real <button> element.
const findButtonByText = async (text: string | RegExp) => (await screen.findByText(text)).closest("button")!;

describe("CascadeGraph", () => {
  beforeEach(() => {
    getCascadeNodesMock.mockReset();
    getCascadeEdgesMock.mockReset();
    retryNodeMock.mockReset();
    resumeNodeMock.mockReset();
    getCascadeEdgesMock.mockResolvedValue([]);
  });

  it("resolves each node's workspace name from the include block", async () => {
    getCascadeNodesMock.mockResolvedValue({
      nodes: [nodeResponse()],
      included: [{ type: "workspace", id: "ws-1", attributes: { name: "network" } }],
    });
    renderGraph();

    expect(await screen.findByText("network")).toBeInTheDocument();
    expect(screen.getByText("Succeeded")).toBeInTheDocument();
  });

  it("offers Retry only for a failed or skipped node", async () => {
    getCascadeNodesMock.mockResolvedValue({
      nodes: [nodeResponse({ id: "node-1", status: "FAILED" })],
      included: [{ type: "workspace", id: "ws-1", attributes: { name: "network" } }],
    });
    renderGraph();

    expect(await findButtonByText("Retry")).toBeInTheDocument();
    expect(screen.queryByText("Resume")).toBeNull();
  });

  it("offers Resume only for a blocked node", async () => {
    getCascadeNodesMock.mockResolvedValue({
      nodes: [nodeResponse({ id: "node-1", status: "BLOCKED" })],
      included: [{ type: "workspace", id: "ws-1", attributes: { name: "network" } }],
    });
    renderGraph();

    expect(await findButtonByText("Resume")).toBeInTheDocument();
    expect(screen.queryByText("Retry")).toBeNull();
  });

  it("retries a node and reloads once dispatched", async () => {
    getCascadeNodesMock.mockResolvedValueOnce({
      nodes: [nodeResponse({ id: "node-1", status: "FAILED" })],
      included: [{ type: "workspace", id: "ws-1", attributes: { name: "network" } }],
    });
    retryNodeMock.mockResolvedValue({ status: "RUNNING", jobId: 42 });
    getCascadeNodesMock.mockResolvedValueOnce({
      nodes: [nodeResponse({ id: "node-1", status: "RUNNING" })],
      included: [{ type: "workspace", id: "ws-1", attributes: { name: "network" } }],
    });
    renderGraph();

    // fireEvent, not userEvent: userEvent's realistic pointerdown/up sequence trips reactflow's
    // own node-drag-detection handler, which throws reading properties off a synthetic event with
    // no real `view` in jsdom - unrelated to anything this test is actually covering.
    fireEvent.click(await findButtonByText("Retry"));

    await waitFor(() => expect(retryNodeMock).toHaveBeenCalledWith("node-1"));
    expect(await screen.findByText("Running")).toBeInTheDocument();
  });

  it("connects an edge between the right two cascade nodes, not raw workspace ids", async () => {
    getCascadeNodesMock.mockResolvedValue({
      nodes: [
        nodeResponse({ id: "node-parent", workspaceId: "ws-parent", depth: 0 }),
        nodeResponse({ id: "node-child", workspaceId: "ws-child", depth: 1 }),
      ],
      included: [
        { type: "workspace", id: "ws-parent", attributes: { name: "parent" } },
        { type: "workspace", id: "ws-child", attributes: { name: "child" } },
      ],
    });
    getCascadeEdgesMock.mockResolvedValue([
      {
        id: "edge-1",
        relationships: {
          sourceWorkspace: { data: { type: "workspace", id: "ws-parent" } },
          destinationWorkspace: { data: { type: "workspace", id: "ws-child" } },
        },
      },
    ]);
    renderGraph();

    // Both ends render - if workspaceId->nodeId resolution had failed, the edge-building
    // .filter(edge => edge !== null) would have silently dropped it rather than crashing, so
    // this is the real coverage: no silent drop, and no render-time error from a dangling edge
    // pointing at a node id that was never created.
    expect(await screen.findByText("parent")).toBeInTheDocument();
    expect(screen.getByText("child")).toBeInTheDocument();
    await waitFor(() => expect(getCascadeEdgesMock).toHaveBeenCalledWith("cascade-1"));
  });
});
