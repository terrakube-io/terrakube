import { renderHook, waitFor } from "@testing-library/react";
import { useStructuredOutputStream } from "../useStructuredOutputStream";

jest.mock("../../modules/api/eventStream", () => ({
  readEventStream: jest.fn(),
}));

import { readEventStream } from "../../modules/api/eventStream";

describe("useStructuredOutputStream", () => {
  afterEach(() => {
    jest.clearAllMocks();
  });

  it("replaces state with each pushed snapshot instead of merging", async () => {
    (readEventStream as jest.Mock).mockImplementation(async (_url, { onMessage }) => {
      onMessage(JSON.stringify({ changes: [{ address: "a" }] }), "1");
      onMessage(JSON.stringify({ changes: [{ address: "a" }, { address: "b" }] }), "2");
    });

    const { result } = renderHook(() =>
      useStructuredOutputStream<{ changes: { address: string }[] }>({
        url: "http://localhost/stream",
        enabled: true,
        initial: { changes: [] },
      })
    );

    await waitFor(() => expect(result.current.changes).toHaveLength(2));
    expect(result.current.changes.map((c) => c.address)).toEqual(["a", "b"]);
  });

  it("ignores an unparseable snapshot rather than throwing", async () => {
    (readEventStream as jest.Mock).mockImplementation(async (_url, { onMessage }) => {
      onMessage("not json", "1");
    });

    const { result } = renderHook(() =>
      useStructuredOutputStream<{ changes: unknown[] }>({
        url: "http://localhost/stream",
        enabled: true,
        initial: { changes: [] },
      })
    );

    await waitFor(() => expect(result.current).toEqual({ changes: [] }));
  });
});
