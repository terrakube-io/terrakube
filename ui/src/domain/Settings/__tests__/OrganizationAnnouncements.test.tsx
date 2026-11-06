import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { OrganizationAnnouncements } from "../Announcements/OrganizationAnnouncements";
import * as announcementService from "@/modules/announcements/announcementService";
import axiosInstance from "@/config/axiosConfig";

jest.mock("@/config/axiosConfig");
const mockedAxios = axiosInstance as jest.Mocked<typeof axiosInstance>;

describe("OrganizationAnnouncements", () => {
  const mockAnnouncements = [
    {
      id: "ann-1",
      title: "Platform Maintenance",
      message: "Global downtime scheduled",
      severity: "critical" as const,
      scope: "GLOBAL" as const,
      active: true,
      dismissible: true,
      displayOnLogin: true,
      startsAt: "2026-11-05T00:00:00Z",
      endsAt: "2026-11-05T02:00:00Z",
    },
    {
      id: "ann-2",
      title: "Org Warning",
      message: "Please rotate your keys",
      severity: "warning" as const,
      scope: "ORGANIZATION" as const,
      active: false,
      dismissible: true,
      displayOnLogin: false,
      organization: { id: "org-1", name: "simple" },
    },
  ];

  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(announcementService, "fetchAnnouncements").mockResolvedValue(mockAnnouncements);
  });

  const renderComponent = (orgId = "org-1", managePermission = true) => {
    return render(
      <MemoryRouter initialEntries={[`/organizations/${orgId}/settings/announcements`]}>
        <Routes>
          <Route
            path="/organizations/:orgid/settings/announcements"
            element={<OrganizationAnnouncements managePermission={managePermission} />}
          />
        </Routes>
      </MemoryRouter>
    );
  };

  test("loads and renders announcements list", async () => {
    renderComponent();

    await waitFor(() => {
      expect(screen.getByText("Platform Maintenance")).toBeInTheDocument();
      expect(screen.getByText("Org Warning")).toBeInTheDocument();
    });

    expect(screen.getByText("Platform")).toBeInTheDocument();
    expect(screen.getByText("Organization")).toBeInTheDocument();
    expect(screen.getByText("CRITICAL")).toBeInTheDocument();
    expect(screen.getByText("WARNING")).toBeInTheDocument();
  });

  test("opens create modal when New Announcement button is clicked", async () => {
    renderComponent();

    await waitFor(() => {
      expect(screen.getByText("Platform Maintenance")).toBeInTheDocument();
    });

    const newBtn = screen.getByRole("button", { name: /new announcement/i });
    fireEvent.click(newBtn);

    const dialog = screen.getByRole("dialog");
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText("New Announcement")).toBeInTheDocument();
  });

  test("toggles active switch and calls patch API", async () => {
    mockedAxios.patch.mockResolvedValueOnce({ data: {} });
    renderComponent();

    await waitFor(() => {
      expect(screen.getByText("Platform Maintenance")).toBeInTheDocument();
    });

    const switches = screen.getAllByRole("switch");
    // Toggle the first one
    fireEvent.click(switches[0]);

    await waitFor(() => {
      expect(mockedAxios.patch).toHaveBeenCalledWith(
        "/announcement/ann-1",
        expect.objectContaining({
          data: expect.objectContaining({
            type: "announcement",
            id: "ann-1",
            attributes: { active: false },
          }),
        }),
        expect.anything()
      );
    });
  });

  test("deletes announcement on confirm", async () => {
    mockedAxios.delete.mockResolvedValueOnce({ data: {} });
    renderComponent();

    await waitFor(() => {
      expect(screen.getByText("Platform Maintenance")).toBeInTheDocument();
    });

    const deleteBtn = screen.getByRole("button", { name: /delete platform maintenance/i });
    fireEvent.click(deleteBtn);

    // Popconfirm appears
    const confirmBtn = await screen.findByRole("button", { name: "Delete" });
    fireEvent.click(confirmBtn);

    await waitFor(() => {
      expect(mockedAxios.delete).toHaveBeenCalledWith("/announcement/ann-1", expect.anything());
    });
  });
});
