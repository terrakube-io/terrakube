import { createElement, Fragment, ReactElement, ReactNode, useMemo } from "react";
import AnsiImport from "ansi-to-react";

// ansi-to-react ships both a CJS default and an ESM interop wrapper depending on the bundler.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const Ansi: any = (AnsiImport as any).default ?? AnsiImport;

/**
 * Split a log blob into lines. `text.split("\n")` already keeps a trailing empty element only when
 * the text ends with a newline, which keeps line identity stable as a stream appends.
 */
export function splitLines(text: string): string[] {
  return text.split("\n");
}

// One rendered line per unique line string. Reusing the element reference for an unchanged line lets
// React skip re-rendering (and ansi-to-react skip re-parsing) it when the surrounding log grows.
const lineCache = new Map<string, ReactElement>();
const MAX_CACHED_LINES = 50_000;

function renderLine(line: string): ReactElement {
  const cached = lineCache.get(line);
  if (cached != null) {
    return cached;
  }
  const element = createElement(
    "span",
    { className: "tf-log-line" },
    createElement(Ansi, null, line.length === 0 ? " " : line)
  );
  if (lineCache.size >= MAX_CACHED_LINES) {
    const oldest = lineCache.keys().next().value;
    if (oldest !== undefined) {
      lineCache.delete(oldest);
    }
  }
  lineCache.set(line, element);
  return element;
}

/** Test-only: reset the module-level line cache. */
export function __clearAnsiLineCache(): void {
  lineCache.clear();
}

/**
 * Memoized per-line rendering of an ANSI log. Unchanged lines keep their cached element reference
 * across appends, so only the newly-added lines are ever parsed - the fix for the O(n^2) cost of
 * re-parsing the whole accumulated log on every streaming flush.
 */
export function useAnsiLines(text: string): ReactNode[] {
  return useMemo(
    () => splitLines(text).map((line, index) => createElement(Fragment, { key: index }, renderLine(line))),
    [text]
  );
}
