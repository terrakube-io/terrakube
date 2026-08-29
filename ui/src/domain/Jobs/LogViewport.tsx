import { ReactNode, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";

type LogViewportProps = {
  lines: ReactNode[];
  followTail: boolean;
  onFollowChange?: (following: boolean) => void;
  lineHeight?: number;
  overscan?: number;
  className?: string;
};

const DEFAULT_LINE_HEIGHT = 18;
const DEFAULT_OVERSCAN = 20;

/**
 * Fixed-line-height windowed renderer: only the lines in (or near) the viewport are mounted, so a
 * 15,000-line log renders ~60 DOM nodes. When `followTail` is set the view stays pinned to the
 * bottom; scrolling up releases the pin (reported via `onFollowChange`), scrolling back to the
 * bottom re-engages it.
 */
export function LogViewport({
  lines,
  followTail,
  onFollowChange,
  lineHeight = DEFAULT_LINE_HEIGHT,
  overscan = DEFAULT_OVERSCAN,
  className,
}: LogViewportProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewportHeight, setViewportHeight] = useState(0);

  const total = lines.length;
  const totalHeight = total * lineHeight;

  const measure = useCallback(() => {
    const el = containerRef.current;
    if (el) {
      setViewportHeight(el.clientHeight);
      setScrollTop(el.scrollTop);
    }
  }, []);

  useLayoutEffect(() => {
    measure();
  }, [measure]);

  useEffect(() => {
    const el = containerRef.current;
    if (!el || typeof ResizeObserver === "undefined") {
      return;
    }
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [measure]);

  // Pin to the bottom while following. Runs after the lines change so new output stays visible.
  useLayoutEffect(() => {
    const el = containerRef.current;
    if (el && followTail) {
      el.scrollTop = el.scrollHeight;
      setScrollTop(el.scrollTop);
    }
  }, [lines, followTail]);

  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) {
      return;
    }
    setScrollTop(el.scrollTop);
    if (onFollowChange) {
      const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < lineHeight;
      onFollowChange(atBottom);
    }
  }, [lineHeight, onFollowChange]);

  const start = Math.max(0, Math.floor(scrollTop / lineHeight) - overscan);
  const visibleCount = Math.ceil((viewportHeight || 400) / lineHeight) + overscan * 2;
  const end = Math.min(total, start + visibleCount);
  const visible = lines.slice(start, end);

  return (
    <div ref={containerRef} className={className} onScroll={handleScroll} style={{ overflow: "auto" }}>
      <div style={{ height: totalHeight, position: "relative" }}>
        <div style={{ position: "absolute", top: start * lineHeight, left: 0, right: 0 }}>
          {visible.map((line, i) => (
            <div key={start + i} style={{ height: lineHeight, lineHeight: `${lineHeight}px` }}>
              {line}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
