import { fireEvent, render, screen } from "@testing-library/react";
import { AnnouncementContext, type AnnouncementContextValue } from "../AnnouncementStore";
import { AnnouncementBanner } from "../components/AnnouncementBanner";
import type { AnnouncementItem } from "../types";

function makeAnnouncement(overrides: Partial<AnnouncementItem> = {}): AnnouncementItem {
  return {
    id: "ann-1",
    title: "Scheduled Upgrade",
    message: "See [status](https://status.terrakube.io).",
    severity: "INFO",
    scope: "GLOBAL",
    scheduleType: "IMMEDIATE",
    organizationId: null,
    startsAt: null,
    endsAt: null,
    active: true,
    dismissible: true,
    displayOnLogin: false,
    createdDate: "2026-01-01T00:00:00Z",
    updatedDate: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

function renderBanner(contextOverrides: Partial<AnnouncementContextValue>) {
  const value: AnnouncementContextValue = {
    visibleAnnouncements: [],
    serverNowMs: Date.parse("2026-05-01T12:00:00Z"),
    loading: false,
    dismiss: jest.fn(),
    refreshAnnouncements: jest.fn().mockResolvedValue(undefined),
    ...contextOverrides,
  };
  return {
    value,
    ...render(
      <AnnouncementContext.Provider value={value}>
        <AnnouncementBanner />
      </AnnouncementContext.Provider>
    ),
  };
}

describe("AnnouncementBanner", () => {
  it("renders nothing when no active banners exist", () => {
    const { container } = renderBanner({ visibleAnnouncements: [] });
    expect(container).toBeEmptyDOMElement();
  });

  it("renders sanitized markdown and strips script tags and javascript: URIs", () => {
    const xssAnnouncement = makeAnnouncement({
      id: "ann-xss",
      title: "Security Check",
      message:
        'Safe [docs](https://docs.terrakube.io) and [bad](javascript:alert(1)) <script>window.__xss=true</script><img src="x" onerror="alert(1)" />',
      severity: "WARNING",
      dismissible: true,
    });

    const { container } = renderBanner({ visibleAnnouncements: [xssAnnouncement] });

    const safeLink = screen.getByRole("link", { name: "docs" });
    expect(safeLink).toHaveAttribute("href", "https://docs.terrakube.io");
    expect(safeLink).toHaveAttribute("rel", "noopener noreferrer");

    expect(container.querySelector("script")).toBeNull();
    expect(container.querySelector("img")).toBeNull();
    const badAnchor = container.querySelector('a[href^="javascript:"]');
    expect(badAnchor).toBeNull();
  });

  it("shows dismiss button only when dismissible is true and calls dismiss on click", () => {
    const nonDismissible = makeAnnouncement({ id: "ann-locked", dismissible: false });
    const { rerender } = renderBanner({ visibleAnnouncements: [nonDismissible] });

    expect(screen.queryByRole("button", { name: /dismiss announcement/i })).not.toBeInTheDocument();

    const dismissible = makeAnnouncement({ id: "ann-open", dismissible: true, title: "Optional Tip" });
    const dismissMock = jest.fn();
    rerender(
      <AnnouncementContext.Provider
        value={{
          visibleAnnouncements: [dismissible],
          serverNowMs: Date.now(),
          loading: false,
          dismiss: dismissMock,
          refreshAnnouncements: jest.fn().mockResolvedValue(undefined),
        }}
      >
        <AnnouncementBanner />
      </AnnouncementContext.Provider>
    );

    fireEvent.click(screen.getByRole("button", { name: /dismiss announcement: optional tip/i }));
    expect(dismissMock).toHaveBeenCalledWith(dismissible);
  });

  it("collapses secondary banners into a toggleable tray when multiple banners are active", () => {
    const critical = makeAnnouncement({ id: "crit", title: "Critical Outage", severity: "CRITICAL" });
    const info = makeAnnouncement({ id: "info", title: "FYI Update", severity: "INFO" });

    renderBanner({ visibleAnnouncements: [critical, info] });

    expect(screen.getByText("Critical Outage")).toBeInTheDocument();
    expect(screen.queryByText("FYI Update")).not.toBeInTheDocument();

    const toggle = screen.getByRole("button", { name: /view 1 more active announcement/i });
    expect(toggle).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("FYI Update")).toBeInTheDocument();
  });
});
