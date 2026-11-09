import { useEffect, useState } from "react";
import axiosInstance from "../../config/axiosConfig";

export interface ProjectPermissionSet {
  manageProject: boolean;
  manageWorkspace: boolean;
  manageState: boolean;
  manageJob: boolean;
  planJob: boolean;
  approveJob: boolean;
  managePolicies: boolean;
}

const none: ProjectPermissionSet = {
  manageProject: false,
  manageWorkspace: false,
  manageState: false,
  manageJob: false,
  planJob: false,
  approveJob: false,
  managePolicies: false,
};

/**
 * The current user's effective permissions on one project (organization team plus project access), resolved by
 * the API. Everything is false until loaded and on error, so the UI stays read-only.
 */
export function useProjectPermissions(orgId?: string, projectId?: string) {
  const [permissions, setPermissions] = useState<ProjectPermissionSet>(none);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!orgId || !projectId) {
      setPermissions(none);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setPermissions(none);
    setLoading(true);
    const url = `${
      new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin
    }/access-token/v1/teams/permissions/organization/${orgId}/project/${projectId}`;
    axiosInstance
      .get(url)
      .then((response) => {
        if (cancelled) return;
        const data = response.data ?? {};
        setPermissions({
          manageProject: data.manageProject ?? false,
          manageWorkspace: data.manageWorkspace ?? false,
          manageState: data.manageState ?? false,
          manageJob: data.manageJob ?? false,
          planJob: data.planJob ?? false,
          approveJob: data.approveJob ?? false,
          managePolicies: data.managePolicies ?? false,
        });
      })
      .catch(() => {
        // Keep the read-only defaults.
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [orgId, projectId]);

  return { permissions, loading };
}
