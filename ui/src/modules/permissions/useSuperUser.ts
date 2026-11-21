import { useEffect, useState } from "react";
import { apiUrl } from "@/config/apiUrls";
import getUserFromStorage from "@/config/authUser";
import { axiosAuxiliary } from "@/config/axiosConfig";

let cachedToken: string | null = null;
let cachedSuperUser: boolean | null = null;
let inflightRequest: Promise<boolean> | null = null;

export function resetSuperUserCache() {
  cachedToken = null;
  cachedSuperUser = null;
  inflightRequest = null;
}

export function useSuperUser() {
  const accessToken = getUserFromStorage()?.access_token ?? null;
  const hasCached = Boolean(accessToken && cachedToken === accessToken && cachedSuperUser !== null);

  const [isSuperUser, setIsSuperUser] = useState<boolean>(() => (hasCached ? Boolean(cachedSuperUser) : false));
  const [loading, setLoading] = useState<boolean>(() => Boolean(accessToken) && !hasCached);

  useEffect(() => {
    if (!accessToken) {
      setIsSuperUser(false);
      setLoading(false);
      return;
    }

    if (cachedToken === accessToken && cachedSuperUser !== null) {
      setIsSuperUser(cachedSuperUser);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);

    if (cachedToken !== accessToken || !inflightRequest) {
      cachedToken = accessToken;
      cachedSuperUser = null;
      inflightRequest = axiosAuxiliary
        .get<{ superUser?: boolean }>(`${apiUrl("")}/access-token/v1/teams/current-teams`, {
          auxClass: "permissions",
        })
        .then((response) => Boolean(response.data?.superUser))
        .catch(() => false);
    }

    inflightRequest
      .then((result) => {
        if (cachedToken === accessToken) {
          cachedSuperUser = result;
        }
        if (!cancelled) {
          setIsSuperUser(result);
        }
      })
      .finally(() => {
        if (cachedToken === accessToken) {
          inflightRequest = null;
        }
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [accessToken]);

  return { isSuperUser, loading };
}
