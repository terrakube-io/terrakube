import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { InboxBell } from "../InboxBell";
import { emptyCounts, InboxData, InboxItem } from "@/modules/inbox/inboxModel";
import { loadInbox, loadOrganizationCounts } from "@/modules/inbox/inboxService";
import { listOrganizationNames } from "@/modules/navigation/navigationService";
import { confirmApprove } from "@/modules/inbox/confirmApprove";

jest.mock("@/modules/navigation/navigationService", () => ({ listOrganizationNames: jest.fn() }));
jest.mock("@/modules/inbox/inboxService", () => ({ loadInbox: jest.fn(), loadOrganizationCounts: jest.fn() }));
jest.mock("@/hooks/useOrganizationName", () => ({ useOrganizationName: (id: string) => (id ? "aws" : undefined) }));
jest.mock("@/modules/inbox/confirmApprove", () => ({ confirmApprove: jest.fn() }));

const load = loadInbox as jest.Mock;
const others = loadOrganizationCounts as jest.Mock;
const organizations = listOrganizationNames as jest.Mock;

const ORG = "3a130a1c-d96f-4f99-83b8-58d472567e3a";

const item = (kind: InboxItem["kind"], name: string): InboxItem => ({
  key: `${kind}:${name}`,
  kind,
  orgId: ORG,
  orgName: "aws",
  workspaceId: name,
  name,
  projectName: "Payments",
});

const data = (items: InboxItem[], counts: Partial<InboxData["counts"]> = {}): InboxData => ({
  items,
  counts: { ...emptyCounts(), ...counts },
  failedOrganizations: [],
});

const renderBell = (path = `/organizations/${ORG}/overview`) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <InboxBell />
    </MemoryRouter>
  );

describe("InboxBell", () => {
  beforeEach(() => {
    jest.resetAllMocks();
    sessionStorage.clear();
    organizations.mockResolvedValue([{ id: ORG, name: "aws" }]);
    others.mockResolvedValue({ rows: [], failedOrganizations: [] });
  });

  it("shows how many things need attention without being opened", async () => {
    load.mockResolvedValue(data([item("waiting", "ws-1"), item("failed", "ws-2")], { waiting: 1, failed: 1 }));
    renderBell();

    expect(await screen.findByRole("button", { name: "Needs attention, 2 items" })).toBeInTheDocument();
  });

  it("shows no count when nothing needs attention, and says so when opened", async () => {
    load.mockResolvedValue(data([]));
    renderBell();

    fireEvent.click(await screen.findByRole("button", { name: "Needs attention" }));

    expect(await screen.findByText("Nothing needs attention here.")).toBeInTheDocument();
  });

  it("lists the first items, approvals first, and links to the full page with the real total", async () => {
    const items = [
      item("waiting", "ws-wait"),
      ...Array.from({ length: 7 }, (_, index) => item("failed", `ws-failed-${index}`)),
    ];
    load.mockResolvedValue(data(items, { waiting: 1, failed: 7 }));
    renderBell();

    fireEvent.click(await screen.findByRole("button", { name: "Needs attention, 8 items" }));

    expect(await screen.findByText("ws-wait")).toBeInTheDocument();
    expect(screen.getByText("ws-failed-4")).toBeInTheDocument();
    expect(screen.queryByText("ws-failed-5")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "View all 8" })).toHaveAttribute("href", `/inbox?org=${ORG}`);
  });

  it("approves a waiting run from the list and reloads afterwards", async () => {
    load.mockResolvedValue(data([item("waiting", "ws-wait")], { waiting: 1 }));
    renderBell();

    fireEvent.click(await screen.findByRole("button", { name: "Needs attention, 1 item" }));
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));

    expect(confirmApprove).toHaveBeenCalledWith(expect.objectContaining({ name: "ws-wait" }), expect.any(Function));
  });

  it("only offers Approve for runs that wait for approval", async () => {
    load.mockResolvedValue(data([item("failed", "ws-failed")], { failed: 1 }));
    renderBell();

    fireEvent.click(await screen.findByRole("button", { name: "Needs attention, 1 item" }));

    expect(await screen.findByText("ws-failed")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("says so, instead of looking quiet, when the inbox cannot be read", async () => {
    load.mockRejectedValue(new Error("down"));
    renderBell();

    fireEvent.click(await screen.findByRole("button", { name: "Needs attention" }));

    await waitFor(() => expect(screen.getByText("Could not load what needs attention.")).toBeInTheDocument());
    expect(screen.queryByText("Nothing needs attention.")).not.toBeInTheDocument();
  });

  it("only reads the organization the person is working in", async () => {
    load.mockResolvedValue(data([item("failed", "ws-1")], { failed: 1 }));
    renderBell();

    await screen.findByRole("button", { name: "Needs attention, 1 item" });

    expect(load).toHaveBeenCalledTimes(1);
    expect(load).toHaveBeenCalledWith([{ id: ORG, name: "aws" }]);
    expect(organizations).not.toHaveBeenCalled();
    expect(others).not.toHaveBeenCalled();
  });

  it("falls back to the organization the person was last in when the address has none", async () => {
    sessionStorage.setItem("ORGANIZATION_ARCHIVE", ORG);
    load.mockResolvedValue(data([], {}));
    renderBell("/inbox");

    await waitFor(() => expect(load).toHaveBeenCalledWith([{ id: ORG, name: "aws" }]));
  });

  it("reads nothing at all with no organization to work in", async () => {
    renderBell("/organizations");

    fireEvent.click(await screen.findByRole("button", { name: "Needs attention" }));

    await waitFor(() => expect(organizations).toHaveBeenCalled());
    expect(load).not.toHaveBeenCalled();
  });

  it("counts the other organizations only when the panel is opened, and says where the rest are", async () => {
    load.mockResolvedValue(data([item("failed", "ws-1")], { failed: 1 }));
    organizations.mockResolvedValue([
      { id: ORG, name: "aws" },
      { id: "b", name: "Acme" },
      { id: "c", name: "Initech" },
    ]);
    others.mockResolvedValue({
      rows: [
        { org: { id: "b", name: "Acme" }, counts: { waiting: 2, failed: 3, drifted: 0, policy: 0 } },
        { org: { id: "c", name: "Initech" }, counts: { waiting: 0, failed: 0, drifted: 0, policy: 0 } },
      ],
      failedOrganizations: [],
    });
    renderBell();
    await screen.findByRole("button", { name: "Needs attention, 1 item" });
    expect(others).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Needs attention, 1 item" }));

    const link = await screen.findByRole("link", { name: "5 more in 1 other organization" });
    expect(link).toHaveAttribute("href", "/inbox?org=all");
    // The organization the person is in is not counted twice.
    expect(others).toHaveBeenCalledWith([
      { id: "b", name: "Acme" },
      { id: "c", name: "Initech" },
    ]);
  });

  it("does not count the other organizations again when the panel is reopened within a minute", async () => {
    load.mockResolvedValue(data([item("failed", "ws-1")], { failed: 1 }));
    organizations.mockResolvedValue([
      { id: ORG, name: "aws" },
      { id: "b", name: "Acme" },
    ]);
    others.mockResolvedValue({
      rows: [{ org: { id: "b", name: "Acme" }, counts: { waiting: 1, failed: 0, drifted: 0, policy: 0 } }],
      failedOrganizations: [],
    });
    renderBell();
    const bell = await screen.findByRole("button", { name: "Needs attention, 1 item" });

    fireEvent.click(bell);
    await screen.findByRole("link", { name: "1 more in 1 other organization" });
    fireEvent.click(bell);
    fireEvent.click(bell);
    await screen.findByRole("link", { name: "1 more in 1 other organization" });

    expect(others).toHaveBeenCalledTimes(1);
  });

  it("ignores a slow answer for the organization the person has left", async () => {
    const OTHER = "9f2b1c3d-0000-4000-8000-000000000001";
    let finishFirst: (value: unknown) => void = () => {};
    load
      .mockImplementationOnce(() => new Promise((resolve) => (finishFirst = resolve)))
      .mockResolvedValue(data([item("failed", "second")], { failed: 1 }));
    const { rerender } = render(
      <MemoryRouter initialEntries={[`/organizations/${ORG}/overview`]}>
        <InboxBell />
      </MemoryRouter>
    );
    expect(load).toHaveBeenCalledTimes(1);
    rerender(
      <MemoryRouter initialEntries={[`/organizations/${OTHER}/overview`]} key="other">
        <InboxBell />
      </MemoryRouter>
    );
    await screen.findByRole("button", { name: "Needs attention, 1 item" });

    await waitFor(async () => finishFirst(data([item("failed", "a"), item("failed", "b")], { failed: 2 })));

    expect(screen.getByRole("button", { name: "Needs attention, 1 item" })).toBeInTheDocument();
  });

  it("does not count a long list of organizations, and sends the person to the inbox instead", async () => {
    load.mockResolvedValue(data([], {}));
    organizations.mockResolvedValue(
      Array.from({ length: 30 }, (_, index) => ({ id: `o${index}`, name: `Org ${index}` }))
    );
    renderBell();

    fireEvent.click(await screen.findByRole("button", { name: "Needs attention" }));

    expect(await screen.findByRole("link", { name: "Other organizations" })).toHaveAttribute("href", "/inbox?org=all");
    expect(others).not.toHaveBeenCalled();
  });
});
