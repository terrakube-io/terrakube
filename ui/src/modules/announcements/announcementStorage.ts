import { Announcement, DismissalRecord, PublicAnnouncement } from "./announcementTypes";

export const STORAGE_KEY = "terrakube_dismissed_announcements";

export function computeAnnouncementHash(announcement: Announcement | PublicAnnouncement): string {
  const content = `${announcement.id}_${announcement.updatedAt ?? ""}_${announcement.title}_${announcement.message}_${announcement.severity}_${announcement.startsAt ?? ""}_${announcement.endsAt ?? ""}`;
  let hash = 0;
  for (let i = 0; i < content.length; i++) {
    hash = (hash << 5) - hash + content.charCodeAt(i);
    hash |= 0;
  }
  return hash.toString();
}

export function getDismissals(): Record<string, string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed: DismissalRecord[] = JSON.parse(raw);
    const map: Record<string, string> = {};
    for (const item of parsed) {
      if (item.announcementId && item.versionHash) {
        map[item.announcementId] = item.versionHash;
      }
    }
    return map;
  } catch {
    return {};
  }
}

export function isAnnouncementDismissed(announcement: Announcement | PublicAnnouncement): boolean {
  if (!announcement.dismissible) {
    return false;
  }
  const dismissals = getDismissals();
  const storedHash = dismissals[announcement.id];
  if (!storedHash) {
    return false;
  }
  return storedHash === computeAnnouncementHash(announcement);
}

export function dismissAnnouncement(announcement: Announcement | PublicAnnouncement): void {
  const current = getDismissals();
  current[announcement.id] = computeAnnouncementHash(announcement);
  const records: DismissalRecord[] = Object.entries(current).map(([id, hash]) => ({
    announcementId: id,
    versionHash: hash,
  }));
  localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
}

export function clearDismissal(announcementId: string): void {
  const current = getDismissals();
  delete current[announcementId];
  const records: DismissalRecord[] = Object.entries(current).map(([id, hash]) => ({
    announcementId: id,
    versionHash: hash,
  }));
  localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
}
