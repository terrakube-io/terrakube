import { useEffect, useState } from "react";
import axiosInstance from "@/config/axiosConfig";

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

/** Null when the organization is not kept in code. A failed read counts as not managed: a notice is never worth a block. */
export async function loadManagedInCode(orgId: string): Promise<ManagedInCode | null> {
  const hit = cache.get(orgId);
  if (hit && Date.now() - hit.at < TTL_MS) return hit.value;
  let value: ManagedInCode | null = null;
  try {
    const response = await axiosInstance.get(`organization/${orgId}`, {
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
    value = null;
  }
  cache.set(orgId, { at: Date.now(), value });
  return value;
}

/** Forget what was read, for example after the organization's settings were saved. */
export function clearManagedInCode(orgId?: string) {
  if (orgId) cache.delete(orgId);
  else cache.clear();
}

export function useManagedInCode(orgId?: string | null): ManagedInCode | null {
  const [managed, setManaged] = useState<ManagedInCode | null>(null);
  useEffect(() => {
    let alive = true;
    setManaged(null);
    if (orgId) {
      loadManagedInCode(orgId).then((value) => {
        if (alive) setManaged(value);
      });
    }
    return () => {
      alive = false;
    };
  }, [orgId]);
  return managed;
}
