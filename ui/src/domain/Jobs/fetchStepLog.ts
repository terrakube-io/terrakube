import axiosInstance, { axiosClient } from "../../config/axiosConfig";
import { getJobOutputRequestUrl, getPublicApiOrigin, isTerrakubeApiUrl } from "./outputUrl";

/** Objects at or above this size are fetched tail-first via a Range request. */
export const LARGE_LOG_THRESHOLD = 2 * 1024 * 1024;

export class StepLogNotFoundError extends Error {
  constructor() {
    super("Step log not found");
    this.name = "StepLogNotFoundError";
  }
}

export class StepLogFetchError extends Error {
  status?: number;
  constructor(message: string, status?: number) {
    super(message);
    this.name = "StepLogFetchError";
    this.status = status;
  }
}

type FetchStepLogParams = {
  output?: string;
  jobId: string;
  organizationId: string;
  stepId: string;
  signal: AbortSignal;
  tailBytes?: number;
};

type FetchStepLogResult = {
  text: string;
  truncated: boolean;
};

function resolveUrl(params: FetchStepLogParams): { url: string; terrakubeApi: boolean } {
  if (params.output != null && params.output !== "") {
    const url = getJobOutputRequestUrl(params.output);
    return { url, terrakubeApi: isTerrakubeApiUrl(url) };
  }
  const url =
    `${getPublicApiOrigin()}/tfoutput/v1/organization/${params.organizationId}` +
    `/job/${params.jobId}/step/${params.stepId}`;
  return { url, terrakubeApi: true };
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && (error.name === "AbortError" || error.name === "CanceledError");
}

function statusOf(error: unknown): number | undefined {
  const response = (error as { response?: { status?: number } })?.response;
  return response?.status;
}

export async function fetchStepLog(params: FetchStepLogParams): Promise<FetchStepLogResult> {
  const { url, terrakubeApi } = resolveUrl(params);
  const client = terrakubeApi ? axiosInstance : axiosClient;

  let contentLength: number | undefined;
  try {
    const head = await client.head(url, { signal: params.signal });
    const raw = head.headers?.["content-length"];
    contentLength = raw != null ? Number(raw) : undefined;
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    if (statusOf(error) === 404) {
      throw new StepLogNotFoundError();
    }
    // Some backends / proxies reject HEAD - fall through to a plain GET.
    contentLength = undefined;
  }

  const wantsTail =
    params.tailBytes != null && contentLength != null && contentLength >= LARGE_LOG_THRESHOLD;

  try {
    const response = wantsTail
      ? await client.get(url, {
          signal: params.signal,
          headers: { Range: `bytes=-${params.tailBytes}` },
          responseType: "text",
          transformResponse: (d) => d,
        })
      : await client.get(url, {
          signal: params.signal,
          responseType: "text",
          transformResponse: (d) => d,
        });

    const text = typeof response.data === "string" ? response.data : String(response.data ?? "");
    const contentRange = response.headers?.["content-range"] as string | undefined;
    const truncated =
      response.status === 206 && contentRange != null && !/^bytes 0-/.test(contentRange);

    return { text, truncated };
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    if (statusOf(error) === 404) {
      throw new StepLogNotFoundError();
    }
    throw new StepLogFetchError(`Step log request failed`, statusOf(error));
  }
}
