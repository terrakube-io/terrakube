import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { TeamSettings } from "../Teams";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), delete: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
  isPermissionError: () => false,
}));

jest.mock("../EditTeam", () => ({ EditTeam: () => <div /> }));

const team = (id: string, name: string, attributes: Record<string, unknown> = {}) => ({
  id,
  attributes: { name, ...attributes },
});

const renderTeams = () =>
  render(
    <MemoryRouter initialEntries={["/organizations/org-1/settings/teams"]}>
      <Routes>
        <Route path="/organizations/:orgid/settings/teams" element={<TeamSettings />} />
      </Routes>
    </MemoryRouter>
  );

describe("TeamSettings list", () => {
  beforeEach(() => jest.clearAllMocks());

  it("lists teams with a count, role and permission summary", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({
      data: {
        data: [
          team("t-1", "PLATFORM", { role: "admin" }),
          team("t-2", "DEVS", { role: "custom", manageWorkspace: true, planJob: true }),
        ],
      },
    });
    renderTeams();

    expect(await screen.findByRole("heading", { name: "Teams (2)" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "PLATFORM" })).toHaveAttribute(
      "href",
      "/organizations/org-1/settings/teams/edit/t-1"
    );
    expect(screen.getByText("Admin")).toBeInTheDocument();
    expect(screen.getByText("Can manage workspaces, plan runs.")).toBeInTheDocument();
  });

  it("shows an empty state instead of an empty table", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({ data: { data: [] } });
    renderTeams();

    expect(await screen.findByText(/No teams yet/)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("deletes a team only after its name is typed", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({ data: { data: [team("t-1", "PLATFORM", { role: "read" })] } });
    (axiosInstance.delete as jest.Mock).mockResolvedValue({});
    renderTeams();

    fireEvent.click(await screen.findByRole("button", { name: "Delete team PLATFORM" }));
    const confirm = await screen.findByRole("button", { name: "Delete team" });
    expect(confirm).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Type the name to confirm"), { target: { value: "PLATFORM" } });
    fireEvent.click(confirm);

    await waitFor(() => expect(axiosInstance.delete).toHaveBeenCalledWith("organization/org-1/team/t-1"));
  });
});
