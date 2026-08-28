import { DateTime } from "luxon";

export function relativeTime(iso?: string | null): string | null {
  if (!iso) return null;
  const date = DateTime.fromISO(iso);
  if (!date.isValid) return null;
  if (Math.abs(date.diffNow("seconds").seconds) < 60) return "just now";
  return date.toRelative();
}

export function formatDateTime(iso?: string | null): string {
  if (!iso) return "";
  const date = DateTime.fromISO(iso);
  return date.isValid ? date.toLocaleString(DateTime.DATETIME_MED) : iso;
}
