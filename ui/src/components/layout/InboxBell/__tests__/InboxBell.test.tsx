import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { InboxBell } from "../InboxBell";
import { emptyCounts, InboxData, InboxItem } from "@/modules/inbox/inboxModel";
import { loadInbox } from "@/modules/inbox/inboxService";
import { confirmApprove } from "@/modules/inbox/confirmApprove";

jest.mock("@/modules/navigation/navigationService", () => ({
  listOrganizationNames: jest.fn().mockResolvedValue([{ id: "org", name: "aws" }]),
}));
jest.mock("@/modules/inbox/inboxService", () => ({ loadInbox: jest.fn() }));
jest.mock("@/modules/inbox/confirmApprove", () => ({ confirmApprove: jest.fn() }));

const load = loadInbox as jest.Mock;

const item = (kind: InboxItem["kind"], name: string): InboxItem => ({
  key: `${kind}:${name}`,
  kind,
  orgId: "org",
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

const renderBell = () =>
  render(
    <MemoryRouter>
      <InboxBell />
    </MemoryRouter>
  );

describe("InboxBell", () => {
  beforeEach(() => jest.clearAllMocks());

  it("shows how many things need attention without being opened", async () => {
    load.mockResolvedValue(data([item("waiting", "ws-1"), item("failed", "ws-2")], { waiting: 1, failed: 1 }));
    renderBell();

    expect(await screen.findByRole("button", { name: "Needs attention, 2 items" })).toBeInTheDocument();
  });

  it("shows no count when nothing needs attention, and says so when opened", async () => {
    load.mockResolvedValue(data([]));
    renderBell();

    fireEvent.click(await screen.findByRole("button", { name: "Needs attention" }));

    expect(await screen.findByText("Nothing needs attention.")).toBeInTheDocument();
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
    expect(screen.getByRole("link", { name: "View all 8" })).toHaveAttribute("href", "/inbox");
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
});
