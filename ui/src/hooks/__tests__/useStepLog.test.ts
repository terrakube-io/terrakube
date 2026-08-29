import { act, renderHook, waitFor } from "@testing-library/react";

class StepLogNotFoundError extends Error {
  constructor() {
    super("not found");
    this.name = "StepLogNotFoundError";
  }
}
class StepLogFetchError extends Error {
  status?: number;
  constructor(status?: number) {
    super("fetch error");
    this.name = "StepLogFetchError";
    this.status = status;
  }
}

const mockFetch = jest.fn();

jest.mock("../../domain/Jobs/fetchStepLog", () => ({
  __esModule: true,
  fetchStepLog: (...args: unknown[]) => mockFetch(...args),
  StepLogNotFoundError,
  StepLogFetchError,
}));

import { useStepLog } from "../useStepLog";
import { stepLogCache } from "../../domain/Jobs/stepLogCache";

const params = (over: Partial<Parameters<typeof useStepLog>[0]> = {}) => ({
  stepId: "s1",
  output: "https://api/tfoutput/v1/organization/o/job/j/step/s1",
  jobId: "j",
  organizationId: "o",
  enabled: true,
  isTerminal: true,
  ...over,
});

beforeEach(() => {
  jest.clearAllMocks();
  stepLogCache.clear();
  jest.spyOn(console, "warn").mockImplementation(() => {});
});

afterEach(() => {
  jest.useRealTimers();
});

it("stays idle and does not fetch when disabled", () => {
  const { result } = renderHook(() => useStepLog(params({ enabled: false })));

  expect(result.current.state).toBe("idle");
  expect(mockFetch).not.toHaveBeenCalled();
});

it("serves a cached terminal log immediately without fetching", () => {
  stepLogCache.set("s1", "cached body");

  const { result } = renderHook(() => useStepLog(params()));

  expect(result.current.state).toBe("success");
  expect(result.current.text).toBe("cached body");
  expect(mockFetch).not.toHaveBeenCalled();
});

it("loads, succeeds, and caches a terminal step's log", async () => {
  mockFetch.mockResolvedValue({ text: "the log", truncated: false });

  const { result } = renderHook(() => useStepLog(params()));

  await waitFor(() => expect(result.current.state).toBe("success"));
  expect(result.current.text).toBe("the log");
  expect(stepLogCache.get("s1")).toBe("the log");
});

it("does not cache a truncated (tailed) log", async () => {
  mockFetch.mockResolvedValue({ text: "tail only", truncated: true });

  const { result } = renderHook(() => useStepLog(params()));

  await waitFor(() => expect(result.current.state).toBe("success"));
  expect(stepLogCache.has("s1")).toBe(false);
});

it("reports an empty body as the empty state", async () => {
  mockFetch.mockResolvedValue({ text: "", truncated: false });

  const { result } = renderHook(() => useStepLog(params()));

  await waitFor(() => expect(result.current.state).toBe("empty"));
});

it("treats a 404 on a non-terminal step as empty", async () => {
  mockFetch.mockRejectedValue(new StepLogNotFoundError());

  const { result } = renderHook(() => useStepLog(params({ isTerminal: false })));

  await waitFor(() => expect(result.current.state).toBe("empty"));
});

it("treats a 404 on a terminal step as an error", async () => {
  mockFetch.mockRejectedValue(new StepLogNotFoundError());

  const { result } = renderHook(() => useStepLog(params({ isTerminal: true })));

  await waitFor(() => expect(result.current.state).toBe("error"));
});

it("retries a transient fetch error with backoff then succeeds", async () => {
  jest.useFakeTimers();
  mockFetch
    .mockRejectedValueOnce(new StepLogFetchError(502))
    .mockResolvedValueOnce({ text: "recovered", truncated: false });

  const { result } = renderHook(() => useStepLog(params()));

  await act(async () => {
    await Promise.resolve();
  });
  expect(result.current.state).toBe("loading");

  await act(async () => {
    jest.advanceTimersByTime(2000);
    await Promise.resolve();
    await Promise.resolve();
  });

  expect(result.current.state).toBe("success");
  expect(result.current.text).toBe("recovered");
});

it("ends in error after the retry ladder is exhausted", async () => {
  jest.useFakeTimers();
  mockFetch.mockRejectedValue(new StepLogFetchError(502));

  const { result } = renderHook(() => useStepLog(params()));

  await act(async () => {
    await Promise.resolve();
  });
  await act(async () => {
    jest.advanceTimersByTime(2000);
    await Promise.resolve();
    await Promise.resolve();
  });
  await act(async () => {
    jest.advanceTimersByTime(4000);
    await Promise.resolve();
    await Promise.resolve();
  });

  expect(result.current.state).toBe("error");
  expect(mockFetch).toHaveBeenCalledTimes(3);
});

it("retry() from the error state re-runs the fetch", async () => {
  mockFetch.mockRejectedValue(new StepLogNotFoundError());

  const { result } = renderHook(() => useStepLog(params({ isTerminal: true })));
  await waitFor(() => expect(result.current.state).toBe("error"));

  mockFetch.mockResolvedValueOnce({ text: "second try", truncated: false });
  act(() => result.current.retry());

  await waitFor(() => expect(result.current.state).toBe("success"));
  expect(result.current.text).toBe("second try");
});
