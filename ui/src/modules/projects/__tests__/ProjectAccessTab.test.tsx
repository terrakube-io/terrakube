import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ProjectAccessTab from "../ProjectAccessTab";
import projectService, { ProjectAccessModel } from "../projectService";

jest.mock("@/config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: jest.fn().mockResolvedValue({ data: { data: [{ id: "t1", attributes: { name: "app-team" } }] } }),
  },
}));
jest.mock("../projectService", () => ({
  __esModule: true,
  default: { listProjectAccess: jest.fn(), addProjectAccess: jest.fn(), updateProjectAccess: jest.fn() },
}));

const list = projectService.listProjectAccess as jest.Mock;
const add = projectService.addProjectAccess as jest.Mock;
const update = projectService.updateProjectAccess as jest.Mock;

const access = (overrides: Partial<ProjectAccessModel>): ProjectAccessModel => ({
  id: "a1",
  name: "devs",
  role: "write",
  manageWorkspace: false,
  manageState: false,
  manageJob: false,
  planJob: false,
  approveJob: false,
  destroyJob: false,
  ...overrides,
});

const rowOf = (team: string) => screen.getAllByRole("row").find((row) => within(row).queryByText(team))!;

const permission = (team: string, name: RegExp) => within(rowOf(team)).getByLabelText(name).getAttribute("aria-label");

async function chooseOption(user: ReturnType<typeof userEvent.setup>, combobox: HTMLElement, option: string) {
  await user.click(combobox);
  const options = await screen.findAllByText(option, { selector: ".ant-tag" });
  await user.click(options.at(-1)!);
}

describe("ProjectAccessTab", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    add.mockResolvedValue(undefined);
    update.mockResolvedValue(undefined);
  });

  it("shows the permissions of a preset role and the saved permissions of a custom one", async () => {
    list.mockResolvedValue({
      isError: false,
      data: [
        access({ id: "a1", name: "planners", role: "plan", manageWorkspace: true, approveJob: true }),
        access({ id: "a2", name: "app-team", role: "custom", planJob: true, approveJob: true, destroyJob: true }),
      ],
    });
    render(<ProjectAccessTab orgid="org-1" projectId="proj-1" canManage={true} />);

    expect(await screen.findByText("app-team", { selector: "strong" })).toBeInTheDocument();
    expect(within(rowOf("app-team")).getByText("Custom")).toBeInTheDocument();

    expect(permission("planners", /manage workspaces/i)).toBe("Cannot manage workspaces");
    expect(permission("planners", /plan runs/i)).toBe("Can plan runs");
    expect(permission("planners", /approve runs/i)).toBe("Cannot approve runs");

    expect(permission("app-team", /manage workspaces/i)).toBe("Cannot manage workspaces");
    expect(permission("app-team", /manage state/i)).toBe("Cannot manage state");
    expect(permission("app-team", /plan runs/i)).toBe("Can plan runs");
    expect(permission("app-team", /approve runs/i)).toBe("Can approve runs");
    expect(permission("app-team", /destroy runs/i)).toBe("Can destroy runs");
  });

  it("adds a team with a custom role and the permissions picked for it", async () => {
    const user = userEvent.setup();
    list.mockResolvedValue({ isError: false, data: [] });
    render(<ProjectAccessTab orgid="org-1" projectId="proj-1" canManage={true} />);

    const [teamSelect, roleSelect] = await screen.findAllByRole("combobox");
    await user.click(teamSelect);
    await user.click(await screen.findByTitle("app-team"));
    await chooseOption(user, roleSelect, "Custom");

    await user.click(await screen.findByRole("checkbox", { name: "Plan runs" }));
    await user.click(screen.getByRole("checkbox", { name: "Approve runs" }));
    await user.click(screen.getByRole("checkbox", { name: "Destroy runs" }));
    await user.click(screen.getByRole("button", { name: /Add Team/ }));

    await waitFor(() =>
      expect(add).toHaveBeenCalledWith("org-1", "proj-1", "app-team", "custom", {
        manageWorkspace: false,
        manageState: false,
        planJob: true,
        approveJob: true,
        destroyJob: true,
      })
    );
  });

  it("adds a team with a preset role without sending individual permissions", async () => {
    const user = userEvent.setup();
    list.mockResolvedValue({ isError: false, data: [] });
    render(<ProjectAccessTab orgid="org-1" projectId="proj-1" canManage={true} />);

    const [teamSelect] = await screen.findAllByRole("combobox");
    await user.click(teamSelect);
    await user.click(await screen.findByTitle("app-team"));
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Add Team/ }));

    await waitFor(() => expect(add).toHaveBeenCalledWith("org-1", "proj-1", "app-team", "write", undefined));
  });

  it("edits a custom role starting from its saved permissions", async () => {
    const user = userEvent.setup();
    list.mockResolvedValue({
      isError: false,
      data: [access({ id: "a2", name: "app-team", role: "custom", planJob: true, approveJob: true })],
    });
    render(<ProjectAccessTab orgid="org-1" projectId="proj-1" canManage={true} />);

    await screen.findByText("app-team", { selector: "strong" });
    await user.click(within(rowOf("app-team")).getByRole("button", { name: "Change" }));
    const row = rowOf("app-team");
    expect(within(row).getByRole("checkbox", { name: "Plan runs" })).toBeChecked();
    expect(within(row).getByRole("checkbox", { name: "Approve runs" })).toBeChecked();
    expect(within(row).getByRole("checkbox", { name: "Destroy runs" })).not.toBeChecked();
    expect(within(row).getByRole("checkbox", { name: "Manage workspaces" })).not.toBeChecked();

    await user.click(within(row).getByRole("checkbox", { name: "Destroy runs" }));
    await user.click(within(row).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(update).toHaveBeenCalledWith("org-1", "proj-1", "a2", "custom", {
        manageWorkspace: false,
        manageState: false,
        planJob: true,
        approveJob: true,
        destroyJob: true,
      })
    );
  });

  it("starts a custom role from the permissions of the preset role it replaces", async () => {
    const user = userEvent.setup();
    list.mockResolvedValue({ isError: false, data: [access({ id: "a1", name: "planners", role: "plan" })] });
    render(<ProjectAccessTab orgid="org-1" projectId="proj-1" canManage={true} />);

    await screen.findByText("planners", { selector: "strong" });
    await user.click(within(rowOf("planners")).getByRole("button", { name: "Change" }));
    await chooseOption(user, within(rowOf("planners")).getByRole("combobox"), "Custom");
    await user.click(within(rowOf("planners")).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(update).toHaveBeenCalledWith("org-1", "proj-1", "a1", "custom", {
        manageWorkspace: false,
        manageState: false,
        planJob: true,
        approveJob: false,
        destroyJob: false,
      })
    );
  });
});
