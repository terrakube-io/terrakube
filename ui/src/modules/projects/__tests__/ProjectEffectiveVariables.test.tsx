import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import workspaceService from "@/modules/workspaces/workspaceService";
import projectService from "../projectService";
import ProjectEffectiveVariables from "../ProjectEffectiveVariables";

jest.mock("@/modules/workspaces/workspaceService", () => ({
  __esModule: true,
  default: { listWorkspacePage: jest.fn() },
}));
jest.mock("../projectService", () => ({ __esModule: true, default: { getWorkspaceVariableData: jest.fn() } }));

const list = workspaceService.listWorkspacePage as jest.Mock;
const load = projectService.getWorkspaceVariableData as jest.Mock;

const entry = (key: string, value: string | null, extra = {}) => ({
  id: key,
  key,
  value,
  category: "ENV",
  sensitive: false,
  hcl: false,
  ...extra,
});

const renderIt = () =>
  render(
    <MemoryRouter>
      <ProjectEffectiveVariables orgid="org" projectId="p1" />
    </MemoryRouter>
  );

describe("ProjectEffectiveVariables", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    list.mockResolvedValue({ isError: false, data: { workspaces: [{ id: "w1", name: "ledger-db" }] } });
    load.mockResolvedValue({
      isError: false,
      data: {
        projectId: "p1",
        variables: [entry("AWS_REGION", "eu-west-1")],
        globals: [entry("TF_VAR_env", "prod")],
        collections: [
          {
            id: "c1",
            name: "payments-common",
            priority: 1,
            scope: "PROJECT",
            items: [entry("LOG_LEVEL", "info"), entry("DB_PASSWORD", null, { sensitive: true })],
          },
        ],
      },
    });
  });

  it("shows each variable with its source and a link to where it is edited", async () => {
    renderIt();

    expect(await screen.findByText("Effective variables for workspace ledger-db")).toBeInTheDocument();
    expect(await screen.findByText("eu-west-1")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "workspace" })).toHaveAttribute(
      "href",
      "/organizations/org/workspaces/w1/variables"
    );
    expect(screen.getByRole("link", { name: "organisation global" })).toHaveAttribute(
      "href",
      "/organizations/org/settings/variables"
    );
    expect(screen.getAllByRole("link", { name: 'collection "payments-common" (project)' })[0]).toHaveAttribute(
      "href",
      "/organizations/org/settings/collection/edit/c1"
    );
  });

  it("masks sensitive values", async () => {
    renderIt();

    expect(await screen.findByText("(sensitive)")).toBeInTheDocument();
  });

  it("explains the precedence", async () => {
    renderIt();

    expect(await screen.findByText(/Precedence: workspace, then organisation global variables/)).toBeInTheDocument();
  });

  it("says when the variables could not be loaded", async () => {
    load.mockResolvedValue({ isError: true, data: undefined });

    renderIt();

    expect(await screen.findByText("Variables could not be loaded for this workspace.")).toBeInTheDocument();
  });

  it("reloads when another workspace is chosen", async () => {
    list.mockResolvedValue({
      isError: false,
      data: {
        workspaces: [
          { id: "w1", name: "ledger-db" },
          { id: "w2", name: "gateway-eu" },
        ],
      },
    });
    renderIt();
    await screen.findByText("eu-west-1");

    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Workspace" }));
    fireEvent.click(await screen.findByTitle("gateway-eu"));

    await waitFor(() => expect(load).toHaveBeenCalledWith("org", "w2"));
  });
});
