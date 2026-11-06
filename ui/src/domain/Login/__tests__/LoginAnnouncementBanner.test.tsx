import { render, screen, waitFor } from "@testing-library/react";
import LoginAnnouncementBanner from "../LoginAnnouncementBanner";
import * as announcementService from "@/modules/announcements/announcementService";
import { PublicAnnouncement } from "@/modules/announcements/announcementTypes";

jest.mock("@/modules/announcements/announcementService");

describe("LoginAnnouncementBanner", () => {
  beforeEach(() => {
    localStorage.clear();
    jest.clearAllMocks();
  });

  test("renders nothing when no announcements are returned", async () => {
    jest.spyOn(announcementService, "fetchPublicLoginAnnouncements").mockResolvedValue([]);

    const { container } = render(<LoginAnnouncementBanner />);

    await waitFor(() => {
      expect(announcementService.fetchPublicLoginAnnouncements).toHaveBeenCalledTimes(1);
    });

    expect(container.firstChild).toBeNull();
  });

  test("renders public announcements when available", async () => {
    const mockAnnouncements: PublicAnnouncement[] = [
      {
        id: "pub-1",
        title: "Welcome to Terrakube",
        message: "Please sign in using SSO",
        severity: "info",
        dismissible: true,
      },
    ];

    jest.spyOn(announcementService, "fetchPublicLoginAnnouncements").mockResolvedValue(mockAnnouncements);

    render(<LoginAnnouncementBanner />);

    await waitFor(() => {
      expect(screen.getByText("Welcome to Terrakube")).toBeInTheDocument();
      expect(screen.getByText("Please sign in using SSO")).toBeInTheDocument();
    });
  });
});
