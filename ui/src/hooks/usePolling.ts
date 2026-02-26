import { useEffect, useRef, useCallback } from "react";

interface UsePollingOptions {
  interval: number;
  enabled?: boolean;
  immediate?: boolean;
}

export function usePolling(callback: () => void | Promise<void>, options: UsePollingOptions) {
  const { interval, enabled = true, immediate = false } = options;
  const savedCallback = useRef(callback);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    savedCallback.current = callback;
  }, [callback]);

  const clearPolling = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  useEffect(() => {
    if (!enabled) {
      clearPolling();
      return;
    }

    if (immediate) {
      savedCallback.current();
    }

    intervalRef.current = setInterval(() => {
      savedCallback.current();
    }, interval);

    return () => {
      clearPolling();
    };
  }, [interval, enabled, immediate, clearPolling]);

  useEffect(() => {
    if (!enabled) return;

    const handleVisibilityChange = () => {
      if (document.hidden) {
        clearPolling();
      } else {
        if (immediate) {
          savedCallback.current();
        }
        intervalRef.current = setInterval(() => {
          savedCallback.current();
        }, interval);
      }
    };

    document.addEventListener("visibilitychange", handleVisibilityChange);
    return () => {
      document.removeEventListener("visibilitychange", handleVisibilityChange);
    };
  }, [interval, enabled, immediate, clearPolling]);

  return { clear: clearPolling };
}
