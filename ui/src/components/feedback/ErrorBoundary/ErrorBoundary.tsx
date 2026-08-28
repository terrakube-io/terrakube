import React from "react";
import { ErrorState } from "@/components/feedback/ErrorState";

type Props = {
  children: React.ReactNode;
  catchGlobal?: boolean;
};

type State = {
  error: Error | null;
};

const IGNORED_GLOBAL_ERRORS = [/ResizeObserver loop/i, /^AbortError$/i, /^CanceledError$/i];

const toError = (reason: unknown): Error => (reason instanceof Error ? reason : new Error(String(reason)));

const isIgnored = (error: Error) =>
  IGNORED_GLOBAL_ERRORS.some((pattern) => pattern.test(error.message) || pattern.test(error.name));

export default class ErrorBoundary extends React.Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error("Unhandled render error:", error, info.componentStack);
  }

  componentDidMount() {
    if (this.props.catchGlobal) {
      window.addEventListener("error", this.handleWindowError);
      window.addEventListener("unhandledrejection", this.handleRejection);
    }
  }

  componentWillUnmount() {
    if (this.props.catchGlobal) {
      window.removeEventListener("error", this.handleWindowError);
      window.removeEventListener("unhandledrejection", this.handleRejection);
    }
  }

  handleWindowError = (event: ErrorEvent) => {
    const error = event.error instanceof Error ? event.error : new Error(event.message);
    if (!isIgnored(error)) {
      this.setState({ error });
    }
  };

  handleRejection = (event: PromiseRejectionEvent) => {
    const error = toError(event.reason);
    if (!isIgnored(error)) {
      this.setState({ error });
    }
  };

  handleRetry = () => {
    window.location.reload();
  };

  render() {
    if (this.state.error) {
      return (
        <ErrorState
          title="Something went wrong"
          message={this.state.error.message}
          onRetry={this.handleRetry}
          showHomeLink={false}
        />
      );
    }

    return this.props.children;
  }
}
