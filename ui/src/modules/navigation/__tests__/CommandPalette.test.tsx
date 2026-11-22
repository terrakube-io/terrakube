import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import CommandPalette from "../CommandPalette";
import { openCommandPalette } from "../openPalette";
import * as service from "../navigationService";
import { confirmApprove } from "@/modules/inbox/confirmApprove";
import { approveRuns, findWaitingRuns } from "@/modules/projects/bulkWorkspaceActions";

jest.mock("../navigationService");
jest.mock("@/modules/inbox/confirmApprove", () => ({ confirmApprove: jest.fn() }));
jest.mock("@/modules/projects/bulkWorkspaceActions");
const mocked = service as jest.Mocked<typeof service>;

const ORG_A = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
const ORG_B = "4fa85f64-5717-4562-b3fc-2c963f66afa6";
const orgs = [
  { id: ORG_A, name: "JXP" },
  { id: ORG_B, name: "Admin" },
];

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>;
}

function renderPalette(path = `/organizations/${ORG_A}/projects`, organizations = orgs) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <CommandPalette organizations={organizations} />
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>
  );
}

const openWithKeyboard = () => fireEvent.keyDown(window, { key: "k", code: "KeyK", ctrlKey: true });

// A real key press always has a code, and tinykeys ignores an event without one.
const press = (key: string, target: Window | Node = window) =>
  fireEvent.keyDown(target, { key, code: `Key${key.toUpperCase()}` });

describe("CommandPalette", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    mocked.searchOrganization.mockResolvedValue({ projects: [], workspaces: [] });
  });

  describe("approving the waiting run of a workspace", () => {
    const search = () =>
      mocked.searchOrganization.mockResolvedValue({
        projects: [],
        workspaces: [{ id: "ws-1", name: "ledger-db" }],
      } as never);

    it("offers it right under the selected workspace, and asks before approving", async () => {
      mocked.searchOrganization.mockResolvedValue({
        projects: [],
        workspaces: [
          { id: "ws-1", name: "ledger-db" },
          { id: "ws-2", name: "ledger-web" },
        ],
      } as never);
      (findWaitingRuns as jest.Mock).mockResolvedValue([{ jobId: "412", workspaceId: "ws-1" }]);
      renderPalette();
      openWithKeyboard();
      const input = await screen.findByRole("combobox", { name: "Search" });
      fireEvent.change(input, { target: { value: "ledger" } });
      await screen.findByRole("option", { name: /^ledger-web/ });

      const action = await screen.findByRole("option", { name: /Approve run #412/ });
      const rows = screen.getAllByRole("option");
      expect(rows[rows.indexOf(action) - 1]).toHaveTextContent("ledger-db");
      expect(action).toHaveTextContent("ledger-db");

      fireEvent.keyDown(input, { key: "ArrowDown" });
      await waitFor(() =>
        expect(screen.getByRole("option", { name: /Approve run #412/ })).toHaveAttribute("aria-selected", "true")
      );
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() =>
        expect(confirmApprove).toHaveBeenCalledWith(
          expect.objectContaining({ orgId: ORG_A, workspaceId: "ws-1", name: "ledger-db" }),
          expect.any(Function)
        )
      );
      expect(approveRuns).not.toHaveBeenCalled();
    });

    it("offers nothing when the workspace has no run waiting, and nothing for a failed lookup", async () => {
      search();
      (findWaitingRuns as jest.Mock).mockResolvedValueOnce([]);
      renderPalette();
      openWithKeyboard();
      const input = await screen.findByRole("combobox", { name: "Search" });
      fireEvent.change(input, { target: { value: "ledger" } });
      await screen.findByRole("option", { name: /^ledger-db/ });

      await waitFor(() => expect(findWaitingRuns).toHaveBeenCalled());
      expect(screen.queryByRole("option", { name: /Approve run/ })).not.toBeInTheDocument();
    });
  });

  it.each([
    ["i", "/inbox"],
    ["p", `/organizations/${ORG_A}/projects`],
    ["w", `/organizations/${ORG_A}/workspaces`],
  ])("goes to the right page with g then %s", (second, expected) => {
    renderPalette(`/organizations/${ORG_A}/settings`);

    press("g");
    press(second);

    expect(screen.getByTestId("where")).toHaveTextContent(expected);
  });

  it("does not move on while someone is typing in a field", () => {
    renderPalette();
    const field = document.createElement("input");
    document.body.appendChild(field);

    press("g", field);
    press("i", field);

    expect(screen.getByTestId("where")).toHaveTextContent(`/organizations/${ORG_A}/projects`);
    field.remove();
  });

  it("opens with Ctrl+K and closes with the same key, and lists pages and organizations", async () => {
    renderPalette();
    expect(screen.queryByRole("combobox", { name: "Search" })).not.toBeInTheDocument();

    openWithKeyboard();

    expect(await screen.findByRole("combobox", { name: "Search" })).toBeInTheDocument();
    for (const title of ["Projects", "Workspaces", "Registry", "Settings", "All organizations", "JXP", "Admin"]) {
      expect(screen.getByRole("option", { name: new RegExp(`^${title}`) })).toBeInTheDocument();
    }
    openWithKeyboard();
    await waitFor(() => expect(screen.queryByRole("combobox", { name: "Search" })).not.toBeInTheDocument());
  });

  it("opens from the sidebar button too", async () => {
    renderPalette();

    act(() => openCommandPalette());

    expect(await screen.findByRole("combobox", { name: "Search" })).toBeInTheDocument();
  });

  it("finds workspaces and projects in every organization and says where each one is", async () => {
    mocked.searchOrganization.mockImplementation(async (orgId) =>
      orgId === ORG_A
        ? { projects: [{ id: "p1", name: "ledger" }], workspaces: [{ id: "w1", name: "ledger-db" }] }
        : { projects: [], workspaces: [{ id: "w2", name: "ledger-admin" }] }
    );
    renderPalette();
    openWithKeyboard();

    fireEvent.change(await screen.findByRole("combobox", { name: "Search" }), { target: { value: "ledger" } });

    expect(await screen.findByRole("option", { name: /ledger-db JXP/ })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /ledger-admin Admin/ })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /^ledger JXP.*Project/ })).toBeInTheDocument();
    expect(mocked.searchOrganization).toHaveBeenCalledWith(ORG_A, "ledger");
    expect(mocked.searchOrganization).toHaveBeenCalledWith(ORG_B, "ledger");
  });

  it("moves with the arrow keys and opens the chosen result with Enter", async () => {
    mocked.searchOrganization.mockResolvedValue({ projects: [], workspaces: [{ id: "w1", name: "ledger-db" }] });
    renderPalette();
    openWithKeyboard();
    const input = await screen.findByRole("combobox", { name: "Search" });

    fireEvent.change(input, { target: { value: "ledger" } });
    await screen.findByRole("option", { name: /ledger-db/ });
    fireEvent.keyDown(input, { key: "Enter" });

    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(`/organizations/${ORG_A}/workspaces/w1`));
    expect(screen.queryByRole("combobox", { name: "Search" })).not.toBeInTheDocument();
  });

  it("closes with the same key and starts empty the next time", async () => {
    renderPalette();
    openWithKeyboard();
    fireEvent.change(await screen.findByRole("combobox", { name: "Search" }), { target: { value: "ledger" } });

    openWithKeyboard();
    await waitFor(() => expect(screen.queryByRole("combobox", { name: "Search" })).not.toBeInTheDocument());
    openWithKeyboard();

    expect(await screen.findByRole("combobox", { name: "Search" })).toHaveValue("");
  });

  it("does not offer the results of the text before while the new text is being searched", async () => {
    const finishSecond: Array<(value: { projects: []; workspaces: { id: string; name: string }[] }) => void> = [];
    mocked.searchOrganization
      .mockResolvedValueOnce({ projects: [], workspaces: [{ id: "w1", name: "ledger-db" }] })
      .mockResolvedValueOnce({ projects: [], workspaces: [{ id: "w1", name: "ledger-db" }] })
      .mockImplementation(() => new Promise((resolve) => finishSecond.push(resolve as (typeof finishSecond)[number])));
    renderPalette();
    openWithKeyboard();
    const input = await screen.findByRole("combobox", { name: "Search" });
    fireEvent.change(input, { target: { value: "ledger" } });
    await screen.findByRole("option", { name: /ledger-db/ });

    fireEvent.change(input, { target: { value: "ledger-adm" } });

    await waitFor(() => expect(screen.queryByRole("option", { name: /ledger-db/ })).not.toBeInTheDocument());
    await waitFor(() => expect(finishSecond).toHaveLength(2));
    act(() =>
      finishSecond.forEach((finish) => finish({ projects: [], workspaces: [{ id: "w9", name: "ledger-admin" }] }))
    );
    expect(await screen.findByRole("option", { name: /ledger-admin/ })).toBeInTheDocument();
  });

  it("keeps the selection inside the list", async () => {
    renderPalette();
    openWithKeyboard();
    const input = await screen.findByRole("combobox", { name: "Search" });

    fireEvent.keyDown(input, { key: "ArrowUp" });
    expect(screen.getAllByRole("option")[0]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(screen.getAllByRole("option")[1]).toHaveAttribute("aria-selected", "true");
    for (let index = 0; index < 30; index++) fireEvent.keyDown(input, { key: "ArrowDown" });
    const options = screen.getAllByRole("option");
    expect(options[options.length - 1]).toHaveAttribute("aria-selected", "true");
  });

  it("offers what was opened before when the box is empty", async () => {
    localStorage.setItem(
      "terrakube-palette-recent",
      JSON.stringify([{ key: "workspace:w9", kind: "workspace", title: "recent-one", subtitle: "JXP", to: "/x" }])
    );
    renderPalette();

    openWithKeyboard();

    expect(await screen.findByRole("option", { name: /recent-one/ })).toBeInTheDocument();
  });

  it("remembers a workspace after it is opened", async () => {
    mocked.searchOrganization.mockResolvedValue({ projects: [], workspaces: [{ id: "w1", name: "ledger-db" }] });
    renderPalette();
    openWithKeyboard();
    const input = await screen.findByRole("combobox", { name: "Search" });
    fireEvent.change(input, { target: { value: "ledger" } });
    fireEvent.click(await screen.findByRole("option", { name: /ledger-db/ }));

    expect(JSON.parse(localStorage.getItem("terrakube-palette-recent") ?? "[]")[0]).toMatchObject({
      title: "ledger-db",
    });
  });

  it("says when nothing matches, and when search is unavailable", async () => {
    renderPalette();
    openWithKeyboard();
    const input = await screen.findByRole("combobox", { name: "Search" });

    fireEvent.change(input, { target: { value: "zzz-nothing" } });
    expect(await screen.findByText('Nothing matches "zzz-nothing".')).toBeInTheDocument();

    mocked.searchOrganization.mockRejectedValue(new Error("down"));
    fireEvent.change(input, { target: { value: "ledger" } });
    expect(await screen.findByText("Search is unavailable right now.")).toBeInTheDocument();
  });

  it("searches only the current organization when there are too many to ask each one", async () => {
    const many = Array.from({ length: 13 }, (_, index) => ({
      id: `${index.toString(16).padStart(8, "0")}-5717-4562-b3fc-2c963f66afa6`,
      name: `org-${index}`,
    }));
    many[0] = { id: ORG_A, name: "JXP" };
    renderPalette(`/organizations/${ORG_A}/projects`, many);
    openWithKeyboard();

    fireEvent.change(await screen.findByRole("combobox", { name: "Search" }), { target: { value: "ledger" } });

    await waitFor(() => expect(mocked.searchOrganization).toHaveBeenCalledTimes(1));
    expect(mocked.searchOrganization).toHaveBeenCalledWith(ORG_A, "ledger");
  });

  it("ignores a slow answer that was overtaken by a newer search", async () => {
    let releaseFirst: (value: service.SearchHits) => void = () => undefined;
    mocked.searchOrganization
      .mockImplementationOnce(() => new Promise((resolve) => (releaseFirst = resolve)))
      .mockResolvedValue({ projects: [], workspaces: [{ id: "w2", name: "second-result" }] });
    renderPalette(`/organizations/${ORG_A}/projects`, [orgs[0]]);
    openWithKeyboard();
    const input = await screen.findByRole("combobox", { name: "Search" });

    fireEvent.change(input, { target: { value: "first" } });
    await waitFor(() => expect(mocked.searchOrganization).toHaveBeenCalledTimes(1));
    fireEvent.change(input, { target: { value: "second" } });
    expect(await screen.findByRole("option", { name: /second-result/ })).toBeInTheDocument();
    await act(async () => releaseFirst({ projects: [], workspaces: [{ id: "w1", name: "first-result" }] }));

    expect(screen.queryByRole("option", { name: /first-result/ })).not.toBeInTheDocument();
  });
});
