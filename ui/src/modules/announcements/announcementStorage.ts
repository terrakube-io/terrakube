import { Announcement, DismissalRecord, PublicAnnouncement } from "./announcementTypes";

export const STORAGE_KEY = "terrakube_dismissed_announcements";
export const VIEWED_STORAGE_KEY = "terrakube_viewed_announcements";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

function pad2(n: number) {
  return n < 10 ? `0${n}` : `${n}`;
}

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

export function getViewedAnnouncements(): Record<string, string> {
  try {
    const raw = localStorage.getItem(VIEWED_STORAGE_KEY);
    if (!raw) return {};
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

export function isAnnouncementViewed(announcement: Announcement | PublicAnnouncement): boolean {
  const viewed = getViewedAnnouncements();
  return viewed[announcement.id] === computeAnnouncementHash(announcement);
}

export function markAnnouncementsViewed(announcements: (Announcement | PublicAnnouncement)[]): void {
  const current = getViewedAnnouncements();
  for (const a of announcements) {
    current[a.id] = computeAnnouncementHash(a);
  }
  localStorage.setItem(VIEWED_STORAGE_KEY, JSON.stringify(current));
}

export function formatFullSchedule(startsAt?: string | null, endsAt?: string | null): string | null {
  if (!startsAt && !endsAt) return null;
  const formatDate = (iso: string) => {
    try {
      const d = new Date(iso);
      return d.toUTCString().replace("GMT", "UTC");
    } catch {
      return iso;
    }
  };

  if (startsAt && endsAt) {
    return `${formatDate(startsAt)} – ${formatDate(endsAt)}`;
  }
  if (startsAt) {
    return `From ${formatDate(startsAt)}`;
  }
  return `Until ${formatDate(endsAt!)}`;
}

export function formatSchedule(startsAt?: string | null, endsAt?: string | null): string | null {
  if (!startsAt && !endsAt) return null;
  const dStart = startsAt ? new Date(startsAt) : null;
  const dEnd = endsAt ? new Date(endsAt) : null;

  if (dStart && isNaN(dStart.getTime())) return startsAt ?? null;
  if (dEnd && isNaN(dEnd.getTime())) return endsAt ?? null;

  if (dStart && dEnd) {
    const sYear = dStart.getUTCFullYear();
    const eYear = dEnd.getUTCFullYear();
    const sMonth = MONTHS[dStart.getUTCMonth()];
    const eMonth = MONTHS[dEnd.getUTCMonth()];
    const sDay = dStart.getUTCDate();
    const eDay = dEnd.getUTCDate();
    const sTime = `${pad2(dStart.getUTCHours())}:${pad2(dStart.getUTCMinutes())}`;
    const eTime = `${pad2(dEnd.getUTCHours())}:${pad2(dEnd.getUTCMinutes())}`;

    // Same day window (e.g. 2-hour maintenance):
    if (sYear === eYear && dStart.getUTCMonth() === dEnd.getUTCMonth() && sDay === eDay) {
      return `${sMonth} ${sDay}, ${sTime} – ${eTime} UTC`;
    }
    // Same year:
    if (sYear === eYear) {
      return `${sMonth} ${sDay} – ${eMonth} ${eDay}, ${sYear}`;
    }
    // Multi-year window (e.g. Jan 2026 – Dec 2030):
    return `${sMonth} ${sYear} – ${eMonth} ${eYear}`;
  }

  if (dStart) {
    const sYear = dStart.getUTCFullYear();
    const sMonth = MONTHS[dStart.getUTCMonth()];
    const sDay = dStart.getUTCDate();
    return `From ${sMonth} ${sDay}, ${sYear}`;
  }

  if (dEnd) {
    const eYear = dEnd.getUTCFullYear();
    const eMonth = MONTHS[dEnd.getUTCMonth()];
    const eDay = dEnd.getUTCDate();
    return `Until ${eMonth} ${eDay}, ${eYear}`;
  }

  return null;
}
