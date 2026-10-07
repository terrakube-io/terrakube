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

// "November 3rd, 2026": the long ordinal date format used next to token expirations.
export function formatOrdinalDate(date: DateTime): string {
  const day = date.day;
  const suffix =
    day % 100 >= 11 && day % 100 <= 13
      ? "th"
      : (({ 1: "st", 2: "nd", 3: "rd" } as Record<number, string>)[day % 10] ?? "th");
  return `${date.toFormat("MMMM")} ${day}${suffix}, ${date.year}`;
}

// "42s", "3m 05s", "1h 02m": elapsed time between two ISO timestamps; null when either is missing or invalid.
export function formatDuration(startIso?: string | null, endIso?: string | null): string | null {
  if (!startIso || !endIso) return null;
  const start = DateTime.fromISO(startIso);
  const end = DateTime.fromISO(endIso);
  if (!start.isValid || !end.isValid || end < start) return null;
  const seconds = Math.round(end.diff(start, "seconds").seconds);
  if (seconds < 60) return `${seconds}s`;
  const pad = (n: number) => String(n).padStart(2, "0");
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${pad(seconds % 60)}s`;
  return `${Math.floor(seconds / 3600)}h ${pad(Math.floor((seconds % 3600) / 60))}m`;
}
