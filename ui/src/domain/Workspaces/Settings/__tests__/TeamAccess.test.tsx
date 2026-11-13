import { render, screen, within } from "@testing-library/react";
import { WorkspaceTeamAccess } from "../TeamAccess";
import workspaceAccessService from "@/modules/workspaces/workspaceAccessService";

jest.mock("@/config/axiosConfig", () => ({
  __esModule: true,
  default: { get: jest.fn().mockResolvedValue({ data: { data: [] } }) },
}));
jest.mock("@/modules/workspaces/workspaceAccessService", () => ({
  __esModule: true,
  default: { listWorkspaceAccess: jest.fn() },
}));

const workspace = { id: "ws-1", relationships: { organization: { data: { id: "org-1" } } } } as any;
const list = workspaceAccessService.listWorkspaceAccess as jest.Mock;

describe("WorkspaceTeamAccess", () => {
  it("shows only the empty state when no team has access", async () => {
    list.mockResolvedValue({ isError: false, data: [] });
    render(<WorkspaceTeamAccess workspace={workspace} manageWorkspace={true} />);

    expect(await screen.findByText("No teams have been granted access to this workspace yet.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Add a team/ })).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("uses singular grammar and role labels from the role list", async () => {
    list.mockResolvedValue({
      isError: false,
      data: [
        {
          id: "a1",
          name: "devs",
          role: "write",
          manageWorkspace: false,
          manageState: false,
          planJob: false,
          approveJob: false,
          destroyJob: false,
        },
      ],
    });
    render(<WorkspaceTeamAccess workspace={workspace} manageWorkspace={true} />);

    expect(await screen.findByText("1 team has access")).toBeInTheDocument();
    expect(screen.getAllByText("Write").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "Remove access for team devs" })).toBeInTheDocument();
  });

  it("lists who can destroy: the role decides for a preset role, the saved right for a custom one", async () => {
    const base = { manageWorkspace: false, manageState: false, planJob: true, approveJob: false };
    list.mockResolvedValue({
      isError: false,
      data: [
        { id: "a1", name: "planners", role: "plan", ...base, destroyJob: true },
        { id: "a2", name: "writers", role: "write", ...base, destroyJob: false },
        { id: "a3", name: "ops", role: "custom", ...base, destroyJob: true },
      ],
    });
    render(<WorkspaceTeamAccess workspace={workspace} manageWorkspace={true} />);

    expect(await screen.findByText("3 teams have access")).toBeInTheDocument();
    const rows = screen.getAllByRole("row");
    const destroy = (team: string) =>
      within(rows.find((row) => within(row).queryByText(team))!)
        .queryByLabelText(/destroy runs/i)
        ?.getAttribute("aria-label");
    expect(destroy("planners")).toBe("Cannot destroy runs");
    expect(destroy("writers")).toBe("Can destroy runs");
    expect(destroy("ops")).toBe("Can destroy runs");
  });
});
