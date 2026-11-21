import {
  DISMISSED_STORAGE_KEY,
  computeAnnouncementVersionHash,
  dismissAnnouncement,
  getDismissedMap,
  isAnnouncementDismissed,
} from "../dismissalStorage";

describe("dismissalStorage", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  const baseAnnouncement = {
    id: "ann-1",
    title: "Maintenance Window",
    message: "API upgrade tonight",
    updatedDate: "2026-01-01T00:00:00Z",
    dismissible: true,
  };

  it("persists dismissal under terrakube_dismissed_announcements with versionHash", () => {
    expect(isAnnouncementDismissed(baseAnnouncement)).toBe(false);

    dismissAnnouncement(baseAnnouncement);

    const raw = localStorage.getItem(DISMISSED_STORAGE_KEY);
    expect(raw).not.toBeNull();
    const parsed = JSON.parse(raw as string);
    expect(parsed["ann-1"]).toEqual({
      announcementId: "ann-1",
      versionHash: computeAnnouncementVersionHash(baseAnnouncement),
    });
    expect(isAnnouncementDismissed(baseAnnouncement)).toBe(true);
  });

  it("resurfaces automatically when updatedDate, title, or message changes", () => {
    dismissAnnouncement(baseAnnouncement);
    expect(isAnnouncementDismissed(baseAnnouncement)).toBe(true);

    expect(
      isAnnouncementDismissed({
        ...baseAnnouncement,
        updatedDate: "2026-01-01T01:00:00Z",
      })
    ).toBe(false);

    expect(
      isAnnouncementDismissed({
        ...baseAnnouncement,
        title: "Maintenance Window (Extended)",
      })
    ).toBe(false);

    expect(
      isAnnouncementDismissed({
        ...baseAnnouncement,
        message: "API upgrade extended by 2 hours",
      })
    ).toBe(false);
  });

  it("resurfaces immediately when dismissible changes to false", () => {
    dismissAnnouncement(baseAnnouncement);
    expect(isAnnouncementDismissed(baseAnnouncement)).toBe(true);

    expect(
      isAnnouncementDismissed({
        ...baseAnnouncement,
        dismissible: false,
      })
    ).toBe(false);
  });

  it("handles corrupt localStorage JSON safely", () => {
    localStorage.setItem(DISMISSED_STORAGE_KEY, "{not-valid-json");
    expect(getDismissedMap()).toEqual({});
    expect(isAnnouncementDismissed(baseAnnouncement)).toBe(false);
  });
});
