import axios from "axios";
import { apiUrl } from "@/config/apiUrls";
import axiosInstance, { axiosAuxiliary } from "@/config/axiosConfig";
import type {
  AnnouncementItem,
  AnnouncementPayload,
  AnnouncementScheduleType,
  AnnouncementScope,
  AnnouncementSeverity,
  PublicAnnouncementItem,
} from "./types";

const JSON_API_HEADERS = {
  "Content-Type": "application/vnd.api+json",
};

interface JsonApiResource {
  id: string;
  type: string;
  attributes?: Record<string, any>;
  relationships?: Record<string, { data?: { id: string; type: string } | null }>;
}

function parseServerTimeMs(headers: Record<string, any> | undefined, bodyServerTime?: string | null): number {
  const headerVal = headers?.["x-server-time"];
  const candidate = typeof headerVal === "string" && headerVal ? headerVal : bodyServerTime;
  if (candidate) {
    const parsed = Date.parse(candidate);
    if (!Number.isNaN(parsed)) {
      return parsed;
    }
  }
  return Date.now();
}

function mapJsonApiAnnouncement(resource: JsonApiResource, orgNamesById: Record<string, string>): AnnouncementItem {
  const attrs = resource.attributes ?? {};
  const orgRel = resource.relationships?.organization?.data;
  const organizationId = orgRel?.id ?? null;

  return {
    id: resource.id,
    title: String(attrs.title ?? ""),
    message: String(attrs.message ?? ""),
    severity: (attrs.severity as AnnouncementSeverity) || "INFO",
    scope: (attrs.scope as AnnouncementScope) || "ORGANIZATION",
    scheduleType: (attrs.scheduleType as AnnouncementScheduleType) || "IMMEDIATE",
    organizationId,
    organizationName: organizationId ? orgNamesById[organizationId] : undefined,
    startsAt: attrs.startsAt ?? null,
    endsAt: attrs.endsAt ?? null,
    active: attrs.active !== false,
    dismissible: attrs.dismissible !== false,
    displayOnLogin: Boolean(attrs.displayOnLogin),
    createdDate: attrs.createdDate ?? null,
    updatedDate: attrs.updatedDate ?? null,
    createdBy: attrs.createdBy ?? null,
    updatedBy: attrs.updatedBy ?? null,
  };
}

function mapIncludedOrganizations(included: JsonApiResource[] | undefined): Record<string, string> {
  const map: Record<string, string> = {};
  if (!Array.isArray(included)) {
    return map;
  }
  for (const item of included) {
    if (item.type === "organization" && item.id) {
      map[item.id] = String(item.attributes?.name ?? item.id);
    }
  }
  return map;
}

export async function fetchPublicLoginAnnouncements(): Promise<{
  announcements: PublicAnnouncementItem[];
  serverTimeMs: number;
}> {
  const response = await axios.get<{
    serverTime?: string;
    announcements?: PublicAnnouncementItem[];
  }>(apiUrl("/public/v1/announcements"));
  const data = response.data ?? {};
  return {
    announcements: Array.isArray(data.announcements) ? data.announcements : [],
    serverTimeMs: parseServerTimeMs(response.headers, data.serverTime ?? null),
  };
}

export async function fetchAnnouncementsForBanner(orgId: string | null): Promise<{
  announcements: AnnouncementItem[];
  serverTimeMs: number;
}> {
  const response = await axiosAuxiliary.get("announcement?include=organization", {
    auxClass: "announcements",
  });
  const orgNames = mapIncludedOrganizations(response.data?.included);
  const rawItems: JsonApiResource[] = Array.isArray(response.data?.data) ? response.data.data : [];
  const mapped = rawItems
    .map((item) => mapJsonApiAnnouncement(item, orgNames))
    .filter((item) => item.scope === "GLOBAL" || (orgId !== null && item.organizationId === orgId));

  return {
    announcements: mapped,
    serverTimeMs: parseServerTimeMs(response.headers),
  };
}

export async function listOrgAnnouncements(orgId: string): Promise<AnnouncementItem[]> {
  const response = await axiosInstance.get(
    `announcement?include=organization&filter[announcement]=scope==ORGANIZATION;organization.id==${orgId}`
  );
  const orgNames = mapIncludedOrganizations(response.data?.included);
  const rawItems: JsonApiResource[] = Array.isArray(response.data?.data) ? response.data.data : [];
  return rawItems.map((item) => mapJsonApiAnnouncement(item, orgNames));
}

export async function listAllAnnouncements(): Promise<AnnouncementItem[]> {
  const response = await axiosInstance.get("announcement?include=organization");
  const orgNames = mapIncludedOrganizations(response.data?.included);
  const rawItems: JsonApiResource[] = Array.isArray(response.data?.data) ? response.data.data : [];
  return rawItems.map((item) => mapJsonApiAnnouncement(item, orgNames));
}

export async function createAnnouncement(payload: AnnouncementPayload): Promise<AnnouncementItem> {
  const isOrgScoped = payload.scope === "ORGANIZATION";
  const attributes: Record<string, unknown> = {
    title: payload.title.trim(),
    message: payload.message,
    severity: payload.severity,
    scope: payload.scope,
    scheduleType: payload.scheduleType,
    startsAt: payload.startsAt,
    endsAt: payload.endsAt,
    active: payload.active,
    dismissible: payload.dismissible,
    displayOnLogin: isOrgScoped ? false : payload.displayOnLogin,
  };

  const body: Record<string, unknown> = {
    data: {
      type: "announcement",
      attributes,
      ...(isOrgScoped && payload.organizationId
        ? {
            relationships: {
              organization: {
                data: { type: "organization", id: payload.organizationId },
              },
            },
          }
        : {}),
    },
  };

  const endpoint =
    isOrgScoped && payload.organizationId ? `organization/${payload.organizationId}/announcement` : "announcement";
  const response = await axiosInstance.post(endpoint, body, { headers: JSON_API_HEADERS });
  return mapJsonApiAnnouncement(response.data?.data, {});
}

export async function updateAnnouncement(
  id: string,
  payload: Partial<AnnouncementPayload>,
  options: {
    isSuperUser?: boolean;
    includeScopeAndOrg?: boolean;
  } = {}
): Promise<void> {
  const attributes: Record<string, unknown> = {};
  if (payload.title !== undefined) attributes.title = payload.title.trim();
  if (payload.message !== undefined) attributes.message = payload.message;
  if (payload.severity !== undefined) attributes.severity = payload.severity;
  if (payload.scheduleType !== undefined) attributes.scheduleType = payload.scheduleType;
  if (payload.startsAt !== undefined) attributes.startsAt = payload.startsAt;
  if (payload.endsAt !== undefined) attributes.endsAt = payload.endsAt;
  if (payload.active !== undefined) attributes.active = payload.active;
  if (payload.dismissible !== undefined) attributes.dismissible = payload.dismissible;
  if (payload.displayOnLogin !== undefined) {
    attributes.displayOnLogin = payload.scope === "ORGANIZATION" ? false : payload.displayOnLogin;
  }

  let relationships: Record<string, unknown> | undefined;
  // Never include superuser-only fields (`scope`, `organization`) for non-superusers on PATCH.
  if (options.isSuperUser && options.includeScopeAndOrg) {
    if (payload.scope !== undefined) {
      attributes.scope = payload.scope;
    }
    if (payload.scope === "GLOBAL") {
      relationships = { organization: { data: null } };
    } else if (payload.scope === "ORGANIZATION" && payload.organizationId) {
      relationships = {
        organization: { data: { type: "organization", id: payload.organizationId } },
      };
    }
  }

  await axiosInstance.patch(
    `announcement/${id}`,
    {
      data: {
        type: "announcement",
        id,
        attributes,
        ...(relationships ? { relationships } : {}),
      },
    },
    { headers: JSON_API_HEADERS }
  );
}

export async function deleteAnnouncement(id: string): Promise<void> {
  await axiosInstance.delete(`announcement/${id}`);
}
