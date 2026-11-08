import { Spin } from "antd";
import { lazy, Suspense } from "react";
import ErrorBoundary from "@/components/feedback/ErrorBoundary/ErrorBoundary";
import type { Props } from "./CodeEditor";

// Monaco and its workers are over 1 MB gzip; load them only when an editor is on screen.
const LazyCodeEditor = lazy(() => import("./CodeEditor"));

export function CodeEditor(props: Props) {
  return (
    <ErrorBoundary>
      <Suspense
        fallback={
          <div className="code-editor">
            <Spin />
          </div>
        }
      >
        <LazyCodeEditor {...props} />
      </Suspense>
    </ErrorBoundary>
  );
}
