import {
  computeAnnouncementHash,
  isAnnouncementDismissed,
  dismissAnnouncement,
  clearDismissal,
  getDismissals,
  STORAGE_KEY,
  formatSchedule,
  formatFullSchedule,
  isAnnouncementViewed,
  markAnnouncementsViewed,
  VIEWED_STORAGE_KEY,
} from "../announcementStorage";
import { Announcement } from "../announcementTypes";

describe("announcementStorage", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  const sampleAnnouncement: Announcement = {
    id: "ann-1",
    title: "Scheduled Maintenance",
    message: "System upgrade tonight",
    severity: "warning",
    scope: "GLOBAL",
    active: true,
    dismissible: true,
    displayOnLogin: false,
    updatedAt: "2026-11-05T12:00:00Z",
  };

  test("computeAnnouncementHash produces deterministic hash", () => {
    const hash1 = computeAnnouncementHash(sampleAnnouncement);
    const hash2 = computeAnnouncementHash(sampleAnnouncement);
    expect(hash1).toBe(hash2);
    expect(typeof hash1).toBe("string");
  });

  test("hash changes when message or updatedAt changes", () => {
    const hash1 = computeAnnouncementHash(sampleAnnouncement);
    const updatedAnnouncement = {
      ...sampleAnnouncement,
      message: "System upgrade extended by 2 hours",
      updatedAt: "2026-11-05T14:00:00Z",
    };
    const hash2 = computeAnnouncementHash(updatedAnnouncement);
    expect(hash1).not.toBe(hash2);
  });

  test("isAnnouncementDismissed returns false for non-dismissible announcement", () => {
    const nonDismissible: Announcement = {
      ...sampleAnnouncement,
      dismissible: false,
    };
    dismissAnnouncement(nonDismissible);
    expect(isAnnouncementDismissed(nonDismissible)).toBe(false);
  });

  test("dismissAnnouncement marks banner as dismissed", () => {
    expect(isAnnouncementDismissed(sampleAnnouncement)).toBe(false);
    dismissAnnouncement(sampleAnnouncement);
    expect(isAnnouncementDismissed(sampleAnnouncement)).toBe(true);

    const stored = getDismissals();
    expect(stored[sampleAnnouncement.id]).toBe(computeAnnouncementHash(sampleAnnouncement));
  });

  test("re-surfaces dismissed banner when admin updates content (hash invalidation)", () => {
    dismissAnnouncement(sampleAnnouncement);
    expect(isAnnouncementDismissed(sampleAnnouncement)).toBe(true);

    const modifiedAnnouncement: Announcement = {
      ...sampleAnnouncement,
      message: "Updated: maintenance cancelled",
      updatedAt: "2026-11-05T13:00:00Z",
    };

    expect(isAnnouncementDismissed(modifiedAnnouncement)).toBe(false);
  });

  test("clearDismissal removes dismissal from storage", () => {
    dismissAnnouncement(sampleAnnouncement);
    expect(isAnnouncementDismissed(sampleAnnouncement)).toBe(true);

    clearDismissal(sampleAnnouncement.id);
    expect(isAnnouncementDismissed(sampleAnnouncement)).toBe(false);
  });

  test("handles corrupted localStorage gracefully", () => {
    localStorage.setItem(STORAGE_KEY, "invalid-json{{");
    expect(getDismissals()).toEqual({});
    expect(isAnnouncementDismissed(sampleAnnouncement)).toBe(false);
  });

  describe("smart formatSchedule", () => {
    test("formats same-day window compactly with hours and UTC", () => {
      const schedule = formatSchedule("2026-11-05T02:00:00Z", "2026-11-05T04:00:00Z");
      expect(schedule).toBe("Nov 5, 02:00 – 04:00 UTC");
    });

    test("formats same-year multi-day span compactly", () => {
      const schedule = formatSchedule("2026-11-05T00:00:00Z", "2026-11-12T23:59:59Z");
      expect(schedule).toBe("Nov 5 – Nov 12, 2026");
    });

    test("formats multi-year span compactly", () => {
      const schedule = formatSchedule("2026-01-01T00:00:00Z", "2030-12-31T23:59:59Z");
      expect(schedule).toBe("Jan 2026 – Dec 2030");
    });

    test("formats single startsAt", () => {
      const schedule = formatSchedule("2026-11-05T00:00:00Z", null);
      expect(schedule).toBe("From Nov 5, 2026");
    });

    test("formats single endsAt", () => {
      const schedule = formatSchedule(null, "2030-12-31T23:59:59Z");
      expect(schedule).toBe("Until Dec 31, 2030");
    });

    test("formatFullSchedule provides detailed UTC timestamps", () => {
      const full = formatFullSchedule("2026-11-05T02:00:00Z", "2026-11-05T04:00:00Z");
      expect(full).toContain("Thu, 05 Nov 2026 02:00:00 UTC – Thu, 05 Nov 2026 04:00:00 UTC");
    });
  });

  describe("viewed announcements tracking", () => {
    test("tracks viewed announcements and checks viewed state", () => {
      expect(isAnnouncementViewed(sampleAnnouncement)).toBe(false);
      markAnnouncementsViewed([sampleAnnouncement]);
      expect(isAnnouncementViewed(sampleAnnouncement)).toBe(true);
    });

    test("becomes unviewed when announcement hash changes", () => {
      markAnnouncementsViewed([sampleAnnouncement]);
      expect(isAnnouncementViewed(sampleAnnouncement)).toBe(true);

      const modified: Announcement = {
        ...sampleAnnouncement,
        title: "New Title",
      };
      expect(isAnnouncementViewed(modified)).toBe(false);
    });

    test("handles corrupted viewed storage gracefully", () => {
      localStorage.setItem(VIEWED_STORAGE_KEY, "invalid-json{{");
      expect(isAnnouncementViewed(sampleAnnouncement)).toBe(false);
    });
  });
});
