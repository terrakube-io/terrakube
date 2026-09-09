import { render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { RunTriggers } from "../RunTriggers";

const getMock = jest.fn();
const deleteMock = jest.fn();

jest.mock("../../../config/axiosConfig", () => ({
  __esModule: true,
  default: {
    get: (...args: unknown[]) => getMock(...args),
    delete: (...args: unknown[]) => deleteMock(...args),
    post: jest.fn(),
    patch: jest.fn(),
  },
  getErrorMessage: () => "error",
}));

const WORKSPACE = "ws-self";
const SOURCE = "ws-upstream";
const DESTINATION = "ws-downstream";

/**
 * One edge in each direction, as the API returns them: a single collection where this
 * workspace appears sometimes as source and sometimes as destination.
 */
const bothDirections = {
  data: {
    data: [
      {
        id: "edge-incoming",
        attributes: { enabled: true },
        relationships: {
          sourceWorkspace: { data: { type: "workspace", id: SOURCE } },
          destinationWorkspace: { data: { type: "workspace", id: WORKSPACE } },
          template: { data: { type: "template", id: "tpl-1" } },
        },
      },
      {
        id: "edge-outgoing",
        attributes: { enabled: false },
        relationships: {
          sourceWorkspace: { data: { type: "workspace", id: WORKSPACE } },
          destinationWorkspace: { data: { type: "workspace", id: DESTINATION } },
        },
      },
    ],
    included: [
      { type: "workspace", id: SOURCE, attributes: { name: "network" } },
      { type: "workspace", id: DESTINATION, attributes: { name: "vpn" } },
      { type: "template", id: "tpl-1", attributes: { name: "Plan and Apply" } },
    ],
  },
};

const renderPage = (manageWorkspace = true) =>
  render(
    <MemoryRouter>
      <RunTriggers
        organizationId="org-1"
        workspaceId={WORKSPACE}
        workspaceName="platform"
        manageWorkspace={manageWorkspace}
      />
    </MemoryRouter>
  );

describe("RunTriggers", () => {
  beforeEach(() => {
    getMock.mockReset();
    deleteMock.mockReset();
    getMock.mockResolvedValue(bothDirections);
  });

  it("asks the API for both directions in a single request", async () => {
    renderPage();

    await waitFor(() => expect(getMock).toHaveBeenCalled());
    const [url, config] = getMock.mock.calls[0];
    expect(url).toBe("runTrigger");
    expect(config.params["filter[runTrigger]"]).toBe(
      `sourceWorkspace.id==${WORKSPACE},destinationWorkspace.id==${WORKSPACE}`
    );
    expect(config.params.include).toContain("sourceWorkspace");
    expect(config.params.include).toContain("destinationWorkspace");
  });

  /**
   * The split is the whole point of the page: the same collection has to land in the right
   * table, or a dependency reads backwards.
   */
  it("separates incoming from outgoing edges", async () => {
    renderPage();

    const runsAfter = await screen.findByRole("link", { name: "network" });
    const triggers = await screen.findByRole("link", { name: "vpn" });

    const tables = screen.getAllByRole("table");
    expect(within(tables[0]).getByRole("link", { name: "network" })).toBe(runsAfter);
    expect(within(tables[1]).getByRole("link", { name: "vpn" })).toBe(triggers);
  });

  it("names the trigger's own template and falls back to the default", async () => {
    renderPage();

    expect(await screen.findByText("Plan and Apply")).toBeInTheDocument();
    expect(screen.getByText("Default template")).toBeInTheDocument();
  });

  /**
   * The outgoing table is read-only on purpose: removing one of those edges needs manage
   * rights on the other workspace, which is where the API enforces it.
   */
  it("offers no delete for edges this workspace only feeds", async () => {
    renderPage();

    await screen.findByRole("link", { name: "vpn" });
    const tables = screen.getAllByRole("table");
    expect(within(tables[0]).getAllByRole("button", { name: /delete/i })).toHaveLength(1);
    expect(within(tables[1]).queryByRole("button", { name: /delete/i })).toBeNull();
  });

  it("disables every control without manage rights", async () => {
    renderPage(false);

    await screen.findByRole("link", { name: "network" });
    expect(screen.getByRole("button", { name: /add source workspace/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /delete/i })).toBeDisabled();
  });

  it("says so when nothing depends on this workspace", async () => {
    getMock.mockResolvedValue({ data: { data: [], included: [] } });
    renderPage();

    expect(await screen.findByText("This workspace does not run after any other workspace.")).toBeInTheDocument();
    expect(screen.getByText("No workspace runs after this one.")).toBeInTheDocument();
  });
});
