import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import workspaceService from "@/modules/workspaces/workspaceService";
import projectService, { DeletePreflight } from "../projectService";
import ProjectDeleteDialog from "../ProjectDeleteDialog";
import { deleteProjectWithContents } from "../projectDelete";
import { removeWorkspacesFromProject } from "../bulkWorkspaceActions";

jest.mock("@/modules/workspaces/workspaceService", () => ({
  __esModule: true,
  default: { listWorkspacePage: jest.fn() },
}));
jest.mock("../bulkWorkspaceActions", () => ({ removeWorkspacesFromProject: jest.fn() }));
jest.mock("../projectService", () => ({
  __esModule: true,
  default: {
    getDeletePreflight: jest.fn(),
    deletePolicyAttachment: jest.fn(),
    deletePolicyExemption: jest.fn(),
    detachCollectionFromProject: jest.fn(),
    deleteProject: jest.fn(),
  },
}));

const list = workspaceService.listWorkspacePage as jest.Mock;
const remove = removeWorkspacesFromProject as jest.Mock;
const service = projectService as jest.Mocked<typeof projectService>;

const preflight: DeletePreflight = {
  teamCount: 3,
  attachments: [
    { id: "a1", policySetId: "ps1" },
    { id: "a2", policySetId: "ps2" },
  ],
  exemptions: [{ id: "e1", policySetId: "ps1" }],
  collections: [{ id: "c1", name: "payments-common", referenceId: "r1" }],
  defaultTagCount: 0,
  failed: [],
};

const pageOf = (ids: string[]) => ({ isError: false, data: { workspaces: ids.map((id) => ({ id })) } });

describe("deleteProjectWithContents", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    remove.mockResolvedValue({ succeeded: [], failed: [] });
    service.deleteProject.mockResolvedValue(undefined);
  });

  it("moves workspaces out page by page, removes what points at the project, then deletes it", async () => {
    list
      .mockResolvedValueOnce(pageOf(["w1", "w2"]))
      .mockResolvedValueOnce(pageOf(["w3"]))
      .mockResolvedValueOnce(pageOf([]));
    const steps: string[] = [];

    await deleteProjectWithContents("org", "p1", preflight, (step) => steps.push(step));

    expect(remove).toHaveBeenNthCalledWith(1, "org", ["w1", "w2"]);
    expect(remove).toHaveBeenNthCalledWith(2, "org", ["w3"]);
    expect(service.deletePolicyExemption).toHaveBeenCalledWith("e1");
    expect(service.deletePolicyAttachment).toHaveBeenCalledWith("ps1", "a1");
    expect(service.deletePolicyAttachment).toHaveBeenCalledWith("ps2", "a2");
    expect(service.detachCollectionFromProject).toHaveBeenCalledWith("org", "c1", "r1");
    expect(service.deleteProject).toHaveBeenCalledWith("org", "p1");
    expect(steps).toEqual([
      "Moving workspaces to Unassigned",
      "Removing policy attachments and exemptions",
      "Detaching variable collections",
      "Deleting the project",
    ]);
  });

  it("stops without deleting the project when a workspace could not be moved", async () => {
    list.mockResolvedValue(pageOf(["w1"]));
    remove.mockResolvedValue({ succeeded: [], failed: [{ id: "w1", reason: "no permission" }] });

    await expect(deleteProjectWithContents("org", "p1", preflight)).rejects.toThrow(
      "1 workspaces could not be moved: no permission"
    );

    expect(service.deleteProject).not.toHaveBeenCalled();
    expect(service.deletePolicyAttachment).not.toHaveBeenCalled();
  });

  it("says the workspaces were already moved when a later step fails", async () => {
    list.mockResolvedValueOnce(pageOf(["w1", "w2"])).mockResolvedValueOnce(pageOf([]));
    remove.mockResolvedValue({ succeeded: ["w1", "w2"], failed: [] });
    service.deletePolicyExemption.mockRejectedValueOnce({ response: { status: 403 } });

    await expect(deleteProjectWithContents("org", "p1", preflight)).rejects.toThrow(
      "You do not have permission for this step. 2 workspaces were already moved to Unassigned and the project still exists."
    );

    expect(service.deleteProject).not.toHaveBeenCalled();
  });

  it("does not delete the project when the workspaces cannot be listed", async () => {
    list.mockResolvedValue({ isError: true, error: { message: "down" } });

    await expect(deleteProjectWithContents("org", "p1", preflight)).rejects.toThrow("down");

    expect(service.deleteProject).not.toHaveBeenCalled();
  });
});

describe("ProjectDeleteDialog", () => {
  const renderDialog = (workspaceCount = 18, onDeleted = jest.fn(), rights = {}) =>
    render(
      <ProjectDeleteDialog
        orgid="org"
        projectId="p1"
        projectName="payments-prod"
        workspaceCount={workspaceCount}
        {...rights}
        open
        onClose={jest.fn()}
        onDeleted={onDeleted}
      />
    );

  beforeEach(() => {
    jest.clearAllMocks();
    service.getDeletePreflight.mockResolvedValue(preflight);
    service.deleteProject.mockResolvedValue(undefined);
    remove.mockResolvedValue({ succeeded: [], failed: [] });
    list.mockResolvedValue(pageOf([]));
  });

  it("says what the delete contains and removes", async () => {
    renderDialog();

    expect(await screen.findByText("18 workspaces, 3 team access rows")).toBeInTheDocument();
    expect(screen.getByText("2 attachments, 1 exemption would be removed")).toBeInTheDocument();
    expect(screen.getByText("1 project-scoped collection would be detached")).toBeInTheDocument();
    expect(screen.getByText(/so it cannot be deleted yet/)).toBeInTheDocument();
    expect(screen.getByText("The workspaces are moved to Unassigned first, not deleted.")).toBeInTheDocument();
  });

  it("does not claim team counts it cannot read", async () => {
    service.getDeletePreflight.mockResolvedValue({ ...preflight, teamCount: null });

    renderDialog();

    expect(await screen.findByText("18 workspaces")).toBeInTheDocument();
  });

  it("needs the project name typed before the delete button works", async () => {
    renderDialog();
    await screen.findByText("18 workspaces, 3 team access rows");
    const button = screen.getByRole("button", { name: "Move to Unassigned, then delete" });

    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Project name"), { target: { value: "payments" } });
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Project name"), { target: { value: "payments-prod" } });
    expect(button).toBeEnabled();
  });

  it("deletes and reports it once the name is typed", async () => {
    const onDeleted = jest.fn();
    renderDialog(0, onDeleted);
    await screen.findByText("0 workspaces, 3 team access rows");

    fireEvent.change(screen.getByLabelText("Project name"), { target: { value: "payments-prod" } });
    fireEvent.click(screen.getByRole("button", { name: "Delete project" }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
    expect(service.deleteProject).toHaveBeenCalledWith("org", "p1");
  });

  it("shows why it stopped and does not report a delete", async () => {
    const onDeleted = jest.fn();
    service.deleteProject.mockRejectedValue({
      response: { status: 409, data: { errors: [{ detail: "Project still has 1 workspace." }] } },
    });
    renderDialog(0, onDeleted);
    await screen.findByText("0 workspaces, 3 team access rows");

    fireEvent.change(screen.getByLabelText("Project name"), { target: { value: "payments-prod" } });
    fireEvent.click(screen.getByRole("button", { name: "Delete project" }));

    expect(await screen.findByText("Project still has 1 workspace.")).toBeInTheDocument();
    expect(onDeleted).not.toHaveBeenCalled();
  });

  it("blocks the delete when a read failed, instead of saying nothing is attached", async () => {
    service.getDeletePreflight.mockResolvedValue({
      ...preflight,
      attachments: [],
      exemptions: [],
      collections: [],
      failed: ["policy sets"],
    });
    renderDialog();

    expect(await screen.findByText(/policy sets could not be read/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Project name"), { target: { value: "payments-prod" } });
    expect(screen.getByRole("button", { name: "Move to Unassigned, then delete" })).toBeDisabled();
  });

  it("blocks moving the workspaces when the person cannot manage policies or collections", async () => {
    renderDialog(18, jest.fn(), { canManagePolicies: false });
    expect(await screen.findByText(/needs Manage Policies/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Project name"), { target: { value: "payments-prod" } });
    expect(screen.getByRole("button", { name: "Move to Unassigned, then delete" })).toBeDisabled();
  });

  it("still lets an empty project be deleted without those rights, because nothing is moved first", async () => {
    renderDialog(0, jest.fn(), { canManagePolicies: false, canManageCollection: false });
    await screen.findByText("0 workspaces, 3 team access rows");
    fireEvent.change(screen.getByLabelText("Project name"), { target: { value: "payments-prod" } });

    expect(screen.getByRole("button", { name: "Delete project" })).toBeEnabled();
  });
});
