/**
 * Skipped by default (takes ~1 min). Run explicitly:
 *   RUN_BENCH=1 npx jest ansiPerf.bench
 *
 * Measures the streaming-apply render cost: naive (re-parse the whole accumulated log on every
 * 120ms flush, which is what `<Ansi>{outputLog}</Ansi>` did) vs incremental (parse each new line
 * once, which is what useAnsiLines' per-line cache does).
 */
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import AnsiImport from "ansi-to-react";
import { splitLines } from "../ansiChunks";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const Ansi: any = (AnsiImport as any).default ?? AnsiImport;

function makeLog(lines: number): string[] {
  const out: string[] = [];
  for (let i = 0; i < lines; i++) {
    out.push(`[32mmodule.example[${i}].aws_instance.node: Still creating... [${i * 10}s elapsed][0m`);
  }
  return out;
}

const TOTAL_LINES = 15000;
const LINES_PER_FLUSH = 40; // ~15k lines over a 5-min apply, flushed every 120ms

const bench = process.env.RUN_BENCH ? it : it.skip;

bench("incremental line parsing is far cheaper than full re-parse over a stream", () => {
  const lines = makeLog(TOTAL_LINES);
  const flushes = Math.ceil(TOTAL_LINES / LINES_PER_FLUSH);

  // naive: each flush re-parses the entire accumulated log
  let naiveChars = 0;
  const n0 = performance.now();
  for (let i = 0; i < lines.length; i += LINES_PER_FLUSH) {
    const acc = lines.slice(0, i + LINES_PER_FLUSH).join("\n");
    renderToStaticMarkup(createElement(Ansi, null, acc));
    naiveChars += acc.length;
  }
  const naiveMs = performance.now() - n0;

  // incremental: parse a line only the first time it appears
  const seen = new Set<string>();
  let incChars = 0;
  const i0 = performance.now();
  for (let i = 0; i < lines.length; i += LINES_PER_FLUSH) {
    const acc = lines.slice(0, i + LINES_PER_FLUSH).join("\n");
    for (const line of splitLines(acc)) {
      if (seen.has(line)) continue;
      seen.add(line);
      renderToStaticMarkup(createElement(Ansi, null, line || " "));
      incChars += line.length;
    }
  }
  const incMs = performance.now() - i0;

  console.log(
    `\nstreaming ${TOTAL_LINES} lines over ${flushes} flushes\n` +
      `  naive (full re-parse):  ${naiveMs.toFixed(0)} ms   ${(naiveChars / 1e6).toFixed(1)}M chars parsed\n` +
      `  incremental (per-line): ${incMs.toFixed(0)} ms   ${(incChars / 1e6).toFixed(1)}M chars parsed\n` +
      `  wall-clock speedup: ${(naiveMs / incMs).toFixed(1)}x   parse-work reduction: ${(naiveChars / incChars).toFixed(0)}x`
  );

  expect(incMs).toBeLessThan(naiveMs);
});
