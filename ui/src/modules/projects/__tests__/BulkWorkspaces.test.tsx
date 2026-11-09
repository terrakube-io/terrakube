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
