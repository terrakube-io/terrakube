import { useEffect, useRef } from "react";
import { getSubscriptionClient } from "../modules/api/subscriptionClient";
import { JobStatusEvent } from "./useJobStatusSubscription";

type UseOrganizationJobStatusSubscriptionOptions = {
  organizationId: string;
  enabled: boolean;
  onEvent: (event: JobStatusEvent) => void;
};

const SUBSCRIPTION_QUERY = `
  subscription OnOrganizationJobStatusChanged($organizationId: ID!) {
    organizationJobStatusChanged(organizationId: $organizationId) {
      jobId
      workspaceId
      status
    }
  }
`;

export function useOrganizationJobStatusSubscription({
  organizationId,
  enabled,
  onEvent,
}: UseOrganizationJobStatusSubscriptionOptions): void {
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  useEffect(() => {
    if (!enabled) {
      return;
    }

    const unsubscribe = getSubscriptionClient().subscribe(
      { query: SUBSCRIPTION_QUERY, variables: { organizationId } },
      {
        next: (result) => {
          const event = (result.data as { organizationJobStatusChanged?: JobStatusEvent } | null | undefined)
            ?.organizationJobStatusChanged;
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
  }, [organizationId, enabled]);
}
