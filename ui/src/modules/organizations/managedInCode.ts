import { useEffect, useState } from "react";
// The auxiliary client keeps a failure local: the notice is optional, so one failed read must not replace the page
// with the API error screen, or clear a real outage flag when it succeeds.
import { axiosAuxiliary } from "@/config/axiosConfig";

export type ManagedInCode = {
  /** Where the organization is kept in code. Only ever an http or https address. */
  repository?: string;
  /** True when the settings screens should be read-only, not only carry a notice. */
  readOnly: boolean;
};

const TTL_MS = 60_000;
const cache = new Map<string, { at: number; value: ManagedInCode | null }>();

/** A repository address is shown as a link, so anything but http and https is dropped. */
export function safeRepository(value?: string | null): string | undefined {
  if (!value) return undefined;
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Null when the organization is not kept in code. A failed read counts as not managed, because a notice is never worth
 * a block, but it is not remembered: the next screen asks again instead of showing nothing for a minute.
 */
export async function loadManagedInCode(orgId: string): Promise<ManagedInCode | null> {
  const hit = cache.get(orgId);
  if (hit && Date.now() - hit.at < TTL_MS) return hit.value;
  let value: ManagedInCode | null = null;
  try {
    const response = await axiosAuxiliary.get(`organization/${orgId}`, {
      params: { "fields[organization]": "managedInCodeRepository,managedInCodeMode" },
    });
    const attributes = response.data?.data?.attributes ?? {};
    if (attributes.managedInCodeRepository) {
      value = {
        repository: safeRepository(attributes.managedInCodeRepository),
        readOnly: attributes.managedInCodeMode === "readonly",
      };
    }
  } catch {
    return null;
  }
  cache.set(orgId, { at: Date.now(), value });
  return value;
}

const listeners = new Set<() => void>();

/** Forget what was read, for example after the organization's settings were saved, and tell the banners on screen. */
export function clearManagedInCode(orgId?: string) {
  if (orgId) cache.delete(orgId);
  else cache.clear();
  listeners.forEach((listener) => listener());
}

export function useManagedInCode(orgId?: string | null): ManagedInCode | null {
  const [managed, setManaged] = useState<ManagedInCode | null>(null);
  // Bumped when the settings were saved, so a banner already on screen reads again and does not stay as it was.
  const [version, setVersion] = useState(0);
  useEffect(() => {
    const refresh = () => setVersion((current) => current + 1);
    listeners.add(refresh);
    return () => {
      listeners.delete(refresh);
    };
  }, []);
  // Another organization starts without the last one's banner.
  useEffect(() => setManaged(null), [orgId]);
  useEffect(() => {
    let alive = true;
    if (orgId) {
      loadManagedInCode(orgId).then((value) => {
        if (alive) setManaged(value);
      });
    }
    return () => {
      alive = false;
    };
  }, [orgId, version]);
  return managed;
}
