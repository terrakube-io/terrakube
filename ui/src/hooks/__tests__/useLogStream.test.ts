import { act, renderHook, waitFor } from "@testing-library/react";
import { useLogStream } from "../useLogStream";
import { readEventStream } from "../../modules/api/eventStream";

jest.mock("../../modules/api/eventStream");

describe("useLogStream", () => {
  beforeEach(() => {
    jest.resetAllMocks();
  });

  it("accumulates messages emitted by readEventStream", async () => {
    (readEventStream as jest.Mock).mockImplementation(async (_url, { onMessage }) => {
      onMessage("line 1");
      onMessage("line 2");
    });

    const { result } = renderHook(() => useLogStream({ url: "http://localhost/stream", enabled: true }));

    await waitFor(() => expect(result.current.text).toBe("line 1\nline 2"));
  });

  it("does not connect when enabled is false", () => {
    renderHook(() => useLogStream({ url: "http://localhost/stream", enabled: false }));

    expect(readEventStream).not.toHaveBeenCalled();
  });

  it("resets accumulated text when the url changes", async () => {
    (readEventStream as jest.Mock).mockImplementation(async (_url, { onMessage }) => {
      onMessage("line 1");
    });

    const { result, rerender } = renderHook(({ url }) => useLogStream({ url, enabled: true }), {
      initialProps: { url: "http://localhost/stream/a" },
    });

    await waitFor(() => expect(result.current.text).toBe("line 1"));

    rerender({ url: "http://localhost/stream/b" });

    await waitFor(() => expect(result.current.text).toBe("line 1"));
  });

  describe("reconnect behavior", () => {
    beforeEach(() => {
      jest.useFakeTimers();
    });

    afterEach(() => {
      jest.useRealTimers();
    });

    it("resumes from the last received id after a transient error, keeping accumulated text", async () => {
      const mock = readEventStream as jest.Mock;
      mock.mockImplementationOnce(async (_url, { onMessage }) => {
        onMessage("line 1", "10-0");
        throw new Error("network drop");
      });
      mock.mockImplementationOnce(() => new Promise(() => {}));

      const { result } = renderHook(() => useLogStream({ url: "http://localhost/stream", enabled: true }));

      // useLogStream batches messages behind a flush timer (see LOG_FLUSH_INTERVAL_MS), but the
      // batch still needs to reach state before the transient error below sends it into a
      // multi-second reconnect backoff - useEventStream flushes any pending batch as soon as the
      // connection settles (success, error, or abort), which is what makes "line 1" visible here
      // at 0ms rather than only after the flush timer would otherwise have fired.
      await act(async () => {
        await jest.advanceTimersByTimeAsync(0);
      });
      expect(result.current.text).toBe("line 1");

      await act(async () => {
        await jest.advanceTimersByTimeAsync(1000);
      });

      expect(mock).toHaveBeenCalledTimes(2);
      expect(mock.mock.calls[1][1]).toEqual(expect.objectContaining({ lastEventId: "10-0" }));
      expect(result.current.text).toBe("line 1");
    });

    it("does not reconnect once the effect has been cleaned up", async () => {
      const mock = readEventStream as jest.Mock;
      mock.mockImplementationOnce(async () => {
        throw new Error("network drop");
      });

      const { unmount } = renderHook(() => useLogStream({ url: "http://localhost/stream", enabled: true }));

      await act(async () => {
        await jest.advanceTimersByTimeAsync(0);
      });
      unmount();

      await act(async () => {
        await jest.advanceTimersByTimeAsync(30000);
      });

      expect(mock).toHaveBeenCalledTimes(1);
    });

    it("does not reconnect when the stream ends without an error", async () => {
      const mock = readEventStream as jest.Mock;
      mock.mockResolvedValueOnce(undefined);

      renderHook(() => useLogStream({ url: "http://localhost/stream", enabled: true }));

      await act(async () => {
        await jest.advanceTimersByTimeAsync(30000);
      });

      expect(mock).toHaveBeenCalledTimes(1);
    });
  });
});
