import getUserFromStorage from "../../config/authUser";

type ReadEventStreamOptions = {
  onMessage: (data: string, id?: string) => void;
  signal: AbortSignal;
  lastEventId?: string;
};

export async function readEventStream(
  url: string,
  { onMessage, signal, lastEventId }: ReadEventStreamOptions
): Promise<void> {
  const user = getUserFromStorage();

  const headers: Record<string, string> = {
    Authorization: `Bearer ${user?.access_token ?? ""}`,
  };
  if (lastEventId != null) {
    headers["Last-Event-ID"] = lastEventId;
  }

  const response = await fetch(url, { signal, headers });

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
      const lines = event.split("\n");
      const dataLines = lines.filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trimStart());
      const idLine = lines.find((line) => line.startsWith("id:"));
      const id = idLine != null ? idLine.slice(3).trimStart() : undefined;

      if (dataLines.length > 0) {
        onMessage(dataLines.join("\n"), id);
      }
    }
  }
}
