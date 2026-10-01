import { AuditFieldBase } from "@/modules/types";

export type UserToken = {
  id: string;
  deleted: boolean;
  days: number;
  description: string;
  source?: "API" | "CLI_LOGIN";
  lastUsedAt?: string | null;
} & AuditFieldBase;
