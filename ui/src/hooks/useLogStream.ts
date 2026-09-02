import { useEventStream } from "./useEventStream";

type UseLogStreamOptions = {
  url: string;
  enabled: boolean;
  // Fired { failed: true } when the SSE stream has been failing to reconnect for a while, and
  // { failed: false } once it recovers - LiveTerminalOutput uses this to switch to plain-GET
  // polling. Pass a stable reference.
  onStatus?: (status: { failed: boolean }) => void;
};

// A chatty apply can stream one SSE message per console line; without batching, each one
// triggers its own React render, and the terminal view re-parses the *entire* accumulated log
// through ansi-to-react on every single one. 120ms is short enough that a human reading the
// scrolling log never notices the delay, while capping re-renders to ~8/s regardless of how
// fast lines actually arrive.
const LOG_FLUSH_INTERVAL_MS = 120;

export function useLogStream({ url, enabled, onStatus }: UseLogStreamOptions): { text: string } {
  const text = useEventStream<string>({
    url,
    enabled,
    initial: "",
    reduce: (previous, data) => (previous.length === 0 ? data : `${previous}\n${data}`),
    flushIntervalMs: LOG_FLUSH_INTERVAL_MS,
    onStatus,
  });

  return { text };
}
