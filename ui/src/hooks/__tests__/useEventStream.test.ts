import { act, renderHook, waitFor } from "@testing-library/react";
import { useEventStream } from "../useEventStream";

jest.mock("../../modules/api/eventStream", () => ({
  readEventStream: jest.fn(),
}));

import { readEventStream } from "../../modules/api/eventStream";

describe("useEventStream", () => {
  afterEach(() => {
    jest.clearAllMocks();
  });

  it("folds incoming messages through the reduce function, starting from initial", async () => {
    (readEventStream as jest.Mock).mockImplementation(async (_url, { onMessage }) => {
      onMessage("a", "1");
      onMessage("b", "2");
    });

    const { result } = renderHook(() =>
      useEventStream<string[]>({
        url: "http://localhost/stream",
        enabled: true,
        initial: [],
        reduce: (previous, data) => [...previous, data],
      })
    );

    await waitFor(() => expect(result.current).toEqual(["a", "b"]));
  });

  it("resets to initial when disabled, and never connects", () => {
    const { result } = renderHook(() =>
      useEventStream<string[]>({
        url: "http://localhost/stream",
        enabled: false,
        initial: [],
        reduce: (previous, data) => [...previous, data],
      })
    );

    expect(result.current).toEqual([]);
    expect(readEventStream).not.toHaveBeenCalled();
  });

  describe("flushIntervalMs batching", () => {
    beforeEach(() => {
      jest.useFakeTimers();
    });

    afterEach(() => {
      jest.useRealTimers();
    });

    it("coalesces messages that arrive within the flush window into a single update", async () => {
      let renderCount = 0;
      (readEventStream as jest.Mock).mockImplementation(async (_url, { onMessage }) => {
        onMessage("a");
        onMessage("b");
        onMessage("c");
        return new Promise(() => {}); // keep the stream "open" so .finally() doesn't flush early
      });

      const { result } = renderHook(() => {
        renderCount++;
        return useEventStream<string[]>({
          url: "http://localhost/stream",
          enabled: true,
          initial: [],
          reduce: (previous, data) => [...previous, data],
          flushIntervalMs: 100,
        });
      });

      // All three messages landed synchronously within the mock, before the flush timer fired -
      // state (and therefore render count) must still reflect none of them yet.
      expect(result.current).toEqual([]);
      const renderCountBeforeFlush = renderCount;

      await act(async () => {
        await jest.advanceTimersByTimeAsync(100);
      });

      expect(result.current).toEqual(["a", "b", "c"]);
      expect(renderCount).toBe(renderCountBeforeFlush + 1);
    });

    it("flushes a pending batch immediately once the connection settles, even before the flush window elapses", async () => {
      (readEventStream as jest.Mock).mockImplementation(async (_url, { onMessage }) => {
        onMessage("a");
      });

      const { result } = renderHook(() =>
        useEventStream<string[]>({
          url: "http://localhost/stream",
          enabled: true,
          initial: [],
          reduce: (previous, data) => [...previous, data],
          flushIntervalMs: 5000,
        })
      );

      await act(async () => {
        await jest.advanceTimersByTimeAsync(0);
      });

      expect(result.current).toEqual(["a"]);
    });

    it("behaves exactly like the unbatched path when flushIntervalMs is unset", async () => {
      (readEventStream as jest.Mock).mockImplementation(async (_url, { onMessage }) => {
        onMessage("a");
        onMessage("b");
      });

      const { result } = renderHook(() =>
        useEventStream<string[]>({
          url: "http://localhost/stream",
          enabled: true,
          initial: [],
          reduce: (previous, data) => [...previous, data],
        })
      );

      await act(async () => {
        await jest.advanceTimersByTimeAsync(0);
      });

      expect(result.current).toEqual(["a", "b"]);
    });
  });
});
