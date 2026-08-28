import { useEffect, useState } from "react";
import { readEventStream } from "../modules/api/eventStream";

type UseEventStreamOptions<T> = {
  url: string;
  enabled: boolean;
  initial: T;
  reduce: (previous: T, data: string) => T;
  // When set, coalesces messages arriving within this window into a single React state
  // update instead of one per message - see useLogStream, which streams one console line per
  // SSE message and can otherwise re-render (and, for the terminal view, re-parse the whole
  // accumulated log through ansi-to-react) once per line during a chatty apply. Left unset,
  // behavior is identical to before this existed: one setValue per message.
  flushIntervalMs?: number;
  // Fired with { failed: true } once the reconnect backoff has grown to its ceiling (repeated
  // failures), and { failed: false } again on the next successful message. Lets a consumer fall
  // back to polling - see LiveTerminalOutput.
  onStatus?: (status: { failed: boolean }) => void;
};

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30000;

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

export function useEventStream<T>({ url, enabled, initial, reduce, flushIntervalMs, onStatus }: UseEventStreamOptions<T>): T {
  const [value, setValue] = useState<T>(initial);

  useEffect(() => {
    setValue(initial);

    if (!enabled) {
      return;
    }

    let cancelled = false;
    let retryTimeout: ReturnType<typeof setTimeout> | undefined;
    let backoffMs = INITIAL_BACKOFF_MS;
    let reportedFailed = false;
    let lastEventId: string | undefined;
    let controller = new AbortController();

    // currentValue always reflects the latest reduced value, updated synchronously on every
    // message regardless of batching - only how often it's copied into React state (setValue)
    // depends on flushIntervalMs. This keeps the accumulated value correct even across a burst
    // of messages that all land inside the same flush window.
    let currentValue: T = initial;
    let dirty = false;
    let flushTimeout: ReturnType<typeof setTimeout> | undefined;

    const flush = () => {
      if (flushTimeout != null) {
        clearTimeout(flushTimeout);
        flushTimeout = undefined;
      }
      if (!dirty) {
        return;
      }
      dirty = false;
      setValue(currentValue);
    };

    const connect = () => {
      controller = new AbortController();

      readEventStream(url, {
        signal: controller.signal,
        lastEventId,
        onMessage: (data, id) => {
          backoffMs = INITIAL_BACKOFF_MS;
          if (reportedFailed) {
            reportedFailed = false;
            onStatus?.({ failed: false });
          }
          if (id != null) {
            lastEventId = id;
          }

          currentValue = reduce(currentValue, data);

          if (flushIntervalMs == null) {
            setValue(currentValue);
            return;
          }

          dirty = true;
          if (flushTimeout == null) {
            flushTimeout = setTimeout(flush, flushIntervalMs);
          }
        },
      })
        .catch((error: unknown) => {
          if (cancelled || isAbortError(error)) {
            return;
          }
          retryTimeout = setTimeout(() => {
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            if (backoffMs >= MAX_BACKOFF_MS && !reportedFailed) {
              reportedFailed = true;
              onStatus?.({ failed: true });
            }
            connect();
          }, backoffMs);
        })
        .finally(() => {
          // Flush any partial batch immediately when the connection settles (success, error,
          // or abort) rather than leaving it stuck until the next message arrives - which, on a
          // transient error right before a reconnect, could otherwise be a long wait.
          flush();
        });
    };

    connect();

    return () => {
      cancelled = true;
      controller.abort();
      if (retryTimeout != null) {
        clearTimeout(retryTimeout);
      }
      if (flushTimeout != null) {
        clearTimeout(flushTimeout);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, enabled]);

  return value;
}
