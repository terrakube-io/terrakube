import { useEffect, useState } from "react";
import { readEventStream } from "../modules/api/eventStream";

type UseLogStreamOptions = {
  url: string;
  enabled: boolean;
};

export function useLogStream({ url, enabled }: UseLogStreamOptions): { text: string } {
  const [text, setText] = useState("");

  useEffect(() => {
    setText("");

    if (!enabled) {
      return;
    }

    const controller = new AbortController();

    readEventStream(url, {
      signal: controller.signal,
      onMessage: (data) => {
        setText((previous) => (previous.length === 0 ? data : `${previous}\n${data}`));
      },
    }).catch(() => {
      // Connection closed or aborted; the terminal falls back to whatever text was already accumulated.
    });

    return () => controller.abort();
  }, [url, enabled]);

  return { text };
}
