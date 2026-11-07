import { render, screen, fireEvent } from "@testing-library/react";
import AnnouncementBell from "../AnnouncementBell";
import * as useAnnouncementsModule from "../useAnnouncements";

describe("AnnouncementBell", () => {
  const mockUseAnnouncements = jest.spyOn(useAnnouncementsModule, "useAnnouncements");
  const mockMarkViewed = jest.fn();

  beforeEach(() => {
    mockMarkViewed.mockClear();
    mockUseAnnouncements.mockReturnValue({
      announcements: [
        {
          id: "ann-1",
          title: "System Update",
          message: "Restart pending",
          severity: "info",
          scope: "GLOBAL",
          active: true,
          dismissible: true,
          displayOnLogin: false,
        },
      ],
      bannerAnnouncements: [],
      dismissedAnnouncements: [],
      allAnnouncements: [],
      unreadCount: 1,
      loading: false,
      refresh: jest.fn(),
      dismissAnnouncement: jest.fn(),
      restoreAnnouncement: jest.fn(),
      markViewed: mockMarkViewed,
    });
  });

  test("renders bell button with unread count badge, marks viewed and opens drawer on click", () => {
    render(<AnnouncementBell organizationId="org-1" />);

    const button = screen.getByTestId("announcement-bell-button");
    expect(button).toBeInTheDocument();
    expect(screen.getByText("1")).toBeInTheDocument();

    fireEvent.click(button);
    expect(mockMarkViewed).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Announcements & Notices")).toBeInTheDocument();
    expect(screen.getByText("System Update")).toBeInTheDocument();
  });
});
