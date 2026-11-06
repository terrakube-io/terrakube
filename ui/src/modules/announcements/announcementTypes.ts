export type AnnouncementSeverity = "info" | "warning" | "critical";
export type AnnouncementScope = "GLOBAL" | "ORGANIZATION";

export interface Announcement {
  id: string;
  title: string;
  message: string;
  severity: AnnouncementSeverity;
  scope: AnnouncementScope;
  startsAt?: string | null;
  endsAt?: string | null;
  active: boolean;
  dismissible: boolean;
  displayOnLogin: boolean;
  createdAt?: string;
  updatedAt?: string;
  organization?: {
    id: string;
    name?: string;
  } | null;
}

export interface DismissalRecord {
  announcementId: string;
  versionHash: string;
}

export interface PublicAnnouncement {
  id: string;
  title: string;
  message: string;
  severity: AnnouncementSeverity;
  startsAt?: string | null;
  endsAt?: string | null;
  updatedAt?: string | null;
  dismissible: boolean;
}
