import { render, screen, waitFor } from "@testing-library/react";
import * as announcementService from "../announcementService";
import { LoginAnnouncements } from "../components/LoginAnnouncements";

describe("LoginAnnouncements", () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("renders active public login announcements sorted by severity", async () => {
    jest.spyOn(announcementService, "fetchPublicLoginAnnouncements").mockResolvedValue({
      serverTimeMs: Date.parse("2026-05-01T12:00:00Z"),
      announcements: [
        {
          id: "pub-info",
          title: "Release 2.35",
          message: "Upgrade complete",
          severity: "INFO",
          startsAt: null,
          endsAt: null,
          updatedDate: "2026-05-01T10:00:00Z",
        },
        {
          id: "pub-crit",
          title: "SSO Maintenance",
          message: "Intermittent login delays",
          severity: "CRITICAL",
          startsAt: null,
          endsAt: "2026-05-01T13:00:00Z",
          updatedDate: "2026-05-01T11:00:00Z",
        },
      ],
    });

    render(<LoginAnnouncements />);

    expect(await screen.findByText("SSO Maintenance")).toBeInTheDocument();
    expect(screen.getByText("Release 2.35")).toBeInTheDocument();
    const titles = screen.getAllByText(/SSO Maintenance|Release 2.35/).map((el) => el.textContent);
    expect(titles).toEqual(["SSO Maintenance", "Release 2.35"]);
  });

  it("degrades silently when public announcements endpoint fails", async () => {
    jest.spyOn(announcementService, "fetchPublicLoginAnnouncements").mockRejectedValue(new Error("Network error"));

    const { container } = render(<LoginAnnouncements />);

    await waitFor(() => expect(announcementService.fetchPublicLoginAnnouncements).toHaveBeenCalledTimes(1));
    expect(container).toBeEmptyDOMElement();
  });
});
