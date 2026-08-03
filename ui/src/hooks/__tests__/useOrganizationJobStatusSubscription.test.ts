import { renderHook } from "@testing-library/react";
import { useOrganizationJobStatusSubscription } from "../useOrganizationJobStatusSubscription";
import { getSubscriptionClient } from "../../modules/api/subscriptionClient";

jest.mock("../../modules/api/subscriptionClient");

describe("useOrganizationJobStatusSubscription", () => {
  const unsubscribe = jest.fn();
  const subscribe = jest.fn().mockReturnValue(unsubscribe);

  beforeEach(() => {
    jest.resetAllMocks();
    subscribe.mockReturnValue(unsubscribe);
    (getSubscriptionClient as jest.Mock).mockReturnValue({ subscribe });
  });

  it("subscribes with the organizationId variable and forwards each event", () => {
    const onEvent = jest.fn();

    renderHook(() => useOrganizationJobStatusSubscription({ organizationId: "org-1", enabled: true, onEvent }));

    expect(subscribe).toHaveBeenCalledTimes(1);
    const [payload, sink] = subscribe.mock.calls[0];
    expect(payload.variables).toEqual({ organizationId: "org-1" });

    sink.next({ data: { organizationJobStatusChanged: { jobId: 1, workspaceId: "workspace-1", status: "running" } } });

    expect(onEvent).toHaveBeenCalledWith({ jobId: 1, workspaceId: "workspace-1", status: "running" });
  });

  it("does not subscribe when disabled", () => {
    renderHook(() =>
      useOrganizationJobStatusSubscription({ organizationId: "org-1", enabled: false, onEvent: jest.fn() })
    );

    expect(subscribe).not.toHaveBeenCalled();
  });

  it("unsubscribes on unmount", () => {
    const { unmount } = renderHook(() =>
      useOrganizationJobStatusSubscription({ organizationId: "org-1", enabled: true, onEvent: jest.fn() })
    );

    unmount();

    expect(unsubscribe).toHaveBeenCalledTimes(1);
  });

  it("does not resubscribe when the caller passes a new onEvent reference on every render", () => {
    const { rerender } = renderHook(
      ({ onEvent }) => useOrganizationJobStatusSubscription({ organizationId: "org-1", enabled: true, onEvent }),
      { initialProps: { onEvent: () => {} } }
    );

    expect(subscribe).toHaveBeenCalledTimes(1);

    rerender({ onEvent: () => {} });
    rerender({ onEvent: () => {} });

    expect(subscribe).toHaveBeenCalledTimes(1);
    expect(unsubscribe).not.toHaveBeenCalled();
  });
});
