import { createContext } from "react";
import { FlatOrganization } from "@/domain/types";

export type OrganizationSummaryContextValue = {
  organizations: FlatOrganization[];
  loading: boolean;
  error?: Error;
  refresh: () => Promise<void>;
  upsertOrganization: (organization: FlatOrganization) => void;
  updateOrganization: (id: string, changes: Partial<FlatOrganization>) => void;
  removeOrganization: (id: string) => void;
};

export const OrganizationSummaryContext = createContext<OrganizationSummaryContextValue | undefined>(undefined);
