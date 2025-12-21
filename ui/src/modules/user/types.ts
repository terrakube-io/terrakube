import { AuditFieldBase } from "@/modules/types";

export type UserToken = {
  id: string;
  deleted: boolean;
  days: number;
  description: string;
} & AuditFieldBase;
