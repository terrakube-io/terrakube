import { render, screen } from "@testing-library/react";
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
        },
      ],
    });
    render(<WorkspaceTeamAccess workspace={workspace} manageWorkspace={true} />);

    expect(await screen.findByText("1 team has access")).toBeInTheDocument();
    expect(screen.getAllByText("Write").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "Remove access for team devs" })).toBeInTheDocument();
  });
});
