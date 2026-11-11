import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { createQueryClient } from "@/modules/api/queryClient";
import { TeamSettings } from "../Teams";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), delete: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
  isPermissionError: () => false,
}));

// The real form saves and then asks the list to reload; the stub does only the second part.
jest.mock("../EditTeam", () => ({
  EditTeam: ({ loadTeams }: { loadTeams: () => void }) => (
    <button type="button" onClick={loadTeams}>
      Save team
    </button>
  ),
}));

const team = (id: string, name: string, attributes: Record<string, unknown> = {}) => ({
  id,
  attributes: { name, ...attributes },
});

// A fresh client per render, so no test sees another test's cached teams.
const renderTeams = (props: { editorMode?: "new" } = {}) =>
  render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/organizations/org-1/settings/teams"]}>
        <Routes>
          <Route path="/organizations/:orgid/settings/teams" element={<TeamSettings {...props} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
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

  it("reloads the list after a delete, so the deleted team disappears", async () => {
    (axiosInstance.get as jest.Mock)
      .mockResolvedValueOnce({ data: { data: [team("t-1", "PLATFORM"), team("t-2", "DEVS")] } })
      .mockResolvedValue({ data: { data: [team("t-2", "DEVS")] } });
    (axiosInstance.delete as jest.Mock).mockResolvedValue({});
    renderTeams();

    fireEvent.click(await screen.findByRole("button", { name: "Delete team PLATFORM" }));
    fireEvent.change(screen.getByLabelText("Type the name to confirm"), { target: { value: "PLATFORM" } });
    fireEvent.click(await screen.findByRole("button", { name: "Delete team" }));

    expect(await screen.findByRole("heading", { name: "Teams (1)" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "PLATFORM" })).not.toBeInTheDocument();
  });

  it("reports a failed delete and keeps the list", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({ data: { data: [team("t-1", "PLATFORM")] } });
    (axiosInstance.delete as jest.Mock).mockRejectedValue(new Error("in use"));
    renderTeams();

    fireEvent.click(await screen.findByRole("button", { name: "Delete team PLATFORM" }));
    fireEvent.change(screen.getByLabelText("Type the name to confirm"), { target: { value: "PLATFORM" } });
    fireEvent.click(await screen.findByRole("button", { name: "Delete team" }));

    expect(await screen.findByText("Could not delete the team: in use")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "PLATFORM" })).toBeInTheDocument();
    expect(axiosInstance.get).toHaveBeenCalledTimes(1);
  });

  it("refetches the teams when the editor saves, without a remount", async () => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({ data: { data: [] } });
    renderTeams({ editorMode: "new" });
    await waitFor(() => expect(axiosInstance.get).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole("button", { name: "Save team" }));

    await waitFor(() => expect(axiosInstance.get).toHaveBeenCalledTimes(2));
    expect(axiosInstance.get).toHaveBeenLastCalledWith("organization/org-1/team");
  });
});
