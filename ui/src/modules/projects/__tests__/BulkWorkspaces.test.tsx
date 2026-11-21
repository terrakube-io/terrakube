import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import axiosInstance from "@/config/axiosConfig";
import workspaceService from "@/modules/workspaces/workspaceService";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import projectService from "../projectService";
import BulkWorkspaceBar from "../BulkWorkspaceBar";
import {
  addTagToWorkspaces,
  moveWorkspaces,
  removeWorkspacesFromProject,
  setWorkspacesLocked,
} from "../bulkWorkspaceActions";

jest.mock("@/config/axiosConfig", () => ({ __esModule: true, default: { post: jest.fn(), patch: jest.fn() } }));
jest.mock("@/modules/workspaces/workspaceService", () => ({
  __esModule: true,
  default: { assignWorkspaceToProject: jest.fn(), removeWorkspaceFromProject: jest.fn() },
}));
jest.mock("../projectService", () => ({ __esModule: true, default: { listProjectSummaries: jest.fn() } }));
jest.mock("../MoveImpact", () => ({
  __esModule: true,
  default: ({ targetName }: { targetName: string }) => <div>impact for {targetName}</div>,
}));

const assign = workspaceService.assignWorkspaceToProject as jest.Mock;
const remove = workspaceService.removeWorkspaceFromProject as jest.Mock;
const post = axiosInstance.post as jest.Mock;
const patch = axiosInstance.patch as jest.Mock;

const item = (id: string, overrides: Partial<WorkspaceListItem> = {}): WorkspaceListItem => ({
  id,
  name: `ws-${id}`,
  iacType: "terraform",
  source: "empty",
  ...overrides,
});

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

  it("tags only the workspaces that do not have the tag yet", async () => {
    post.mockResolvedValue({});

    const outcome = await addTagToWorkspaces(
      "org",
      [item("w1", { tags: [{ tagId: "t1" }] }), item("w2"), item("w3", { tags: [{ tagId: "other" }] })],
      "t1",
      " prod "
    );

    expect(outcome.succeeded).toEqual(["w2", "w3"]);
    expect(post).toHaveBeenCalledTimes(2);
    expect(post).toHaveBeenCalledWith(
      "organization/org/workspace/w2/workspaceTag",
      { data: { type: "workspacetag", attributes: { tagId: "t1", value: "prod" } } },
      expect.anything()
    );
  });

  it("locks and unlocks through the workspace attribute", async () => {
    patch.mockResolvedValue({});

    await setWorkspacesLocked("org", ["w1"], true);

    expect(patch).toHaveBeenCalledWith(
      "organization/org/workspace/w1",
      { data: { type: "workspace", id: "w1", attributes: { locked: true } } },
      expect.anything()
    );
  });
});

describe("BulkWorkspaceBar", () => {
  const renderBar = (selected: WorkspaceListItem[], onDone = jest.fn()) =>
    render(
      <BulkWorkspaceBar
        orgid="org"
        projectId="p1"
        selected={selected}
        total={18}
        tags={[{ id: "t1", name: "env" }]}
        onClear={jest.fn()}
        onDone={onDone}
      />
    );

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
    const { container } = renderBar([]);

    expect(container).toBeEmptyDOMElement();
  });

  it("says how many are selected and that the selection covers this page only", () => {
    renderBar([item("w1"), item("w2")]);

    expect(screen.getByText("2 of 18 selected")).toBeInTheDocument();
    expect(screen.getByText("(this page only)")).toBeInTheDocument();
  });

  it("offers Unlock when every selected workspace is locked, and Lock otherwise", () => {
    const { unmount } = renderBar([item("w1", { locked: true })]);
    expect(screen.getByRole("button", { name: "Unlock" })).toBeInTheDocument();
    unmount();

    renderBar([item("w1", { locked: true }), item("w2")]);
    expect(screen.getByRole("button", { name: "Lock" })).toBeInTheDocument();
  });

  it("moves the selection to another project, shows the impact, and never offers the current project", async () => {
    assign.mockResolvedValue(undefined);
    const onDone = jest.fn();
    renderBar([item("w1"), item("w2")], onDone);

    fireEvent.click(screen.getByRole("button", { name: "Move to project" }));
    fireEvent.mouseDown(await screen.findByRole("combobox", { name: "Destination project" }));
    expect(await screen.findByText("Other project")).toBeInTheDocument();
    expect(screen.queryByText("This project")).not.toBeInTheDocument();
    fireEvent.click(screen.getByText("Other project"));
    expect(await screen.findByText("impact for Other project")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Move 2 workspaces" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(assign).toHaveBeenCalledTimes(2);
    expect(assign).toHaveBeenCalledWith("org", "w1", "p2");
  });

  it("adds a tag to the selection", async () => {
    post.mockResolvedValue({});
    const onDone = jest.fn();
    renderBar([item("w1")], onDone);

    fireEvent.click(screen.getByRole("button", { name: "Add tag" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.mouseDown(within(dialog).getByRole("combobox", { name: "Tag to add" }));
    fireEvent.click(await screen.findByTitle("env"));
    fireEvent.click(within(dialog).getByRole("button", { name: "Add tag" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(post).toHaveBeenCalledWith(
      "organization/org/workspace/w1/workspaceTag",
      { data: { type: "workspacetag", attributes: { tagId: "t1", value: null } } },
      expect.anything()
    );
  });
});
