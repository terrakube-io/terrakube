import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axiosInstance from "../../config/axiosConfig";

/**
 * Represents the full set of permissions returned by the
 * /access-token/v1/teams/permissions/organization/{orgId} endpoint.
 */
export interface OrgPermissionSet {
  manageState: boolean;
  manageWorkspace: boolean;
  manageModule: boolean;
  manageProvider: boolean;
  manageVcs: boolean;
  manageTemplate: boolean;
  manageCollection: boolean;
  manageJob: boolean;
  planJob: boolean;
  approveJob: boolean;
  managePermission: boolean;
}

const defaultPermissions: OrgPermissionSet = {
  manageState: false,
  manageWorkspace: false,
  manageModule: false,
  manageProvider: false,
  manageVcs: false,
  manageTemplate: false,
  manageCollection: false,
  manageJob: false,
  planJob: false,
  approveJob: false,
  managePermission: false,
};

/**
 * Hook that fetches organization-level permissions for the current user.
 * Uses the orgid from the URL params.
 *
 * Returns { permissions, loading } where permissions contains all the
 * granular boolean flags from the backend PermissionSet.
 */
export function useOrgPermissions() {
  const { orgid } = useParams<{ orgid: string }>();
  const [permissions, setPermissions] = useState<OrgPermissionSet>(defaultPermissions);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!orgid) {
      setLoading(false);
      return;
    }

    const url = `${
      new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
    }/access-token/v1/teams/permissions/organization/${orgid}`;

    axiosInstance
      .get(url)
      .then((response) => {
        setPermissions({
          manageState: response.data.manageState ?? false,
          manageWorkspace: response.data.manageWorkspace ?? false,
          manageModule: response.data.manageModule ?? false,
          manageProvider: response.data.manageProvider ?? false,
          manageVcs: response.data.manageVcs ?? false,
          manageTemplate: response.data.manageTemplate ?? false,
          manageCollection: response.data.manageCollection ?? false,
          manageJob: response.data.manageJob ?? false,
          planJob: response.data.planJob ?? false,
          approveJob: response.data.approveJob ?? false,
          managePermission: response.data.managePermission ?? false,
        });
      })
      .catch((err) => {
        console.error("Failed to load org permissions:", err);
        // Keep default (all false) on error — user sees read-only UI
      })
      .finally(() => {
        setLoading(false);
      });
  }, [orgid]);

  return { permissions, loading };
}
