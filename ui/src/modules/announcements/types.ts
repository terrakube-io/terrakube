export type AnnouncementSeverity = "INFO" | "WARNING" | "CRITICAL";

export type AnnouncementScope = "GLOBAL" | "ORGANIZATION";

export type AnnouncementScheduleType = "IMMEDIATE" | "SCHEDULED";

export type AnnouncementLifecycleStatus = "INACTIVE" | "SCHEDULED" | "ACTIVE" | "EXPIRED";

export interface AnnouncementItem {
  id: string;
  title: string;
  message: string;
  severity: AnnouncementSeverity;
  scope: AnnouncementScope;
  scheduleType: AnnouncementScheduleType;
  organizationId: string | null;
  organizationName?: string;
  startsAt: string | null;
  endsAt: string | null;
  active: boolean;
  dismissible: boolean;
  displayOnLogin: boolean;
  createdDate: string | null;
  updatedDate: string | null;
  createdBy?: string | null;
  updatedBy?: string | null;
}

export interface PublicAnnouncementItem {
  id: string;
  title: string;
  message: string;
  severity: AnnouncementSeverity;
  startsAt: string | null;
  endsAt: string | null;
  updatedDate: string | null;
}

export interface AnnouncementPayload {
  title: string;
  message: string;
  severity: AnnouncementSeverity;
  scope: AnnouncementScope;
  scheduleType: AnnouncementScheduleType;
  organizationId?: string | null;
  startsAt: string | null;
  endsAt: string | null;
  active: boolean;
  dismissible: boolean;
  displayOnLogin: boolean;
}
