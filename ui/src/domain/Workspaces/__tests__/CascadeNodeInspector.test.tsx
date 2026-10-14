import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { RunCascadeNodeStatus } from "@/domain/types";
import { CascadeDependency, CascadeNodeInspector, describeNode } from "../CascadeNodeInspector";

const dependency = (overrides: Partial<CascadeDependency> = {}): CascadeDependency => ({
  id: "edge-1",
  sourceNodeId: "node-up",
  targetNodeId: "node-1",
  sourceName: "fanin-dns",
  targetName: "fanin-app",
  mode: "EACH",
  onDestroy: "TRIGGER",
  status: "SATISFIED",
  ...overrides,
});

describe("describeNode", () => {
  it("explains a destroy-policy pause and does not offer a recheck, which cannot lift it", () => {
    const guidance = describeNode(RunCascadeNodeStatus.Blocked, [
      dependency({ status: "BLOCKED", onDestroy: "BLOCK" }),
    ]);

    expect(guidance?.title).toBe("Paused by a destroy policy");
    expect(guidance?.text).toContain("fanin-dns");
    expect(guidance?.text).toMatch(/start a new run/i);
    expect(guidance?.canRecheck).toBe(false);
  });

  it("offers a recheck when a paused upstream simply failed", () => {
    const guidance = describeNode(RunCascadeNodeStatus.Blocked, [dependency({ status: "BLOCKED" })]);

    expect(guidance?.title).toBe("Waiting for an upstream workspace to recover");
    expect(guidance?.canRecheck).toBe(true);
  });

  it("lets a recheck through when one of several pauses is not a policy", () => {
    const guidance = describeNode(RunCascadeNodeStatus.Blocked, [
      dependency({ status: "BLOCKED", onDestroy: "BLOCK" }),
      dependency({ id: "edge-2", sourceName: "other", status: "BLOCKED" }),
    ]);

    expect(guidance?.canRecheck).toBe(true);
  });

  it("offers a retry for a failed run and says nothing for a succeeded one", () => {
    expect(describeNode(RunCascadeNodeStatus.Failed, [])?.canRetry).toBe(true);
    expect(describeNode(RunCascadeNodeStatus.Succeeded, [])).toBeUndefined();
  });

  it("falls back to the server's reason for a skipped workspace", () => {
    expect(describeNode(RunCascadeNodeStatus.Skipped, [], "No change upstream")?.text).toBe("No change upstream");
  });

  it("abbreviates a long list of paused upstreams", () => {
    const upstream = ["a", "b", "c", "d", "e"].map((name, i) =>
      dependency({ id: `edge-${i}`, sourceName: name, status: "BLOCKED" })
    );

    expect(describeNode(RunCascadeNodeStatus.Blocked, upstream)?.text).toContain("a, b, c and 2 more");
  });
});

describe("CascadeNodeInspector", () => {
  const data = (overrides = {}) => ({
    organizationId: "org-1",
    workspaceId: "ws-1",
    workspaceName: "fanin-app",
    status: RunCascadeNodeStatus.Blocked,
    reason: "server reason",
    attempts: [],
    ...overrides,
  });

  const renderInspector = (props: Partial<React.ComponentProps<typeof CascadeNodeInspector>> = {}) => {
    const handlers = { onRetry: jest.fn(), onResume: jest.fn(), onSelect: jest.fn(), onFocus: jest.fn() };
    render(
      <MemoryRouter>
        <CascadeNodeInspector
          nodeId="node-1"
          data={data()}
          upstream={[dependency({ status: "BLOCKED" })]}
          downstream={[]}
          organizationId="org-1"
          canOperate
          busy={false}
          {...handlers}
          {...props}
        />
      </MemoryRouter>
    );
    return handlers;
  };

  it("offers the recheck for a recoverable pause and wires it to the node", () => {
    const { onResume } = renderInspector();

    fireEvent.click(screen.getByRole("button", { name: "Recheck dependencies" }));

    expect(onResume).toHaveBeenCalledWith("node-1");
  });

  it("explains instead of offering a recheck when a destroy policy paused the run", () => {
    renderInspector({ upstream: [dependency({ status: "BLOCKED", onDestroy: "BLOCK" })] });

    expect(screen.getByText("Paused by a destroy policy")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Recheck dependencies" })).toBeNull();
  });

  it("hides the actions from someone who cannot operate", () => {
    renderInspector({ canOperate: false });

    expect(screen.queryByRole("button", { name: "Recheck dependencies" })).toBeNull();
    expect(screen.getByRole("link", { name: /Open workspace/ })).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces/ws-1"
    );
  });

  it("offers a retry for a failed run", () => {
    const { onRetry } = renderInspector({ data: data({ status: RunCascadeNodeStatus.Failed }), upstream: [] });

    fireEvent.click(screen.getByRole("button", { name: "Retry failed run" }));

    expect(onRetry).toHaveBeenCalledWith("node-1");
  });

  it("focuses the graph on this workspace and jumps to a listed neighbour", () => {
    const { onFocus, onSelect } = renderInspector({
      upstream: [dependency()],
      downstream: [
        dependency({ id: "edge-9", sourceNodeId: "node-1", targetNodeId: "node-down", targetName: "downstream" }),
      ],
    });

    fireEvent.click(screen.getByRole("button", { name: /Focus on this workspace/ }));
    fireEvent.click(screen.getByRole("button", { name: "fanin-dns" }));
    fireEvent.click(screen.getByRole("button", { name: "downstream" }));

    expect(onFocus).toHaveBeenCalledWith("node-1");
    expect(onSelect).toHaveBeenCalledWith("node-up");
    expect(onSelect).toHaveBeenCalledWith("node-down");
  });

  it("lists each attempt with a link to its run", () => {
    renderInspector({
      data: data({ attempts: [{ number: 1, outcome: "FAILED", jobId: "42" }] }),
    });

    expect(screen.getByText(/Attempt/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Run #42" })).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces/ws-1/runs/42"
    );
  });
});
