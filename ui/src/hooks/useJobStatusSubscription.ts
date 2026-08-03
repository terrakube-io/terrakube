import { useEffect, useRef } from "react";
import { getSubscriptionClient } from "../modules/api/subscriptionClient";

export type JobStatusEvent = {
  jobId: number;
  workspaceId: string;
  status: string;
};

type UseJobStatusSubscriptionOptions = {
  workspaceId: string;
  enabled: boolean;
  onEvent: (event: JobStatusEvent) => void;
};

const SUBSCRIPTION_QUERY = `
  subscription OnJobStatusChanged($workspaceId: ID!) {
    jobStatusChanged(workspaceId: $workspaceId) {
      jobId
      workspaceId
      status
    }
  }
`;

export function useJobStatusSubscription({ workspaceId, enabled, onEvent }: UseJobStatusSubscriptionOptions): void {
  // Callers commonly pass an inline callback that closes over component state (e.g. `() =>
  // loadWorkspace(...)`), which is a new function reference every render. Reading it through a ref -
  // updated on every render, but not part of the effect's dependency array - means the subscription
  // itself isn't torn down and recreated just because the caller re-rendered.
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  useEffect(() => {
    if (!enabled) {
      return;
    }

    const unsubscribe = getSubscriptionClient().subscribe(
      { query: SUBSCRIPTION_QUERY, variables: { workspaceId } },
      {
        next: (result) => {
          const event = (result.data as { jobStatusChanged?: JobStatusEvent } | null | undefined)?.jobStatusChanged;
          if (event != null) {
            onEventRef.current(event);
          }
        },
        error: () => {
          // graphql-ws retries the underlying connection and resubscribes automatically; nothing to do here.
        },
        complete: () => {},
      }
    );

    return () => unsubscribe();
  }, [workspaceId, enabled]);
}
