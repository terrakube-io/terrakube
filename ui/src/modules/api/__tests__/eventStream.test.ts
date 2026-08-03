import { readEventStream } from "../eventStream";

function makeStreamResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  let index = 0;

  const reader = {
    read: async () => {
      if (index < chunks.length) {
        const value = encoder.encode(chunks[index]);
        index++;
        return { value, done: false };
      }
      return { value: undefined, done: true };
    },
  };

  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => reader,
    },
  } as unknown as Response;
}

describe("readEventStream", () => {
  it("invokes onMessage for each SSE data line", async () => {
    const response = makeStreamResponse(["data: line 1\n\n", "data: line 2\n\n"]);
    (global.fetch as jest.Mock) = jest.fn().mockResolvedValue(response);

    const received: string[] = [];
    await readEventStream("http://localhost/stream", {
      onMessage: (data) => received.push(data),
      signal: new AbortController().signal,
    });

    expect(received).toEqual(["line 1", "line 2"]);
  });

  it("ignores SSE comment lines", async () => {
    const response = makeStreamResponse([": heartbeat\n\n", "data: line 1\n\n"]);
    (global.fetch as jest.Mock) = jest.fn().mockResolvedValue(response);

    const received: string[] = [];
    await readEventStream("http://localhost/stream", {
      onMessage: (data) => received.push(data),
      signal: new AbortController().signal,
    });

    expect(received).toEqual(["line 1"]);
  });

  it("rejects when the response is not ok", async () => {
    const response = { ok: false, status: 401, body: null } as unknown as Response;
    (global.fetch as jest.Mock) = jest.fn().mockResolvedValue(response);

    await expect(
      readEventStream("http://localhost/stream", {
        onMessage: jest.fn(),
        signal: new AbortController().signal,
      })
    ).rejects.toThrow("401");
  });

  it("passes the event's id alongside its data", async () => {
    const response = makeStreamResponse(["id: 100-0\ndata: line 1\n\n"]);
    (global.fetch as jest.Mock) = jest.fn().mockResolvedValue(response);

    const received: Array<[string, string | undefined]> = [];
    await readEventStream("http://localhost/stream", {
      onMessage: (data, id) => received.push([data, id]),
      signal: new AbortController().signal,
    });

    expect(received).toEqual([["line 1", "100-0"]]);
  });

  it("sends Last-Event-ID as a request header when provided", async () => {
    const response = makeStreamResponse(["data: line 1\n\n"]);
    const fetchMock = jest.fn().mockResolvedValue(response);
    (global.fetch as jest.Mock) = fetchMock;

    await readEventStream("http://localhost/stream", {
      onMessage: jest.fn(),
      signal: new AbortController().signal,
      lastEventId: "100-0",
    });

    const [, requestInit] = fetchMock.mock.calls[0];
    expect(requestInit.headers["Last-Event-ID"]).toBe("100-0");
  });
});
