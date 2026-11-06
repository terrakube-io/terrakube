import axios from "axios";
import { axiosGraphQL } from "@/config/axiosConfig";
import { Announcement, AnnouncementSeverity, PublicAnnouncement } from "./announcementTypes";

const SEVERITY_PRIORITY: Record<AnnouncementSeverity, number> = {
  critical: 0,
  warning: 1,
  info: 2,
};

export function filterAndSortAnnouncements(
  announcements: Announcement[],
  currentOrgId?: string,
  now: Date = new Date()
): Announcement[] {
  return announcements
    .filter((a) => a.active)
    .filter((a) => {
      if (a.startsAt && new Date(a.startsAt) > now) {
        return false;
      }
      if (a.endsAt && new Date(a.endsAt) < now) {
        return false;
      }
      return true;
    })
    .filter((a) => {
      if (a.scope === "GLOBAL") {
        return true;
      }
      if (a.scope === "ORGANIZATION") {
        return Boolean(currentOrgId && a.organization?.id === currentOrgId);
      }
      return false;
    })
    .sort((a, b) => {
      const priorityA = SEVERITY_PRIORITY[a.severity] ?? 99;
      const priorityB = SEVERITY_PRIORITY[b.severity] ?? 99;
      if (priorityA !== priorityB) {
        return priorityA - priorityB;
      }
      const timeA = a.startsAt ? new Date(a.startsAt).getTime() : 0;
      const timeB = b.startsAt ? new Date(b.startsAt).getTime() : 0;
      return timeB - timeA;
    });
}

export async function fetchAnnouncements(): Promise<Announcement[]> {
  const body = {
    query: `{
      announcement {
        edges {
          node {
            id
            title
            message
            severity
            scope
            active
            dismissible
            displayOnLogin
            startsAt
            endsAt
            createdDate
            updatedDate
            organization {
              id
              name
            }
          }
        }
      }
    }`,
  };

  const response = await axiosGraphQL.post("", body, {
    headers: { "Content-Type": "application/json" },
  });

  if (response.data?.errors?.length) {
    throw new Error(response.data.errors[0]?.message || "Failed to load announcements");
  }

  const edges = response.data?.data?.announcement?.edges;
  if (!edges) {
    return [];
  }

  return edges.map((edge: { node: any }) => {
    const node = edge.node;
    return {
      id: node.id,
      title: node.title,
      message: node.message,
      severity: node.severity,
      scope: node.scope,
      active: node.active,
      dismissible: node.dismissible,
      displayOnLogin: node.displayOnLogin,
      startsAt: node.startsAt,
      endsAt: node.endsAt,
      createdAt: node.createdDate,
      updatedAt: node.updatedDate,
      organization: node.organization
        ? {
            id: node.organization.id,
            name: node.organization.name,
          }
        : null,
    };
  });
}

export async function fetchPublicLoginAnnouncements(): Promise<PublicAnnouncement[]> {
  const baseUrl = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin;
  const response = await axios.get<PublicAnnouncement[]>(`${baseUrl}/public/v1/announcements`);
  return response.data || [];
}

