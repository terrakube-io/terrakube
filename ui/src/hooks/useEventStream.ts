import { useEffect, useState } from "react";
import { readEventStream } from "../modules/api/eventStream";

type UseEventStreamOptions<T> = {
  url: string;
  enabled: boolean;
  initial: T;
  reduce: (previous: T, data: string) => T;
};

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30000;

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

export function useEventStream<T>({ url, enabled, initial, reduce }: UseEventStreamOptions<T>): T {
  const [value, setValue] = useState<T>(initial);

  useEffect(() => {
    setValue(initial);

    if (!enabled) {
      return;
    }

    let cancelled = false;
    let retryTimeout: ReturnType<typeof setTimeout> | undefined;
    let backoffMs = INITIAL_BACKOFF_MS;
    let lastEventId: string | undefined;
    let controller = new AbortController();

    const connect = () => {
      controller = new AbortController();

      readEventStream(url, {
        signal: controller.signal,
        lastEventId,
        onMessage: (data, id) => {
          backoffMs = INITIAL_BACKOFF_MS;
          if (id != null) {
            lastEventId = id;
          }
          setValue((previous) => reduce(previous, data));
        },
      }).catch((error: unknown) => {
        if (cancelled || isAbortError(error)) {
          return;
        }
        retryTimeout = setTimeout(() => {
          backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
          connect();
        }, backoffMs);
      });
    };

    connect();

    return () => {
      cancelled = true;
      controller.abort();
      if (retryTimeout != null) {
        clearTimeout(retryTimeout);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, enabled]);

  return value;
}
