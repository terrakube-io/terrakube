import type { AnnouncementItem } from "./types";

export const DISMISSED_STORAGE_KEY = "terrakube_dismissed_announcements";

export interface DismissedAnnouncementEntry {
  announcementId: string;
  versionHash: string;
}

export type DismissedAnnouncementMap = Record<string, DismissedAnnouncementEntry>;

/**
 * Computes a deterministic FNV-1a + length fingerprint over updatedDate, title, and message
 * so any admin edit automatically invalidates client-side dismissals.
 */
export function computeAnnouncementVersionHash(
  item: Pick<AnnouncementItem, "updatedDate" | "title" | "message">
): string {
  const input = `${item.updatedDate ?? ""}\0${item.title}\0${item.message}`;
  let hash = 0x811c9dc5;
  for (let i = 0; i < input.length; i += 1) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return `${(hash >>> 0).toString(16).padStart(8, "0")}-${input.length}`;
}

export function getDismissedMap(): DismissedAnnouncementMap {
  if (typeof window === "undefined" || !window.localStorage) {
    return {};
  }
  try {
    const raw = window.localStorage.getItem(DISMISSED_STORAGE_KEY);
    if (!raw) {
      return {};
    }
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return {};
    }
    const result: DismissedAnnouncementMap = {};
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      if (
        value &&
        typeof value === "object" &&
        typeof (value as DismissedAnnouncementEntry).announcementId === "string" &&
        typeof (value as DismissedAnnouncementEntry).versionHash === "string"
      ) {
        result[key] = value as DismissedAnnouncementEntry;
      }
    }
    return result;
  } catch {
    return {};
  }
}

export function isAnnouncementDismissed(
  item: Pick<AnnouncementItem, "id" | "dismissible" | "updatedDate" | "title" | "message">,
  dismissedMap: DismissedAnnouncementMap = getDismissedMap()
): boolean {
  if (!item.dismissible) {
    return false;
  }
  const entry = dismissedMap[item.id];
  if (!entry) {
    return false;
  }
  return entry.versionHash === computeAnnouncementVersionHash(item);
}

export function dismissAnnouncement(
  item: Pick<AnnouncementItem, "id" | "dismissible" | "updatedDate" | "title" | "message">
): DismissedAnnouncementMap {
  if (!item.dismissible) {
    return getDismissedMap();
  }
  const current = getDismissedMap();
  const next: DismissedAnnouncementMap = {
    ...current,
    [item.id]: {
      announcementId: item.id,
      versionHash: computeAnnouncementVersionHash(item),
    },
  };
  if (typeof window !== "undefined" && window.localStorage) {
    try {
      window.localStorage.setItem(DISMISSED_STORAGE_KEY, JSON.stringify(next));
    } catch {
      // Ignore storage quota/security errors
    }
  }
  return next;
}
