import { useContext } from "react";
import { OrganizationSummaryContext } from "./OrganizationSummaryStore";

export function useOrganizationSummaries() {
  const context = useContext(OrganizationSummaryContext);
  if (!context) {
    throw new Error("useOrganizationSummaries must be used within OrganizationSummaryProvider");
  }
  return context;
}
