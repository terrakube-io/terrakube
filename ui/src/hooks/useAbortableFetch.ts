import { useEffect, useRef, useCallback } from "react";
import { AxiosRequestConfig, AxiosResponse } from "axios";
import axiosInstance from "../config/axiosConfig";

interface UseAbortableFetchReturn<T> {
  execute: (config?: AxiosRequestConfig) => Promise<AxiosResponse<T>>;
  abort: () => void;
  isAborted: boolean;
}

export function useAbortableFetch<T = unknown>(url: string, config?: AxiosRequestConfig): UseAbortableFetchReturn<T> {
  const abortControllerRef = useRef<AbortController | null>(null);
  const isAbortedRef = useRef(false);

  const abort = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      isAbortedRef.current = true;
    }
  }, []);

  const execute = useCallback(
    async (overrideConfig?: AxiosRequestConfig): Promise<AxiosResponse<T>> => {
      abort();

      abortControllerRef.current = new AbortController();
      isAbortedRef.current = false;

      try {
        const response = await axiosInstance.request<T>({
          url,
          ...config,
          ...overrideConfig,
          signal: abortControllerRef.current.signal,
        });
        return response;
      } catch (error: unknown) {
        if (error instanceof Error && (error.name === "AbortError" || error.name === "CanceledError")) {
          isAbortedRef.current = true;
        }
        throw error;
      }
    },
    [url, config, abort]
  );

  useEffect(() => {
    return () => {
      abort();
    };
  }, [abort]);

  return {
    execute,
    abort,
    get isAborted() {
      return isAbortedRef.current;
    },
  };
}

export function useAbortController() {
  const controllerRef = useRef<AbortController | null>(null);

  const getSignal = useCallback(() => {
    if (controllerRef.current) {
      controllerRef.current.abort();
    }
    controllerRef.current = new AbortController();
    return controllerRef.current.signal;
  }, []);

  const abort = useCallback(() => {
    if (controllerRef.current) {
      controllerRef.current.abort();
    }
  }, []);

  useEffect(() => {
    return () => {
      if (controllerRef.current) {
        controllerRef.current.abort();
      }
    };
  }, []);

  return { getSignal, abort };
}
