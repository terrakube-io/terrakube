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

// Bounded by attempt count, not a wall-clock budget: 1 initial try + these backoff delays. Each
// delay is longer than the API's own storage timeout so the client never bails before the server.
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

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  useEffect(() => {
    if (!enabled) {
      setState("idle");
      return;
    }

    const cached = stepLogCache.get(stepId);
    if (cached != null) {
      setText(cached);
      setTruncated(false);
      setState("success");
      return;
    }

    const controller = new AbortController();
    let cancelled = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    setState("loading");

    const attemptFetch = (tries: number) => {
      fetchStepLog({ output, jobId, organizationId, stepId, signal: controller.signal, tailBytes: TAIL_BYTES })
        .then((result) => {
          if (cancelled) {
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
          if (cancelled || isAbortError(error)) {
            return;
          }
          console.warn("[stepLog]", stepId, error);

          if (error instanceof StepLogNotFoundError) {
            // A step that hasn't finished may simply have no archived log yet.
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
  }, [enabled, stepId, output, jobId, organizationId, isTerminal, attempt]);

  return { state, text, truncated, retry };
}
