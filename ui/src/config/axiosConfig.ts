import axios, { AxiosError, AxiosResponse } from "axios";
import getUserFromStorage from "./authUser";

const axiosInstance = axios.create({
  baseURL: window._env_.REACT_APP_TERRAKUBE_API_URL,
});

export const axiosClient = axios.create({
  baseURL: window._env_.REACT_APP_TERRAKUBE_API_URL,
});

export const axiosGraphQL = axios.create({
  baseURL: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin + "/graphql/api/v1",
});

// Axios instance for Terraform Registry proxy (without /api/v1 prefix)
export const axiosRegistry = axios.create({
  baseURL: new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin,
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

// Shared response interceptor that enriches 403 errors with a clear message
function handleResponseSuccess(response: AxiosResponse) {
  return response;
}

function handleResponseError(error: AxiosError) {
  if (error.response?.status === 403) {
    // Enrich the error with a clear permission message so callers can display it
    const enriched = error as AxiosError & { permissionError: true; permissionMessage: string };
    enriched.permissionError = true;
    enriched.permissionMessage =
      "You do not have the required permissions to perform this action. Please contact your organization administrator.";
  }
  return Promise.reject(error);
}

axiosInstance.interceptors.response.use(handleResponseSuccess, handleResponseError);
axiosGraphQL.interceptors.response.use(handleResponseSuccess, handleResponseError);
axiosRegistry.interceptors.response.use(handleResponseSuccess, handleResponseError);

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
    if (error.response?.status === 404) {
      return "The requested resource could not be found.";
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
