import { useCallback, useEffect, useRef, useState } from "react";
import { fetchStepLog, StepLogFetchError, StepLogNotFoundError } from "../domain/Jobs/fetchStepLog";
import { stepLogCache } from "../domain/Jobs/stepLogCache";

export type StepLogState = "idle" | "loading" | "success" | "empty" | "error";

type UseStepLogParams = {
  stepId: string;
  output?: string;
  jobId: string;
  organizationId: string;
  enabled: boolean;
  isTerminal: boolean;
};

type UseStepLogResult = {
  state: StepLogState;
  text: string;
  truncated: boolean;
  retry: () => void;
};

// Longer than the API's own storage timeout so the client never bails before the server does.
const RETRY_DELAYS_MS = [2000, 4000];
const TAIL_BYTES = 256 * 1024;

function isAbortError(error: unknown): boolean {
  return error instanceof Error && (error.name === "AbortError" || error.name === "CanceledError");
}

export function useStepLog({
  stepId,
  output,
  jobId,
  organizationId,
  enabled,
  isTerminal,
}: UseStepLogParams): UseStepLogResult {
  const [state, setState] = useState<StepLogState>("idle");
  const [text, setText] = useState("");
  const [truncated, setTruncated] = useState(false);
  const [attempt, setAttempt] = useState(0);

  const requestRef = useRef(0);

  const run = useCallback(() => {
    if (!enabled) {
      return;
    }

    if (stepLogCache.has(stepId)) {
      setText(stepLogCache.get(stepId) ?? "");
      setTruncated(false);
      setState("success");
      return;
    }

    const requestId = ++requestRef.current;
    const controller = new AbortController();
    setState("loading");

    let cancelled = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;

    const attemptFetch = (tries: number) => {
      fetchStepLog({ output, jobId, organizationId, stepId, signal: controller.signal, tailBytes: TAIL_BYTES })
        .then((result) => {
          if (cancelled || requestId !== requestRef.current) {
            return;
          }
          if (result.text.length === 0) {
            setState("empty");
            return;
          }
          setText(result.text);
          setTruncated(result.truncated);
          setState("success");
          if (isTerminal && !result.truncated) {
            stepLogCache.set(stepId, result.text);
          }
        })
        .catch((error: unknown) => {
          if (cancelled || requestId !== requestRef.current || isAbortError(error)) {
            return;
          }
          // eslint-disable-next-line no-console
          console.warn("[stepLog]", stepId, error);

          if (error instanceof StepLogNotFoundError) {
            // A running step's log may simply not be archived yet.
            setState(isTerminal ? "error" : "empty");
            return;
          }

          if (error instanceof StepLogFetchError && tries < RETRY_DELAYS_MS.length) {
            retryTimer = setTimeout(() => attemptFetch(tries + 1), RETRY_DELAYS_MS[tries]);
            return;
          }

          setState("error");
        });
    };

    attemptFetch(0);

    return () => {
      cancelled = true;
      controller.abort();
      if (retryTimer != null) {
        clearTimeout(retryTimer);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, stepId, output, jobId, organizationId, isTerminal, attempt]);

  useEffect(() => {
    const teardown = run();
    return teardown;
  }, [run]);

  const retry = useCallback(() => {
    setAttempt((n) => n + 1);
  }, []);

  return { state, text, truncated, retry };
}
