import { render, screen, fireEvent } from "@testing-library/react";
import AnnouncementTray from "../AnnouncementTray";
import { Announcement } from "../announcementTypes";

describe("AnnouncementTray", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  const announcement1: Announcement = {
    id: "ann-1",
    title: "Critical Outage",
    message: "Service disruption",
    severity: "critical",
    scope: "GLOBAL",
    active: true,
    dismissible: true,
    displayOnLogin: false,
  };

  const announcement2: Announcement = {
    id: "ann-2",
    title: "Scheduled Maintenance",
    message: "Window tomorrow",
    severity: "warning",
    scope: "GLOBAL",
    active: true,
    dismissible: true,
    displayOnLogin: false,
  };

  test("returns null when announcements list is empty", () => {
    const { container } = render(<AnnouncementTray announcements={[]} />);
    expect(container.firstChild).toBeNull();
  });

  test("renders single banner without collapsible toggle", () => {
    render(<AnnouncementTray announcements={[announcement1]} />);
    expect(screen.getByText("Critical Outage")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /view/i })).not.toBeInTheDocument();
  });

  test("stacks multiple banners with collapsible toggle", () => {
    render(<AnnouncementTray announcements={[announcement1, announcement2]} />);

    // Top banner is visible
    expect(screen.getByText("Critical Outage")).toBeInTheDocument();

    // Toggle button is present
    const toggleBtn = screen.getByRole("button", {
      name: /view 1 more active announcement/i,
    });
    expect(toggleBtn).toBeInTheDocument();

    // Second banner is not visible initially
    expect(screen.queryByText("Scheduled Maintenance")).not.toBeInTheDocument();

    // Expand
    fireEvent.click(toggleBtn);
    expect(screen.getByText("Scheduled Maintenance")).toBeInTheDocument();
    expect(screen.getByText(/hide additional announcements/i)).toBeInTheDocument();

    // Collapse
    fireEvent.click(screen.getByText(/hide additional announcements/i));
    expect(screen.queryByText("Scheduled Maintenance")).not.toBeInTheDocument();
  });

  test("dismissing a banner removes it from the tray", () => {
    render(<AnnouncementTray announcements={[announcement1]} />);
    expect(screen.getByText("Critical Outage")).toBeInTheDocument();

    const dismissBtn = screen.getByRole("button", { name: /dismiss banner/i });
    fireEvent.click(dismissBtn);

    expect(screen.queryByText("Critical Outage")).not.toBeInTheDocument();
  });
});
