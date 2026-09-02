import axios, { AxiosError, AxiosResponse } from "axios";
import { mgr } from "./authConfig";
import getUserFromStorage from "./authUser";
import { setBackendError, FATAL_API_STATUSES } from "@/modules/api/backendStatus";
import {
  classifyRequestOutcome,
  recordAuxiliaryRequestFailure,
  recordGlobalBackendErrorActivation,
} from "@/modules/api/requestMetrics";

declare module "axios" {
  interface AxiosRequestConfig {
    /**
     * Marks a request as auxiliary Job Details data (context read, archived log, reconciliation
     * poll). Only used for metric labelling - the {@link axiosAuxiliary} client already prevents
     * global backend-error mutation.
     */
    auxClass?: string;
  }
}

type RuntimeEnv = Window["_env_"] & { REACT_APP_TERRAKUBE_SEND_COOKIES?: string };

const runtimeEnv = window._env_ as RuntimeEnv;
const sendCookiesWithRequests = runtimeEnv.REACT_APP_TERRAKUBE_SEND_COOKIES?.trim().toLowerCase() === "true";

const axiosInstance = axios.create({
  baseURL: window._env_.REACT_APP_TERRAKUBE_API_URL,
  withCredentials: sendCookiesWithRequests,
});

export const axiosClient = axios.create({
  baseURL: window._env_.REACT_APP_TERRAKUBE_API_URL,
  withCredentials: sendCookiesWithRequests,
});

export const axiosGraphQL = axios.create({
  baseURL: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin + "/graphql/api/v1",
  withCredentials: sendCookiesWithRequests,
});

// Axios instance for Terraform Registry proxy (without /api/v1 prefix)
export const axiosRegistry = axios.create({
  baseURL: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin,
  withCredentials: sendCookiesWithRequests,
});

/**
 * Client for auxiliary Job Details data (structured context, archived step logs, reconciliation
 * polls). Same auth as {@link axiosInstance}, but its 404/429/5xx/timeout/network failures are
 * local to the calling component and never activate the application-wide backend-error screen.
 * 401/403 handling is identical to the shared interceptor.
 */
export const axiosAuxiliary = axios.create({
  baseURL: window._env_.REACT_APP_TERRAKUBE_API_URL,
  withCredentials: sendCookiesWithRequests,
});

// Shared request interceptor that attaches the Bearer token
function attachAuthToken(config: any) {
  const user = getUserFromStorage();
  const accessToken = user?.access_token;
  config.headers["Authorization"] = `Bearer ${accessToken}`;
  return config;
}

function rejectError(error: any) {
  return Promise.reject(error);
}

axiosInstance.interceptors.request.use(attachAuthToken, rejectError);
axiosGraphQL.interceptors.request.use(attachAuthToken, rejectError);
axiosRegistry.interceptors.request.use(attachAuthToken, rejectError);
axiosAuxiliary.interceptors.request.use(attachAuthToken, rejectError);

// Shared response interceptor that enriches 403 errors with a clear message
function handleResponseSuccess(response: AxiosResponse) {
  setBackendError(null);
  return response;
}

// 401/403 handling shared by the core and auxiliary interceptors - never duplicated.
function applyAuthHandling(error: AxiosError) {
  if (error.response?.status === 401) {
    // Token rejected by the API - sign out so the user lands back on Login.
    mgr.removeUser();
  }
  if (error.response?.status === 403) {
    // Enrich the error with a clear permission message so callers can display it
    const enriched = error as AxiosError & { permissionError: true; permissionMessage: string };
    enriched.permissionError = true;
    enriched.permissionMessage =
      "You do not have the required permissions to perform this action. Please contact your organization administrator.";
  }
}

function handleResponseError(error: AxiosError) {
  if (error.response && FATAL_API_STATUSES.includes(error.response.status)) {
    setBackendError(error.response.status);
    recordGlobalBackendErrorActivation(error.config?.auxClass ?? "core");
  }
  applyAuthHandling(error);
  return Promise.reject(error);
}

// Auxiliary requests: a transient failure stays local to the calling component. No backend-error
// mutation (invariant 2), and a success must not clear a genuine global outage (invariant 6).
function handleAuxiliaryResponseError(error: AxiosError) {
  const requestClass = error.config?.auxClass ?? "auxiliary";
  if (!error.response || !error.response.status || error.response.status < 400) {
    recordAuxiliaryRequestFailure(requestClass, classifyRequestOutcome(error));
  } else {
    recordAuxiliaryRequestFailure(requestClass, String(error.response.status));
  }
  applyAuthHandling(error);
  return Promise.reject(error);
}

axiosInstance.interceptors.response.use(handleResponseSuccess, handleResponseError);
axiosGraphQL.interceptors.response.use(handleResponseSuccess, handleResponseError);
axiosRegistry.interceptors.response.use(handleResponseSuccess, handleResponseError);
axiosAuxiliary.interceptors.response.use((response) => response, handleAuxiliaryResponseError);

/**
 * Helper to extract a user-friendly error message from an axios error.
 * Use this in .catch() blocks to display meaningful errors.
 */
export function getErrorMessage(error: any): string {
  if (error?.permissionError) {
    return error.permissionMessage;
  }
  if (axios.isAxiosError(error)) {
    if (error.response?.status === 403) {
      return "You do not have the required permissions to perform this action.";
    }
    if (error.response?.status === 429) {
      if (typeof error.response.data === "string" && error.response.data.trim() !== "") {
        return error.response.data;
      }

      return "The upstream API rate limit was reached. Please wait a moment and try again.";
    }
    if (error.response?.status === 404) {
      return "The requested resource could not be found.";
    }
    const jsonApiDetail = error.response?.data?.errors?.[0]?.detail;
    if (typeof jsonApiDetail === "string" && jsonApiDetail.trim() !== "") {
      return jsonApiDetail;
    }
    return error.response?.statusText || error.message || "An unexpected error occurred.";
  }
  return error?.message || "An unexpected error occurred.";
}

/**
 * Returns true if the error is a 403 permission error.
 */
export function isPermissionError(error: any): boolean {
  return error?.permissionError === true || error?.response?.status === 403;
}

export default axiosInstance;
