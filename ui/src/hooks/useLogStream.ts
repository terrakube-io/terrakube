import { useEffect, useState } from "react";
import { readEventStream } from "../modules/api/eventStream";

type UseLogStreamOptions = {
  url: string;
  enabled: boolean;
};

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30000;

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

export function useLogStream({ url, enabled }: UseLogStreamOptions): { text: string } {
  const [text, setText] = useState("");

  useEffect(() => {
    setText("");

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
          setText((previous) => (previous.length === 0 ? data : `${previous}\n${data}`));
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
  }, [url, enabled]);

  return { text };
}
