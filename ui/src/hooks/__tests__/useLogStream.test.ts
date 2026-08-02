import { renderHook, waitFor } from "@testing-library/react";
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
});
