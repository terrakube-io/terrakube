import { render, screen, waitFor, fireEvent } from "@testing-library/react";
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

  test("allows dismissing public announcement on the login screen", async () => {
    const mockAnnouncements: PublicAnnouncement[] = [
      {
        id: "pub-dismiss-1",
        title: "Dismissible Notice",
        message: "Notice body",
        severity: "warning",
        dismissible: true,
      },
    ];

    jest.spyOn(announcementService, "fetchPublicLoginAnnouncements").mockResolvedValue(mockAnnouncements);

    render(<LoginAnnouncementBanner />);

    await waitFor(() => {
      expect(screen.getByText("Dismissible Notice")).toBeInTheDocument();
    });

    const dismissBtn = screen.getByRole("button", { name: /dismiss banner/i });
    fireEvent.click(dismissBtn);

    await waitFor(() => {
      expect(screen.queryByText("Dismissible Notice")).not.toBeInTheDocument();
    });
  });
});
