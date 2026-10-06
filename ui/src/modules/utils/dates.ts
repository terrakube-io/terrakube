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
