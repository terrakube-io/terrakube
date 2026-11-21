import {
  getAnnouncementLifecycleStatus,
  getNextScheduleBoundaryMs,
  isAnnouncementCurrentlyActive,
  sortAnnouncements,
} from "../announcementUtils";
import type { AnnouncementItem } from "../types";

function makeAnnouncement(overrides: Partial<AnnouncementItem> = {}): AnnouncementItem {
  return {
    id: "id-1",
    title: "Notice",
    message: "Body",
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

describe("announcementUtils", () => {
  it("sorts announcements by severity (CRITICAL > WARNING > INFO) then startsAt descending", () => {
    const info = makeAnnouncement({ id: "info-1", severity: "INFO", startsAt: "2026-01-05T00:00:00Z" });
    const warnOlder = makeAnnouncement({ id: "warn-old", severity: "WARNING", startsAt: "2026-01-02T00:00:00Z" });
    const warnNewer = makeAnnouncement({ id: "warn-new", severity: "WARNING", startsAt: "2026-01-04T00:00:00Z" });
    const critical = makeAnnouncement({ id: "crit-1", severity: "CRITICAL", startsAt: null });

    const sorted = sortAnnouncements([info, warnOlder, warnNewer, critical]);
    expect(sorted.map((item) => item.id)).toEqual(["crit-1", "warn-new", "warn-old", "info-1"]);
  });

  it("evaluates activation windows against server timestamp", () => {
    const now = Date.parse("2026-05-01T12:00:00Z");

    expect(isAnnouncementCurrentlyActive(makeAnnouncement({ active: false }), now)).toBe(false);
    expect(
      isAnnouncementCurrentlyActive(
        makeAnnouncement({ startsAt: "2026-05-01T12:30:00Z", endsAt: "2026-05-01T14:00:00Z" }),
        now
      )
    ).toBe(false);
    expect(
      isAnnouncementCurrentlyActive(
        makeAnnouncement({ startsAt: "2026-05-01T11:00:00Z", endsAt: "2026-05-01T12:00:00Z" }),
        now
      )
    ).toBe(false);
    expect(
      isAnnouncementCurrentlyActive(
        makeAnnouncement({ startsAt: "2026-05-01T11:00:00Z", endsAt: "2026-05-01T13:00:00Z" }),
        now
      )
    ).toBe(true);
  });

  it("reports lifecycle statuses accurately", () => {
    const now = Date.parse("2026-05-01T12:00:00Z");

    expect(getAnnouncementLifecycleStatus(makeAnnouncement({ active: false }), now)).toBe("INACTIVE");
    expect(getAnnouncementLifecycleStatus(makeAnnouncement({ startsAt: "2026-05-01T12:05:00Z" }), now)).toBe(
      "SCHEDULED"
    );
    expect(getAnnouncementLifecycleStatus(makeAnnouncement({ endsAt: "2026-05-01T11:59:00Z" }), now)).toBe("EXPIRED");
    expect(
      getAnnouncementLifecycleStatus(
        makeAnnouncement({ startsAt: "2026-05-01T11:00:00Z", endsAt: "2026-05-01T13:00:00Z" }),
        now
      )
    ).toBe("ACTIVE");
  });

  it("finds the nearest future schedule boundary across active announcements", () => {
    const now = Date.parse("2026-05-01T12:00:00Z");
    const items = [
      makeAnnouncement({ active: false, startsAt: "2026-05-01T12:01:00Z" }),
      makeAnnouncement({ active: true, startsAt: "2026-05-01T12:10:00Z", endsAt: "2026-05-01T13:00:00Z" }),
      makeAnnouncement({ active: true, startsAt: "2026-05-01T11:00:00Z", endsAt: "2026-05-01T12:05:00Z" }),
    ];

    expect(getNextScheduleBoundaryMs(items, now)).toBe(5 * 60_000);
  });
});
