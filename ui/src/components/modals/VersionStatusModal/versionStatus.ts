import type { MenuProps } from "antd";

export type VersionStatus = {
  deprecated?: boolean;
  removed?: boolean;
  deprecationMessage?: string | null;
};

export type VersionKind = "module" | "provider";

export function versionStatusSuffix(status: VersionStatus): string {
  if (status.removed) return " (removed)";
  if (status.deprecated) return " (deprecated)";
  return "";
}

// The version to point users at: the newest active one, else the newest one that is still served.
export function recommendedVersion<T extends VersionStatus>(versionsNewestFirst: T[]): T | undefined {
  return versionsNewestFirst.find((v) => !v.removed && !v.deprecated) ?? versionsNewestFirst.find((v) => !v.removed);
}

export function versionStatusChangeMessage(version: string, status: VersionStatus, kind: VersionKind): string {
  if (status.removed) {
    return kind === "module"
      ? `Version ${version} removed. The registry stops serving it within a few minutes.`
      : `Version ${version} removed. The registry no longer serves it.`;
  }
  if (status.deprecated) return `Version ${version} marked as deprecated`;
  return `Version ${version} is active again`;
}

// Dropdown entries with removed versions grouped at the end, behind a divider.
export function versionMenuItems<T extends VersionStatus>(
  versionsNewestFirst: T[],
  getVersion: (version: T) => string
): MenuProps["items"] {
  const toItem = (v: T) => ({ key: getVersion(v), label: getVersion(v) + versionStatusSuffix(v) });
  const served = versionsNewestFirst.filter((v) => !v.removed).map(toItem);
  const removed = versionsNewestFirst.filter((v) => v.removed).map(toItem);
  return removed.length > 0 ? [...served, { type: "divider" }, ...removed] : served;
}
