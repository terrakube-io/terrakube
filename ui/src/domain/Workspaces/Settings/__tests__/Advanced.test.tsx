import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { WorkspaceAdvanced } from "../Advanced";
import axiosInstance from "../../../../config/axiosConfig";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => mockNavigate,
}));

jest.mock("../../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { patch: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
}));

jest.mock("../../Workspaces", () => ({ genericHeader: {} }));

const workspace = {
  id: "ws-1",
  attributes: { name: "prod-cluster" },
  relationships: { organization: { data: { id: "org-1" } } },
} as any;

describe("WorkspaceAdvanced", () => {
  it("says resources keep running and soft-deletes after typing the name", async () => {
    (axiosInstance.patch as jest.Mock).mockResolvedValue({ status: 204 });
    render(
      <MemoryRouter>
        <WorkspaceAdvanced workspace={workspace} manageWorkspace={true} />
      </MemoryRouter>
    );

    expect(screen.getByText("Destruction and deletion")).toBeInTheDocument();
    expect(screen.getByText(/does not destroy any infrastructure/)).toBeInTheDocument();
    expect(screen.queryByText(/permanently/)).not.toBeInTheDocument();
    expect(screen.getByText(/state files and run outputs are deleted from storage/)).toBeInTheDocument();
    expect(screen.getByText(/This cannot be undone/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Delete this workspace" }));
    fireEvent.change(screen.getByLabelText("Type the name to confirm"), { target: { value: "prod-cluster" } });
    fireEvent.click(screen.getAllByRole("button", { name: "Delete this workspace" }).pop()!);

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith("/organizations/org-1/workspaces"));
    expect(axiosInstance.patch).toHaveBeenCalledWith(
      "/organization/org-1/workspace/ws-1/relationships/vcs",
      { data: null },
      expect.anything()
    );
    const body = (axiosInstance.patch as jest.Mock).mock.calls[1][1];
    expect(body.data.attributes.deleted).toBe("true");
    expect(body.data.attributes.name).toMatch(/^prod-cluster_DEL_/);
  });

  it("disables the action without manage permission", () => {
    render(
      <MemoryRouter>
        <WorkspaceAdvanced workspace={workspace} manageWorkspace={false} />
      </MemoryRouter>
    );
    expect(screen.getByRole("button", { name: "Delete this workspace" })).toBeDisabled();
  });
});
