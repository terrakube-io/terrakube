import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import axiosInstance from "@/config/axiosConfig";
import { apiPost } from "@/modules/api/apiWrapper";
import workspaceService from "@/modules/workspaces/workspaceService";
import { WorkspaceListItem } from "@/modules/workspaces/types";
import projectService from "../projectService";
import BulkWorkspaceBar from "../BulkWorkspaceBar";
import {
  addTagToWorkspaces,
  approveRuns,
  findWaitingRuns,
  moveWorkspaces,
  planWorkspaces,
  removeWorkspacesFromProject,
  setWorkspacesLocked,
} from "../bulkWorkspaceActions";

jest.mock("@/config/axiosConfig", () => ({ __esModule: true, default: { post: jest.fn(), patch: jest.fn() } }));
jest.mock("@/modules/workspaces/workspaceService", () => ({
  __esModule: true,
  default: { assignWorkspaceToProject: jest.fn(), removeWorkspaceFromProject: jest.fn() },
}));
jest.mock("@/modules/api/apiWrapper", () => ({ apiPost: jest.fn() }));
jest.mock("../projectService", () => ({
  __esModule: true,
  default: { listProjectSummaries: jest.fn(), listOrganizationTemplates: jest.fn() },
}));
jest.mock("../MoveImpact", () => ({
  __esModule: true,
  default: ({ targetName }: { targetName: string }) => <div>impact for {targetName}</div>,
}));

const assign = workspaceService.assignWorkspaceToProject as jest.Mock;
const remove = workspaceService.removeWorkspaceFromProject as jest.Mock;
const post = axiosInstance.post as jest.Mock;
const patch = axiosInstance.patch as jest.Mock;
const graphql = apiPost as jest.Mock;

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

describe("bulk plan and approve", () => {
  beforeEach(() => jest.clearAllMocks());

  it("starts one run per workspace from the template, and a failure on one does not stop the others", async () => {
    post.mockImplementation((_url: string, body: any) =>
      body.data.relationships.workspace.data.id === "w2"
        ? Promise.reject({ response: { status: 403 } })
        : Promise.resolve({})
    );

    const outcome = await planWorkspaces("org", ["w1", "w2", "w3"], "tpl-plan");

    expect(outcome.succeeded).toEqual(["w1", "w3"]);
    expect(outcome.failed[0].id).toBe("w2");
    expect(post).toHaveBeenCalledWith(
      "organization/org/job",
      {
        data: {
          type: "job",
          attributes: { templateReference: "tpl-plan", via: "UI" },
          relationships: { workspace: { data: { type: "workspace", id: "w1" } } },
        },
      },
      expect.anything()
    );
  });

  it("finds the runs that wait for approval on the selected workspaces, without paging arguments", async () => {
    graphql.mockResolvedValue({
      isError: false,
      data: {
        organization: {
          edges: [{ node: { job: { edges: [{ node: { id: 7, workspace: { edges: [{ node: { id: "w1" } }] } } }] } } }],
        },
      },
    });

    const runs = await findWaitingRuns("org", ["w1", "w2"]);

    expect(runs).toEqual([{ jobId: "7", workspaceId: "w1", hardViolations: 0, softViolations: 0 }]);
    expect(graphql.mock.calls[0][1].query).toContain("policyEvaluation");
    const request = graphql.mock.calls[0][1];
    expect(request.variables.filter).toBe("status=='waitingApproval';workspace.id=in=('w1','w2')");
    expect(request.query).not.toContain("first:");
  });

  it("does not ask the server when nothing is selected, and reports a failed lookup", async () => {
    expect(await findWaitingRuns("org", [])).toEqual([]);
    expect(graphql).not.toHaveBeenCalled();

    graphql.mockResolvedValue({ isError: true, error: { message: "down" } });
    await expect(findWaitingRuns("org", ["w1"])).rejects.toThrow("down");
  });

  it("approves each run by setting its status", async () => {
    patch.mockResolvedValue({});

    const outcome = await approveRuns("org", ["7", "8"]);

    expect(outcome.succeeded).toEqual(["7", "8"]);
    expect(patch).toHaveBeenCalledWith(
      "organization/org/job/7",
      { data: { type: "job", id: "7", attributes: { status: "approved" } } },
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

  it("shows no actions, only a placeholder that keeps the row height, until a workspace is selected", () => {
    const { container } = renderBar([]);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(container.querySelector(".project-bulk-bar-idle")).toBeInTheDocument();
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

  it("starts a run on every selected workspace once a template is chosen", async () => {
    (projectService.listOrganizationTemplates as jest.Mock).mockResolvedValue([
      { id: "tpl-plan", name: "Plan only", planOnly: true },
      { id: "tpl-apply", name: "Plan and apply", planOnly: false },
    ]);
    post.mockResolvedValue({});
    const onDone = jest.fn();
    renderBar([item("w1"), item("w2")], onDone);

    fireEvent.click(screen.getByRole("button", { name: "Plan" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("button", { name: "Start 2 runs" })).toBeDisabled();
    expect(within(dialog).getByText(/One plan starts on each workspace/)).toBeInTheDocument();
    fireEvent.mouseDown(within(dialog).getByRole("combobox", { name: "Template" }));
    expect(await screen.findByTitle("Plan only")).toBeInTheDocument();
    // A template that applies is not offered for a bulk plan.
    expect(screen.queryByTitle("Plan and apply")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTitle("Plan only"));
    fireEvent.click(within(dialog).getByRole("button", { name: "Start 2 runs" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(post).toHaveBeenCalledTimes(2);
  });

  it("only offers Approve when a selected workspace is waiting for approval", () => {
    const { unmount } = renderBar([item("w1", { lastStatus: "completed" as never })]);
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    unmount();

    renderBar([item("w1", { lastStatus: "waitingApproval" as never })]);
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();
  });

  it("lists the runs that wait and approves them", async () => {
    graphql.mockResolvedValue({
      isError: false,
      data: {
        organization: {
          edges: [{ node: { job: { edges: [{ node: { id: 7, workspace: { edges: [{ node: { id: "w1" } }] } } }] } } }],
        },
      },
    });
    patch.mockResolvedValue({});
    const onDone = jest.fn();
    renderBar([item("w1", { lastStatus: "waitingApproval" as never })], onDone);

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    expect(
      await screen.findByText(/1 run is waiting for approval on 1 of the selected workspaces/)
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Approve 1 run" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(patch).toHaveBeenCalledWith("organization/org/job/7", expect.anything(), expect.anything());
  });

  it("holds back runs whose policy check found violations and approves only the others", async () => {
    const run = (id: number, workspace: string, hard: number, soft: number) => ({
      node: {
        id,
        workspace: { edges: [{ node: { id: workspace } }] },
        policyEvaluation: {
          edges: [{ node: { hardMandatoryViolations: hard, softMandatoryViolations: soft } }],
        },
      },
    });
    graphql.mockResolvedValue({
      isError: false,
      data: {
        organization: {
          edges: [{ node: { job: { edges: [run(7, "w1", 0, 0), run(8, "w2", 0, 2), run(9, "w3", 1, 0)] } } }],
        },
      },
    });
    patch.mockResolvedValue({});
    const onDone = jest.fn();
    renderBar(
      [
        item("w1", { lastStatus: "waitingApproval" as never }),
        item("w2", { lastStatus: "waitingApproval" as never }),
        item("w3", { lastStatus: "waitingApproval" as never }),
      ],
      onDone
    );

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    expect(await screen.findByText(/2 runs are held back: the policy check found violations/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Approve 1 run" }));

    await waitFor(() => expect(onDone).toHaveBeenCalled());
    expect(patch).toHaveBeenCalledTimes(1);
    expect(patch).toHaveBeenCalledWith("organization/org/job/7", expect.anything(), expect.anything());
  });

  it("says when nothing is waiting, and offers nothing to approve", async () => {
    graphql.mockResolvedValue({
      isError: false,
      data: { organization: { edges: [{ node: { job: { edges: [] } } }] } },
    });
    renderBar([item("w1", { lastStatus: "waitingApproval" as never })]);

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(
      await screen.findByText("None of the selected workspaces has a run waiting for approval.")
    ).toBeInTheDocument();
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Approve" })).toBeDisabled();
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
