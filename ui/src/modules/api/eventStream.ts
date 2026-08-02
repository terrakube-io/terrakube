import getUserFromStorage from "../../config/authUser";

type ReadEventStreamOptions = {
  onMessage: (data: string) => void;
  signal: AbortSignal;
};

export async function readEventStream(url: string, { onMessage, signal }: ReadEventStreamOptions): Promise<void> {
  const user = getUserFromStorage();

  const response = await fetch(url, {
    signal,
    headers: {
      Authorization: `Bearer ${user?.access_token ?? ""}`,
    },
  });

  if (!response.ok || !response.body) {
    throw new Error(`Event stream request failed with status ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  for (;;) {
    const { value, done } = await reader.read();
    if (done) {
      return;
    }

    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split("\n\n");
    buffer = events.pop() ?? "";

    for (const event of events) {
      const dataLines = event
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart());

      if (dataLines.length > 0) {
        onMessage(dataLines.join("\n"));
      }
    }
  }
}
