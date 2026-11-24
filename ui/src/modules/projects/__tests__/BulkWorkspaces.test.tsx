import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import workspaceService from "@/modules/workspaces/workspaceService";
import projectService from "../projectService";
import BulkWorkspaceBar from "../BulkWorkspaceBar";
import { moveWorkspaces, removeWorkspacesFromProject } from "../bulkWorkspaceActions";

jest.mock("@/modules/workspaces/workspaceService", () => ({
  __esModule: true,
  default: { assignWorkspaceToProject: jest.fn(), removeWorkspaceFromProject: jest.fn() },
}));
jest.mock("../projectService", () => ({ __esModule: true, default: { listProjectSummaries: jest.fn() } }));

const assign = workspaceService.assignWorkspaceToProject as jest.Mock;
const remove = workspaceService.removeWorkspaceFromProject as jest.Mock;

describe("bulk workspace actions", () => {
  beforeEach(() => jest.clearAllMocks());

  it("reports each workspace separately so one failure does not hide the rest", async () => {
    assign.mockImplementation((_org: string, id: string) =>
      id === "w2" ? Promise.reject({ response: { status: 403 } }) : Promise.resolve()
    );

    const outcome = await moveWorkspaces("org", ["w1", "w2", "w3"], "p2");

    expect(outcome.succeeded).toEqual(["w1", "w3"]);
    expect(outcome.failed).toEqual([{ id: "w2", reason: expect.stringContaining("permission") }]);
    expect(assign).toHaveBeenCalledWith("org", "w1", "p2");
  });

  it("never has more than five changes in flight", async () => {
    let inFlight = 0;
    let peak = 0;
    assign.mockImplementation(async () => {
      inFlight += 1;
      peak = Math.max(peak, inFlight);
      await new Promise((resolve) => setTimeout(resolve, 1));
      inFlight -= 1;
    });

    const outcome = await moveWorkspaces(
      "org",
      Array.from({ length: 20 }, (_, i) => `w${i}`),
      "p2"
    );

    expect(outcome.succeeded).toHaveLength(20);
    expect(peak).toBeLessThanOrEqual(5);
  });

  it("removes workspaces from their project", async () => {
    remove.mockResolvedValue(undefined);

    const outcome = await removeWorkspacesFromProject("org", ["w1"]);

    expect(outcome.succeeded).toEqual(["w1"]);
    expect(remove).toHaveBeenCalledWith("org", "w1");
  });
});

describe("BulkWorkspaceBar", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (projectService.listProjectSummaries as jest.Mock).mockResolvedValue({
      isError: false,
      data: {
        items: [
          { id: "p1", name: "This project" },
          { id: "p2", name: "Other project" },
        ],
        totalRecords: 2,
      },
    });
  });

  it("renders nothing until a workspace is selected", () => {
    const { container } = render(
      <BulkWorkspaceBar orgid="org" projectId="p1" selectedIds={[]} onClear={jest.fn()} onDone={jest.fn()} />
    );

    expect(container).toBeEmptyDOMElement();
  });

  it("offers Remove from project only to someone who may take workspaces out of projects", () => {
    const { rerender } = render(
      <BulkWorkspaceBar orgid="org" projectId="p1" selectedIds={["w1"]} onClear={jest.fn()} onDone={jest.fn()} />
    );
    expect(screen.queryByRole("button", { name: "Remove from project" })).not.toBeInTheDocument();

    rerender(
      <BulkWorkspaceBar
        orgid="org"
        projectId="p1"
        selectedIds={["w1"]}
        canRemove
        onClear={jest.fn()}
        onDone={jest.fn()}
      />
    );
    expect(screen.getByRole("button", { name: "Remove from project" })).toBeInTheDocument();
  });

  it("moves the selection to another project and never offers the current one", async () => {
    assign.mockResolvedValue(undefined);
    const onDone = jest.fn();
    render(
      <BulkWorkspaceBar orgid="org" projectId="p1" selectedIds={["w1", "w2"]} onClear={jest.fn()} onDone={onDone} />
    );

    expect(screen.getByText("2 selected")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Move to project" }));
    fireEvent.mouseDown(await screen.findByRole("combobox", { name: "Destination project" }));
    expect(await screen.findByText("Other project")).toBeInTheDocument();
    expect(screen.queryByText("This project")).not.toBeInTheDocument();
    fireEvent.click(screen.getByText("Other project"));
    fireEvent.click(screen.getByRole("button", { name: "Move" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(assign).toHaveBeenCalledTimes(2);
    expect(assign).toHaveBeenCalledWith("org", "w1", "p2");
  });
});
