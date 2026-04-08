import axios from "axios";
import getUserFromStorage from "../../config/authUser";
import { ApiResponse, RequestOptions } from "./types";

const BASE_API_URL = new URL(window._env_.REACT_APP_TERRAKUBE_API_URL).origin;
type RuntimeEnv = Window["_env_"] & { REACT_APP_TERRAKUBE_SEND_COOKIES?: string };

const runtimeEnv = window._env_ as RuntimeEnv;
const sendCookiesWithRequests = runtimeEnv.REACT_APP_TERRAKUBE_SEND_COOKIES?.trim().toLowerCase() === "true";

const defaultRequestOptions: RequestOptions = {
  requireAuth: true,
  contentType: "application/vnd.api+json",
};

function getOptions(options?: RequestOptions): RequestOptions {
  if (options === undefined) return defaultRequestOptions;
  return {
    ...defaultRequestOptions,
    ...options,
  };
}

async function requestWrapper<T>(
  requireAuth: boolean = true,
  requestFunc: (accessToken?: string) => Promise<ApiResponse<T>>
): Promise<ApiResponse<T>> {
  try {
    if (requireAuth) {
      const tokenModel = getUserFromStorage();
      if (tokenModel?.access_token === undefined || tokenModel?.access_token === null) {
        return {
          isError: true,
          error: {
            status: "Unauthorized",
            statusCode: 401,
          },
          responseCode: 404,
        };
      }
      return await requestFunc(tokenModel.access_token);
    }
    return await requestFunc();
  } catch (error) {
    if (axios.isAxiosError(error)) {
      if (error.response?.status === 404) {
        return {
          isError: true,
          error: {
            status: "Not Found",
            statusCode: 404,
            message: "The requested resource could not be found",
          },
          responseCode: 404,
        };
      }

      if (error.response?.status === 403) {
        return {
          isError: true,
          error: {
            status: "Forbidden",
            statusCode: 403,
            message:
              "You do not have the required permissions to perform this action. Please contact your organization administrator.",
          },
          responseCode: 403,
        };
      }

      let apiErr = error.response?.data;

      if (Object.keys(apiErr ?? {}).length === 0) {
        const errRes = error.response as any;
        apiErr = {
          statusCode: errRes?.status || 500,
          message: errRes?.statusText || "Failed to request",
          status: errRes?.statusText || "Internal server error",
        };
      }

      const res = {
        isError: true,
        error: apiErr,
        responseCode: (error.response as any)?.status || 500,
      };
      return res;
    } else {
      return {
        isError: true,
        responseCode: 500,
      };
    }
  }
}

export async function apiPost<TRequest, TResponse>(
  path: string,
  body: TRequest,
  options?: RequestOptions
): Promise<ApiResponse<TResponse>> {
  return await post<TRequest, TResponse>(`${BASE_API_URL}${path}`, body, getOptions(options));
}

export async function apiPut<TRequest, TResponse>(
  path: string,
  body: TRequest,
  options?: RequestOptions
): Promise<ApiResponse<TResponse>> {
  return await put<TRequest, TResponse>(`${BASE_API_URL}${path}`, body, getOptions(options));
}
export async function apiGet<T>(path: string, options?: RequestOptions): Promise<ApiResponse<T>> {
  return await get<T>(`${BASE_API_URL}${path}`, getOptions(options));
}

export async function apiDelete(path: string, options?: RequestOptions): Promise<ApiResponse<undefined>> {
  return await intDelete(`${BASE_API_URL}${path}`, getOptions(options));
}

async function get<T>(path: string, options: RequestOptions): Promise<ApiResponse<T>> {
  return await requestWrapper(options.requireAuth, async (accessToken?: string) => {
    const headers: Record<string, string> = {
      "Content-type": options.contentType!,
    };

    if (options.requireAuth) {
      headers["Authorization"] = `Bearer ${accessToken}`;
    }

    const response = await axios({
      method: "GET",
      url: path,
      headers: headers,
      params: options.query,
      withCredentials: sendCookiesWithRequests,
    });
    return {
      isError: false,
      data: options.dataWrapped ? response.data?.data : response.data,
      responseCode: response.status,
    };
  });
}

async function post<TRequest, TResponse>(
  path: string,
  body: TRequest,
  options: RequestOptions
): Promise<ApiResponse<TResponse>> {
  return await requestWrapper(options.requireAuth, async (accessToken?: string) => {
    const headers: Record<string, string> = {
      "Content-type": options.contentType!,
    };

    if (options.requireAuth) {
      headers["Authorization"] = `Bearer ${accessToken}`;
    }
    const response = await axios({
      method: "POST",
      url: path,
      headers: headers,
      data: body,
      params: options.query,
      withCredentials: sendCookiesWithRequests,
    });

    return {
      isError: false,
      data: options.dataWrapped ? response.data?.data : response.data,
      responseCode: response.status,
    };
  });
}
async function put<TRequest, TResponse>(
  path: string,
  body: TRequest,
  options: RequestOptions
): Promise<ApiResponse<TResponse>> {
  return await requestWrapper(options.requireAuth, async (accessToken?: string) => {
    const headers: Record<string, string> = {
      "Content-type": options.contentType!,
    };

    if (options.requireAuth) {
      headers["Authorization"] = `Bearer ${accessToken}`;
    }
    const response = await axios({
      method: "PUT",
      url: path,
      headers: headers,
      data: body,
      params: options.query,
      withCredentials: sendCookiesWithRequests,
    });
    return {
      isError: false,
      data: options.dataWrapped ? response.data?.data : response.data,
      responseCode: response.status,
    };
  });
}
async function intDelete<T>(path: string, options: RequestOptions): Promise<ApiResponse<T>> {
  return await requestWrapper(options.requireAuth, async (accessToken?: string) => {
    const headers: Record<string, string> = {
      "Content-type": options.contentType!,
    };

    if (options.requireAuth) {
      headers["Authorization"] = `Bearer ${accessToken}`;
    }

    const response = await axios({
      method: "DELETE",
      url: path,
      headers: headers,
      params: options.query,
      withCredentials: sendCookiesWithRequests,
    });
    return {
      isError: false,
      data: response.data,
      responseCode: response.status,
    };
  });
}
