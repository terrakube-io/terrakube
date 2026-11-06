import { render, screen, fireEvent } from "@testing-library/react";
import AnnouncementBanner from "../AnnouncementBanner";
import { Announcement } from "../announcementTypes";

describe("AnnouncementBanner", () => {
  const baseAnnouncement: Announcement = {
    id: "ann-1",
    title: "Database Migration",
    message: "Upgrade in progress with [Documentation](https://docs.terrakube.io).",
    severity: "warning",
    scope: "GLOBAL",
    active: true,
    dismissible: true,
    displayOnLogin: false,
    startsAt: "2026-11-05T02:00:00Z",
    endsAt: "2026-11-05T04:00:00Z",
  };

  test("renders title, schedule and markdown link", () => {
    render(<AnnouncementBanner announcement={baseAnnouncement} />);

    expect(screen.getByText("Database Migration")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Documentation" })).toHaveAttribute("href", "https://docs.terrakube.io");
    expect(screen.getByText(/02:00:00 UTC –/i)).toBeInTheDocument();
  });

  test("renders dismiss button and triggers onDismiss when clicked", () => {
    const handleDismiss = jest.fn();
    render(<AnnouncementBanner announcement={baseAnnouncement} onDismiss={handleDismiss} />);

    const dismissBtn = screen.getByRole("button", { name: /dismiss banner/i });
    expect(dismissBtn).toBeInTheDocument();

    fireEvent.click(dismissBtn);
    expect(handleDismiss).toHaveBeenCalledTimes(1);
    expect(handleDismiss).toHaveBeenCalledWith(baseAnnouncement);
  });

  test("does not render dismiss button when dismissible is false", () => {
    const nonDismissible: Announcement = {
      ...baseAnnouncement,
      dismissible: false,
    };
    render(<AnnouncementBanner announcement={nonDismissible} onDismiss={jest.fn()} />);

    expect(screen.queryByRole("button", { name: /dismiss banner/i })).not.toBeInTheDocument();
  });

  test("sanitizes dangerous markdown content to prevent XSS", () => {
    const maliciousAnnouncement: Announcement = {
      ...baseAnnouncement,
      message: 'Notice with <script>alert("xss")</script> and [bad link](javascript:alert(1))',
    };
    const { container } = render(<AnnouncementBanner announcement={maliciousAnnouncement} />);

    expect(container.querySelector("script")).toBeNull();
    // rehype-sanitize strips javascript: schemes from links
    const link = container.querySelector("a");
    expect(link).toBeNull();
  });
});
