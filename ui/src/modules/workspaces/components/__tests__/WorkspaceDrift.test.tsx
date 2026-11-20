import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { axe, toHaveNoViolations } from "jest-axe";
import { MemoryRouter } from "react-router-dom";
import { Workspace } from "@/domain/types";
import { listDriftChecks } from "../../driftService";
import WorkspaceDrift from "../WorkspaceDrift";

expect.extend(toHaveNoViolations);

jest.mock("../../driftService");
const list = listDriftChecks as jest.Mock;

const workspace = (attributes: Partial<Workspace["attributes"]>): Workspace =>
  ({ id: "ws-1", attributes: { name: "ledger-db", ...attributes }, relationships: {} }) as unknown as Workspace;

const renderDrift = (ws: Workspace) =>
  render(
    <MemoryRouter>
      <WorkspaceDrift orgId="org-1" workspace={ws} />
    </MemoryRouter>
  );

const checks = [
  { id: "9", createdDate: "2026-11-14T02:00:00Z", result: "drift", add: 0, change: 2, destroy: 0 },
  { id: "8", createdDate: "2026-11-13T02:00:00Z", result: "drift", add: 0, change: 1, destroy: 0 },
  { id: "7", createdDate: "2026-11-10T02:00:00Z", result: "clean", add: 0, change: 0, destroy: 0 },
];

describe("WorkspaceDrift", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    list.mockResolvedValue({ isError: false, data: checks });
  });

  it("shows nothing for a workspace that was never checked", () => {
    const { container } = renderDrift(workspace({ driftStatus: null }));

    expect(container).toBeEmptyDOMElement();
    expect(list).not.toHaveBeenCalled();
  });

  it("says since when it drifted, how many resources differ, and links to the plan", async () => {
    renderDrift(
      workspace({
        driftStatus: "drifted",
        driftFirstSeen: "2026-11-11T02:00:00Z",
        driftLastChecked: "2026-11-14T02:00:00Z",
        driftJobId: 9,
      })
    );

    expect(await screen.findByText("Drifted")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/2 resources differ/)).toBeInTheDocument());
    expect(screen.getByRole("link", { name: "View plan" })).toHaveAttribute(
      "href",
      "/organizations/org-1/workspaces/ws-1/runs/9"
    );
  });

  it("draws one block per check, oldest first, and says how many found drift", async () => {
    renderDrift(workspace({ driftStatus: "drifted", driftFirstSeen: "2026-11-11T02:00:00Z", driftJobId: 9 }));

    const bar = await screen.findByRole("img", { name: "Last 3 drift checks, 2 found drift" });
    const blocks = Array.from(bar.children);
    expect(blocks.map((block) => block.className)).toEqual([
      "workspace-drift-block workspace-drift-block-clean",
      "workspace-drift-block workspace-drift-block-drift",
      "workspace-drift-block workspace-drift-block-drift",
    ]);
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("lists the checks behind a toggle, with the counts of the ones that found drift", async () => {
    renderDrift(workspace({ driftStatus: "drifted", driftFirstSeen: "2026-11-11T02:00:00Z", driftJobId: 9 }));

    const toggle = await screen.findByRole("button", { name: "Show checks" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(toggle);

    expect(screen.getByRole("table", { name: "Drift checks" })).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(4);
    expect(screen.getByText("0 to add, 1 to change, 0 to destroy")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Hide checks" })).toHaveAttribute("aria-expanded", "true");
  });

  it("says a clean workspace has no drift, without a list when there is only one check", async () => {
    list.mockResolvedValue({ isError: false, data: [checks[2]] });
    renderDrift(workspace({ driftStatus: "clean", driftLastChecked: "2026-11-14T02:00:00Z", driftJobId: 7 }));

    expect(await screen.findByText("No drift")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("still says what it knows when the checks cannot be read", async () => {
    list.mockResolvedValue({ isError: true });
    renderDrift(workspace({ driftStatus: "drifted", driftFirstSeen: "2026-11-11T02:00:00Z", driftJobId: 9 }));

    expect(await screen.findByText("Drifted")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("has no accessibility violations", async () => {
    const { container } = renderDrift(
      workspace({ driftStatus: "drifted", driftFirstSeen: "2026-11-11T02:00:00Z", driftJobId: 9 })
    );
    fireEvent.click(await screen.findByRole("button", { name: "Show checks" }));
    await screen.findByRole("table", { name: "Drift checks" });

    expect(await axe(container)).toHaveNoViolations();
  });
});
