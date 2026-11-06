import {
  computeAnnouncementHash,
  isAnnouncementDismissed,
  dismissAnnouncement,
  clearDismissal,
  getDismissals,
  STORAGE_KEY,
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
});
