import { AuditFieldBase } from "@/modules/types";

export type UserToken = {
  id: string;
  deleted: boolean;
  days: number;
  // Team tokens also carry hours and minutes; personal tokens only have days.
  hours?: number;
  minutes?: number;
  description: string;
  source?: "API" | "CLI_LOGIN";
  lastUsedAt?: string | null;
} & AuditFieldBase;
