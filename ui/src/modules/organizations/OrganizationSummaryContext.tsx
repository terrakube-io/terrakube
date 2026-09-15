import { type ReactNode, useCallback, useEffect, useRef, useState } from "react";
import { FlatOrganization } from "@/domain/types";
import organizationService from "./organizationService";
import { OrganizationSummaryContext } from "./OrganizationSummaryStore";

export function OrganizationSummaryProvider({ children }: { children: ReactNode }) {
  const [organizations, setOrganizations] = useState<FlatOrganization[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error>();
  const inFlight = useRef<Promise<void> | null>(null);

  const refresh = useCallback(async () => {
    if (inFlight.current) {
      return inFlight.current;
    }

    setLoading(true);
    setError(undefined);
    const request = organizationService
      .listOrganizationSummaries()
      .then((nextOrganizations) => {
        setOrganizations(nextOrganizations);
      })
      .catch((requestError: unknown) => {
        setError(requestError instanceof Error ? requestError : new Error("Failed to load organizations"));
      })
      .finally(() => {
        inFlight.current = null;
        setLoading(false);
      });

    inFlight.current = request;
    return request;
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const upsertOrganization = useCallback((organization: FlatOrganization) => {
    setOrganizations((current) => {
      const existingIndex = current.findIndex((item) => item.id === organization.id);
      if (existingIndex === -1) {
        return [...current, organization].sort((left, right) => left.name.localeCompare(right.name));
      }
      return current.map((item) => (item.id === organization.id ? { ...item, ...organization } : item));
    });
  }, []);

  const updateOrganization = useCallback((id: string, changes: Partial<FlatOrganization>) => {
    setOrganizations((current) => current.map((item) => (item.id === id ? { ...item, ...changes } : item)));
  }, []);

  const removeOrganization = useCallback((id: string) => {
    setOrganizations((current) => current.filter((item) => item.id !== id));
  }, []);

  return (
    <OrganizationSummaryContext.Provider
      value={{ organizations, loading, error, refresh, upsertOrganization, updateOrganization, removeOrganization }}
    >
      {children}
    </OrganizationSummaryContext.Provider>
  );
}
