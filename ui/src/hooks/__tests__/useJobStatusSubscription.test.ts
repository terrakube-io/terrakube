import { renderHook } from "@testing-library/react";
import { useJobStatusSubscription } from "../useJobStatusSubscription";
import { getSubscriptionClient } from "../../modules/api/subscriptionClient";

jest.mock("../../modules/api/subscriptionClient");

describe("useJobStatusSubscription", () => {
  const unsubscribe = jest.fn();
  const subscribe = jest.fn().mockReturnValue(unsubscribe);

  beforeEach(() => {
    jest.resetAllMocks();
    subscribe.mockReturnValue(unsubscribe);
    (getSubscriptionClient as jest.Mock).mockReturnValue({ subscribe });
  });

  it("subscribes with the workspaceId variable and forwards each event", () => {
    const onEvent = jest.fn();

    renderHook(() => useJobStatusSubscription({ workspaceId: "workspace-1", enabled: true, onEvent }));

    expect(subscribe).toHaveBeenCalledTimes(1);
    const [payload, sink] = subscribe.mock.calls[0];
    expect(payload.variables).toEqual({ workspaceId: "workspace-1" });

    sink.next({ data: { jobStatusChanged: { jobId: 1, workspaceId: "workspace-1", status: "running" } } });

    expect(onEvent).toHaveBeenCalledWith({ jobId: 1, workspaceId: "workspace-1", status: "running" });
  });

  it("does not subscribe when disabled", () => {
    renderHook(() => useJobStatusSubscription({ workspaceId: "workspace-1", enabled: false, onEvent: jest.fn() }));

    expect(subscribe).not.toHaveBeenCalled();
  });

  it("unsubscribes on unmount", () => {
    const { unmount } = renderHook(() =>
      useJobStatusSubscription({ workspaceId: "workspace-1", enabled: true, onEvent: jest.fn() })
    );

    unmount();

    expect(unsubscribe).toHaveBeenCalledTimes(1);
  });

  it("does not resubscribe when the caller passes a new onEvent reference on every render", () => {
    // Regression test: callers commonly pass an inline callback (e.g. `() => loadWorkspace(...)`), which
    // is a new function reference every render. Re-subscribing every render would tear down and reopen
    // the WebSocket subscription constantly - the same class of bug the SSE log-streaming hook had before
    // its own fix, just for a different transport.
    const { rerender } = renderHook(
      ({ onEvent }) => useJobStatusSubscription({ workspaceId: "workspace-1", enabled: true, onEvent }),
      { initialProps: { onEvent: () => {} } }
    );

    expect(subscribe).toHaveBeenCalledTimes(1);

    rerender({ onEvent: () => {} });
    rerender({ onEvent: () => {} });

    expect(subscribe).toHaveBeenCalledTimes(1);
    expect(unsubscribe).not.toHaveBeenCalled();
  });

  it("calls the latest onEvent even after a re-render passed a new reference", () => {
    const firstOnEvent = jest.fn();
    const secondOnEvent = jest.fn();

    const { rerender } = renderHook(
      ({ onEvent }) => useJobStatusSubscription({ workspaceId: "workspace-1", enabled: true, onEvent }),
      { initialProps: { onEvent: firstOnEvent } }
    );

    rerender({ onEvent: secondOnEvent });

    const [, sink] = subscribe.mock.calls[0];
    sink.next({ data: { jobStatusChanged: { jobId: 1, workspaceId: "workspace-1", status: "running" } } });

    expect(secondOnEvent).toHaveBeenCalledWith({ jobId: 1, workspaceId: "workspace-1", status: "running" });
    expect(firstOnEvent).not.toHaveBeenCalled();
  });
});
