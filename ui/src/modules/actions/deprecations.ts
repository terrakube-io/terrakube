// Scope names that user-defined actions can still use but should move away from.
// `DateTime` (luxon) is replaced by `dayjs`; it will be removed in a later release.
const DEPRECATED_SCOPE: Record<string, string> = {
  DateTime: "`DateTime` (luxon) in custom actions is deprecated and will be removed; use `dayjs` instead.",
};

const reported = new Set<string>();

/** Logs each deprecation once per page load, for every action whose source uses a deprecated name. */
export function reportDeprecatedScope(actionSource: string, actionKey: string): string[] {
  const used = Object.keys(DEPRECATED_SCOPE).filter((name) => new RegExp(`\\b${name}\\b`).test(actionSource));
  for (const name of used) {
    const key = `${actionKey}:${name}`;
    if (reported.has(key)) continue;
    reported.add(key);
    console.error(`[actions] ${DEPRECATED_SCOPE[name]}`);
  }
  return used;
}
