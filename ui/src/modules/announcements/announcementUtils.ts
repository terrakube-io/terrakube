import type { AnnouncementItem, AnnouncementLifecycleStatus, AnnouncementSeverity } from "./types";

export const SEVERITY_RANK: Record<AnnouncementSeverity, number> = {
  CRITICAL: 0,
  WARNING: 1,
  INFO: 2,
};

const MAX_TIMEOUT_MS = 2_147_483_647;

function parseTimestampMs(value: string | null | undefined): number | null {
  if (!value) {
    return null;
  }
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? null : ms;
}

export function isAnnouncementCurrentlyActive(
  item: Pick<AnnouncementItem, "active" | "startsAt" | "endsAt">,
  nowMs: number = Date.now()
): boolean {
  if (!item.active) {
    return false;
  }
  const startsMs = parseTimestampMs(item.startsAt);
  const endsMs = parseTimestampMs(item.endsAt);
  if (startsMs !== null && startsMs > nowMs) {
    return false;
  }
  if (endsMs !== null && endsMs <= nowMs) {
    return false;
  }
  return true;
}

export function getAnnouncementLifecycleStatus(
  item: Pick<AnnouncementItem, "active" | "startsAt" | "endsAt">,
  nowMs: number = Date.now()
): AnnouncementLifecycleStatus {
  if (!item.active) {
    return "INACTIVE";
  }
  const startsMs = parseTimestampMs(item.startsAt);
  const endsMs = parseTimestampMs(item.endsAt);
  if (startsMs !== null && startsMs > nowMs) {
    return "SCHEDULED";
  }
  if (endsMs !== null && endsMs <= nowMs) {
    return "EXPIRED";
  }
  return "ACTIVE";
}

export function sortAnnouncements<
  T extends Pick<AnnouncementItem, "id" | "severity" | "startsAt"> & { createdDate?: string | null },
>(items: T[]): T[] {
  return [...items].sort((a, b) => {
    const rankDiff = (SEVERITY_RANK[a.severity] ?? 99) - (SEVERITY_RANK[b.severity] ?? 99);
    if (rankDiff !== 0) {
      return rankDiff;
    }
    const aStarts = parseTimestampMs(a.startsAt);
    const bStarts = parseTimestampMs(b.startsAt);
    if (aStarts !== null && bStarts !== null && aStarts !== bStarts) {
      return bStarts - aStarts;
    }
    if (aStarts !== null && bStarts === null) {
      return -1;
    }
    if (aStarts === null && bStarts !== null) {
      return 1;
    }
    const aCreated = parseTimestampMs(a.createdDate ?? null) ?? 0;
    const bCreated = parseTimestampMs(b.createdDate ?? null) ?? 0;
    if (aCreated !== bCreated) {
      return bCreated - aCreated;
    }
    return a.id.localeCompare(b.id);
  });
}

export function getNextScheduleBoundaryMs(
  items: Pick<AnnouncementItem, "active" | "startsAt" | "endsAt">[],
  nowMs: number = Date.now()
): number | null {
  let nearestDelay: number | null = null;
  for (const item of items) {
    if (!item.active) {
      continue;
    }
    for (const ts of [item.startsAt, item.endsAt]) {
      const ms = parseTimestampMs(ts);
      if (ms !== null && ms > nowMs) {
        const delay = ms - nowMs;
        if (nearestDelay === null || delay < nearestDelay) {
          nearestDelay = delay;
        }
      }
    }
  }
  if (nearestDelay === null) {
    return null;
  }
  return Math.min(nearestDelay, MAX_TIMEOUT_MS);
}

function formatUtcClock(ms: number): string {
  const date = new Date(ms);
  const hh = String(date.getUTCHours()).padStart(2, "0");
  const mm = String(date.getUTCMinutes()).padStart(2, "0");
  return `${hh}:${mm} UTC`;
}

export function formatUtcTimestamp(value: string | null | undefined): string {
  const ms = parseTimestampMs(value);
  if (ms === null) {
    return "—";
  }
  const date = new Date(ms);
  const yyyy = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, "0");
  const day = String(date.getUTCDate()).padStart(2, "0");
  return `${yyyy}-${month}-${day} ${formatUtcClock(ms)}`;
}

function formatRelativeSpan(diffMs: number): string {
  const totalMinutes = Math.max(1, Math.round(diffMs / 60_000));
  if (totalMinutes < 60) {
    return `${totalMinutes} minute${totalMinutes === 1 ? "" : "s"}`;
  }
  const totalHours = Math.round(totalMinutes / 60);
  if (totalHours < 24) {
    return `${totalHours} hour${totalHours === 1 ? "" : "s"}`;
  }
  const totalDays = Math.round(totalHours / 24);
  return `${totalDays} day${totalDays === 1 ? "" : "s"}`;
}

export function formatAnnouncementTiming(
  item: Pick<AnnouncementItem, "startsAt" | "endsAt">,
  nowMs: number = Date.now()
): string | null {
  const startsMs = parseTimestampMs(item.startsAt);
  const endsMs = parseTimestampMs(item.endsAt);

  if (startsMs !== null && startsMs > nowMs) {
    const diff = startsMs - nowMs;
    if (diff <= 48 * 3_600_000) {
      return `Starts in ${formatRelativeSpan(diff)} (${formatUtcClock(startsMs)})`;
    }
    return `Starts ${formatUtcTimestamp(item.startsAt)}`;
  }

  if (endsMs !== null && endsMs > nowMs) {
    const diff = endsMs - nowMs;
    if (diff <= 24 * 3_600_000) {
      return `Ends in ${formatRelativeSpan(diff)} (${formatUtcClock(endsMs)})`;
    }
    return `Ends ${formatUtcTimestamp(item.endsAt)}`;
  }

  return null;
}
