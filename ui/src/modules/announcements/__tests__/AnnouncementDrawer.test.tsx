import { render, screen, fireEvent } from "@testing-library/react";
import AnnouncementDrawer from "../AnnouncementDrawer";
import { Announcement } from "../announcementTypes";

describe("AnnouncementDrawer", () => {
  const activeNotice: Announcement = {
    id: "notice-1",
    title: "Scheduled Maintenance",
    message: "System upgrade tonight.",
    severity: "warning",
    scope: "GLOBAL",
    active: true,
    dismissible: true,
    displayOnLogin: false,
  };

  const dismissedNotice: Announcement = {
    id: "notice-2",
    title: "Previous Outage",
    message: "Resolved issue.",
    severity: "info",
    scope: "ORGANIZATION",
    active: true,
    dismissible: true,
    displayOnLogin: false,
    organization: { id: "org-1", name: "simple" },
  };

  test("renders active announcements with scope and severity tags", () => {
    render(
      <AnnouncementDrawer
        open={true}
        onClose={jest.fn()}
        activeAnnouncements={[activeNotice]}
        dismissedAnnouncements={[]}
        onRestore={jest.fn()}
        onDismiss={jest.fn()}
      />
    );

    expect(screen.getByText("Announcements & Notices")).toBeInTheDocument();
    expect(screen.getByText("Scheduled Maintenance")).toBeInTheDocument();
    expect(screen.getByText("Platform")).toBeInTheDocument();
    expect(screen.getByText("WARNING")).toBeInTheDocument();
    expect(screen.getByText("Pinned to Banner")).toBeInTheDocument();
    expect(screen.getByText("System upgrade tonight.")).toBeInTheDocument();
  });

  test("calls onDismiss when dismiss button is clicked on an active notice", () => {
    const handleDismiss = jest.fn();
    render(
      <AnnouncementDrawer
        open={true}
        onClose={jest.fn()}
        activeAnnouncements={[activeNotice]}
        dismissedAnnouncements={[]}
        onRestore={jest.fn()}
        onDismiss={handleDismiss}
      />
    );

    const dismissBtn = screen.getByRole("button", { name: /dismiss scheduled maintenance/i });
    fireEvent.click(dismissBtn);
    expect(handleDismiss).toHaveBeenCalledWith(activeNotice);
  });

  test("renders dismissed notices and restores notice when button clicked", () => {
    const handleRestore = jest.fn();
    render(
      <AnnouncementDrawer
        open={true}
        onClose={jest.fn()}
        activeAnnouncements={[]}
        dismissedAnnouncements={[dismissedNotice]}
        onRestore={handleRestore}
        onDismiss={jest.fn()}
      />
    );

    // Switch to Dismissed tab
    const dismissedTab = screen.getByRole("tab", { name: /dismissed \(1\)/i });
    fireEvent.click(dismissedTab);

    expect(screen.getByText("Previous Outage")).toBeInTheDocument();
    expect(screen.getByText("Organization")).toBeInTheDocument();

    const restoreBtn = screen.getByRole("button", { name: /restore previous outage/i });
    fireEvent.click(restoreBtn);
    expect(handleRestore).toHaveBeenCalledWith("notice-2");
  });

  test("shows empty states when no active or dismissed notices exist", () => {
    render(
      <AnnouncementDrawer
        open={true}
        onClose={jest.fn()}
        activeAnnouncements={[]}
        dismissedAnnouncements={[]}
        onRestore={jest.fn()}
        onDismiss={jest.fn()}
      />
    );

    expect(screen.getByText("No active notices")).toBeInTheDocument();

    const dismissedTab = screen.getByRole("tab", { name: /dismissed \(0\)/i });
    fireEvent.click(dismissedTab);
    expect(screen.getByText("No dismissed notices")).toBeInTheDocument();
  });
});
