import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { EditTeam } from "../EditTeam";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), patch: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
}));
jest.mock("@/modules/api/apiWrapper", () => ({
  apiGet: jest.fn().mockResolvedValue({ isError: false, data: [] }),
  apiPost: jest.fn(),
  apiDelete: jest.fn(),
}));
jest.mock("@/modules/token/TokenGrid", () => () => null);
jest.mock("@/components/modals/CreatePatModal", () => () => null);

const renderEdit = (managePermission: boolean) =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/settings/teams/edit/t-1"]}>
      <Routes>
        <Route
          path="/organizations/:orgid/settings/teams/edit/:id"
          element={
            <EditTeam
              mode="edit"
              setMode={jest.fn()}
              teamId="t-1"
              loadTeams={jest.fn()}
              onDeleteTeam={jest.fn()}
              managePermission={managePermission}
            />
          }
        />
      </Routes>
    </MemoryRouter>
  );

describe("EditTeam", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (axiosInstance.get as jest.Mock).mockResolvedValue({
      data: { data: { id: "t-1", attributes: { name: "PLATFORM", role: "admin", manageWorkspace: true } } },
    });
    (axiosInstance.patch as jest.Mock).mockResolvedValue({});
  });

  it("disables deleting the team without the manage permission", async () => {
    renderEdit(false);

    expect(await screen.findByRole("heading", { name: "PLATFORM" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete this team" })).toBeDisabled();
  });

  it("sends only the role for a preset role, not the individual permissions", async () => {
    renderEdit(true);
    await screen.findByRole("heading", { name: "PLATFORM" });
    expect(screen.getByRole("checkbox", { name: "Manage workspaces" })).toBeChecked();

    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(axiosInstance.patch).toHaveBeenCalled());
    const attributes = (axiosInstance.patch as jest.Mock).mock.calls[0][1].data.attributes;
    expect(attributes.role).toBe("admin");
    // JSON drops undefined keys, so the request carries the role alone, as before.
    expect(attributes.manageWorkspace).toBeUndefined();
  });

  it("shows a role's destroy right without sending it, and sends the saved right for a custom team", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({
      data: { data: { id: "t-1", attributes: { name: "PLATFORM", role: "plan" } } },
    });
    renderEdit(true);
    await screen.findByRole("heading", { name: "PLATFORM" });
    expect(screen.getByRole("checkbox", { name: "Destroy runs" })).not.toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(axiosInstance.patch).toHaveBeenCalledTimes(1));
    expect((axiosInstance.patch as jest.Mock).mock.calls[0][1].data.attributes.destroyJob).toBeUndefined();

    (axiosInstance.get as jest.Mock).mockResolvedValue({
      data: { data: { id: "t-1", attributes: { name: "OPS", role: "custom", planJob: true, destroyJob: false } } },
    });
    renderEdit(true);
    const boxes = await screen.findAllByRole("checkbox", { name: "Destroy runs" });
    const custom = boxes[boxes.length - 1];
    expect(custom).not.toBeChecked();
    fireEvent.click(custom);
    fireEvent.click(screen.getAllByRole("button", { name: "Save changes" }).pop()!);
    await waitFor(() => expect(axiosInstance.patch).toHaveBeenCalledTimes(2));
    expect((axiosInstance.patch as jest.Mock).mock.calls[1][1].data.attributes.destroyJob).toBe(true);
  });
});
