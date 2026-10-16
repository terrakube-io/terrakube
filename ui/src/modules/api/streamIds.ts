// Redis stream ids, "<millis>-<sequence>".
const STREAM_ID = /^(\d+)-(\d+)$/;

/** Orders two stream ids; undefined when either is not one. */
export function compareStreamIds(a: string, b: string): number | undefined {
  const left = STREAM_ID.exec(a);
  const right = STREAM_ID.exec(b);
  if (left == null || right == null) {
    return undefined;
  }
  const byMillis = Number(left[1]) - Number(right[1]);
  return byMillis !== 0 ? Math.sign(byMillis) : Math.sign(Number(left[2]) - Number(right[2]));
}
