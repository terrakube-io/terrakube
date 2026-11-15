import { focusManager, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { message } from "antd";
import { UserManager } from "oidc-client-ts";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import axiosInstance from "../../../config/axiosConfig";
import { clearQueriesOnSignOut, createQueryClient } from "@/modules/api/queryClient";
import { TeamSettings } from "../Teams";

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn(), delete: jest.fn() },
  getErrorMessage: (err: any) => err?.message || "Error",
  isPermissionError: () => false,
}));

// The real form saves and then asks the list to reload; the stub does only the second part. Its delete asks the
// page to delete the team, as the real editor does before it navigates back to the list.
jest.mock("../EditTeam", () => ({
  EditTeam: ({ loadTeams, onDeleteTeam }: { loadTeams: () => void; onDeleteTeam: (id: string) => void }) => (
    <>
      <button type="button" onClick={loadTeams}>
        Save team
      </button>
      <button type="button" onClick={() => onDeleteTeam("t-1")}>
        Delete from editor
      </button>
    </>
  ),
}));

const team = (id: string, name: string, attributes: Record<string, unknown> = {}) => ({
  id,
  attributes: { name, ...attributes },
});

// A fresh client per render unless a test passes one, so no test sees another test's cached teams.
const renderTeams = (props: { editorMode?: "new" | "edit"; editorId?: string } = {}, client?: QueryClient) =>
  render(
    <QueryClientProvider client={client ?? createQueryClient()}>
      <MemoryRouter initialEntries={["/organizations/org-1/settings/teams"]}>
        <Routes>
          <Route path="/organizations/:orgid/settings/teams" element={<TeamSettings {...props} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

describe("TeamSettings list", () => {
  beforeEach(() => jest.clearAllMocks());
  // Messages live outside the rendered tree; drop them so a test never finds one left by the previous test.
  afterEach(() => message.destroy());

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

  it.each([
    ["succeeds", "resolve", "Team deleted successfully"],
    ["fails", "reject", "Could not delete the team: in use"],
  ] as const)("reports a delete from the editor that %s after the page is gone", async (_, outcome, text) => {
    (axiosInstance.get as jest.Mock).mockResolvedValue({ data: { data: [team("t-1", "PLATFORM")] } });
    const request = deferred<object>();
    (axiosInstance.delete as jest.Mock).mockReturnValue(request.promise);
    const { unmount } = renderTeams({ editorMode: "edit", editorId: "t-1" });

    fireEvent.click(screen.getByRole("button", { name: "Delete from editor" }));
    // The editor navigates to the list straight away, which mounts a new page before the server answers.
    unmount();
    if (outcome === "resolve") request.resolve({});
    else request.reject(new Error("in use"));

    expect(await screen.findByText(text)).toBeInTheDocument();
  });

  it("does not show the previous user's teams after a sign-out", async () => {
    const client = createQueryClient();
    const users = new UserManager({ authority: "https://idp.test", client_id: "ui", redirect_uri: "https://ui.test" });
    const stopClearing = clearQueriesOnSignOut(client, users);
    (axiosInstance.get as jest.Mock)
      .mockResolvedValueOnce({ data: { data: [team("t-1", "PLATFORM")] } })
      .mockReturnValue(new Promise(() => {}));
    const first = renderTeams({}, client);
    expect(await screen.findByRole("link", { name: "PLATFORM" })).toBeInTheDocument();

    // Signing out unmounts the app; the next user signs in from the same tab.
    first.unmount();
    await users.removeUser();
    renderTeams({}, client);

    await waitFor(() => expect(axiosInstance.get).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("link", { name: "PLATFORM" })).not.toBeInTheDocument();
    stopClearing();
  });

  it("keeps a single load error message across refetches on window focus", async () => {
    // A new error per request, as axios gives, so every failed refetch reaches the page as a new error.
    (axiosInstance.get as jest.Mock).mockImplementation(() => Promise.reject(new Error("down")));
    renderTeams();
    expect(await screen.findByText("Could not load teams: down")).toBeInTheDocument();

    try {
      focusManager.setFocused(false);
      focusManager.setFocused(true);
      await waitFor(() => expect(axiosInstance.get).toHaveBeenCalledTimes(2));
      await waitFor(() => expect(screen.getAllByText("Could not load teams: down")).toHaveLength(1));
    } finally {
      focusManager.setFocused(undefined);
    }
  });
});
