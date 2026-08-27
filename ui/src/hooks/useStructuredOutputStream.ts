import { useEventStream } from "./useEventStream";

type UseStructuredOutputStreamOptions<T> = {
  url: string;
  enabled: boolean;
  initial: T;
};

export function useStructuredOutputStream<T>({ url, enabled, initial }: UseStructuredOutputStreamOptions<T>): T {
  return useEventStream<T>({
    url,
    enabled,
    initial,
    reduce: (previous, data) => {
      try {
        return JSON.parse(data) as T;
      } catch {
        return previous;
      }
    },
  });
}
