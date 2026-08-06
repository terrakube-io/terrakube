import { useEventStream } from "./useEventStream";

type UseLogStreamOptions = {
  url: string;
  enabled: boolean;
};

export function useLogStream({ url, enabled }: UseLogStreamOptions): { text: string } {
  const text = useEventStream<string>({
    url,
    enabled,
    initial: "",
    reduce: (previous, data) => (previous.length === 0 ? data : `${previous}\n${data}`),
  });

  return { text };
}
