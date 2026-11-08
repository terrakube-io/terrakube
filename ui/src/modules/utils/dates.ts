import dayjs, { type Dayjs } from "dayjs";
import relativeTimePlugin from "dayjs/plugin/relativeTime";

dayjs.extend(relativeTimePlugin);

export function relativeTime(iso?: string | null): string | null {
  if (!iso) return null;
  const date = dayjs(iso);
  if (!date.isValid()) return null;
  if (Math.abs(dayjs().diff(date, "second")) < 60) return "just now";
  return date.fromNow();
}

// Absolute dates go through Intl so they follow the browser locale, as before.
const formatLocal = (iso: string, options: Intl.DateTimeFormatOptions): string => {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString(undefined, options);
};

export function formatDateTime(iso?: string | null): string {
  if (!iso) return "";
  return formatLocal(iso, { dateStyle: "medium", timeStyle: "short" });
}

// The exact time with its timezone in the browser's locale ("17 October 2026 at 19:58 BST"), for the tooltip next to a relative time.
export function formatDateTimeWithZone(iso?: string | null): string {
  if (!iso) return "";
  return formatLocal(iso, { dateStyle: "long", timeStyle: "long" });
}

// "November 3rd, 2026": the long ordinal date format used next to token expirations.
export function formatOrdinalDate(date: Dayjs): string {
  const day = date.date();
  const suffix =
    day % 100 >= 11 && day % 100 <= 13
      ? "th"
      : (({ 1: "st", 2: "nd", 3: "rd" } as Record<number, string>)[day % 10] ?? "th");
  return `${date.format("MMMM")} ${day}${suffix}, ${date.year()}`;
}

// "42s", "3m 05s", "1h 02m": elapsed time between two ISO timestamps; null when either is missing or invalid.
export function formatDuration(startIso?: string | null, endIso?: string | null): string | null {
  if (!startIso || !endIso) return null;
  const start = dayjs(startIso);
  const end = dayjs(endIso);
  if (!start.isValid() || !end.isValid() || end.isBefore(start)) return null;
  const seconds = Math.round(end.diff(start, "millisecond") / 1000);
  if (seconds < 60) return `${seconds}s`;
  const pad = (n: number) => String(n).padStart(2, "0");
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${pad(seconds % 60)}s`;
  return `${Math.floor(seconds / 3600)}h ${pad(Math.floor((seconds % 3600) / 60))}m`;
}
