import { renderHook, waitFor } from "@testing-library/react";
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
});
