import { filterAndSortAnnouncements, fetchAnnouncements } from "../announcementService";
import { Announcement } from "../announcementTypes";
import { axiosGraphQL } from "@/config/axiosConfig";

jest.unmock("@/modules/announcements/announcementService");

jest.mock("@/config/axiosConfig", () => ({
  axiosGraphQL: {
    post: jest.fn(),
  },
}));

describe("announcementService - filterAndSortAnnouncements", () => {
  const baseTime = new Date("2026-11-05T12:00:00Z");

  const globalInfo: Announcement = {
    id: "1",
    title: "Global Info",
    message: "Info message",
    severity: "info",
    scope: "GLOBAL",
    active: true,
    dismissible: true,
    displayOnLogin: true,
  };

  const globalCritical: Announcement = {
    id: "2",
    title: "Global Critical",
    message: "Outage ongoing",
    severity: "critical",
    scope: "GLOBAL",
    active: true,
    dismissible: false,
    displayOnLogin: false,
    startsAt: "2026-11-05T10:00:00Z",
  };

  const orgWarning: Announcement = {
    id: "3",
    title: "Org Warning",
    message: "Org specific warning",
    severity: "warning",
    scope: "ORGANIZATION",
    organization: { id: "org-123", name: "FinTech" },
    active: true,
    dismissible: true,
    displayOnLogin: false,
    startsAt: "2026-11-05T11:00:00Z",
  };

  const inactiveAnnouncement: Announcement = {
    id: "4",
    title: "Inactive Announcement",
    message: "Should not show",
    severity: "info",
    scope: "GLOBAL",
    active: false,
    dismissible: true,
    displayOnLogin: false,
  };

  const futureScheduled: Announcement = {
    id: "5",
    title: "Future Window",
    message: "Starts tomorrow",
    severity: "critical",
    scope: "GLOBAL",
    active: true,
    dismissible: true,
    displayOnLogin: false,
    startsAt: "2026-11-06T00:00:00Z",
  };

  const expiredScheduled: Announcement = {
    id: "6",
    title: "Past Window",
    message: "Ended yesterday",
    severity: "warning",
    scope: "GLOBAL",
    active: true,
    dismissible: true,
    displayOnLogin: false,
    endsAt: "2026-11-04T00:00:00Z",
  };

  test("filters out inactive and out-of-window announcements", () => {
    const list = [globalInfo, inactiveAnnouncement, futureScheduled, expiredScheduled];
    const filtered = filterAndSortAnnouncements(list, undefined, baseTime);
    expect(filtered).toHaveLength(1);
    expect(filtered[0]?.id).toBe("1");
  });

  test("filters organization announcements strictly by orgId", () => {
    const list = [globalInfo, orgWarning];

    // User in another organization (org-999) sees only global
    const otherOrgResult = filterAndSortAnnouncements(list, "org-999", baseTime);
    expect(otherOrgResult.map((a) => a.id)).toEqual(["1"]);

    // User without org context (e.g. root) sees only global
    const noOrgResult = filterAndSortAnnouncements(list, undefined, baseTime);
    expect(noOrgResult.map((a) => a.id)).toEqual(["1"]);

    // User in matching org sees both
    const matchingOrgResult = filterAndSortAnnouncements(list, "org-123", baseTime);
    expect(matchingOrgResult.map((a) => a.id)).toEqual(["3", "1"]);
  });

  test("sorts announcements by severity priority (critical > warning > info)", () => {
    const list = [globalInfo, globalCritical, orgWarning];
    const result = filterAndSortAnnouncements(list, "org-123", baseTime);

    expect(result.map((a) => a.severity)).toEqual(["critical", "warning", "info"]);
    expect(result.map((a) => a.id)).toEqual(["2", "3", "1"]);
  });

  test("fetchAnnouncements queries GraphQL with organization connection and parses edges node", async () => {
    (axiosGraphQL.post as jest.Mock).mockResolvedValue({
      data: {
        data: {
          announcement: {
            edges: [
              {
                node: {
                  id: "ann-1",
                  title: "Org Banner",
                  message: "Body",
                  severity: "warning",
                  scope: "ORGANIZATION",
                  active: true,
                  dismissible: true,
                  displayOnLogin: false,
                  organization: {
                    edges: [
                      {
                        node: {
                          id: "org-simple",
                          name: "simple",
                        },
                      },
                    ],
                  },
                },
              },
            ],
          },
        },
      },
    });

    const result = await fetchAnnouncements();
    expect(result).toHaveLength(1);
    expect(result[0]?.id).toBe("ann-1");
    expect(result[0]?.organization?.id).toBe("org-simple");
    expect(result[0]?.organization?.name).toBe("simple");
  });
});
