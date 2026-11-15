import { QueryClient } from "@tanstack/react-query";
import type { UserManager } from "oidc-client-ts";

/**
 * One client for the whole app. Server data read through `useQuery` is cached under its query key and
 * refetched when a mutation invalidates that key, so a list never has to be reloaded by hand.
 *
 * `retry: false`: the axios interceptors already handle 401/403 and the API-down screen, and a retried
 * 4xx only delays the error the user needs to see.
 */
export const createQueryClient = () =>
  new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

export const queryClient = createQueryClient();

/**
 * Drops every cached query when the user goes away, so the next user signing in from the same tab never sees
 * the previous user's data. Every sign-out path (user menu, expired token, a 401 from the API) ends in
 * `removeUser()`, which raises `userUnloaded`. Returns the unsubscribe function.
 */
export const clearQueriesOnSignOut = (client: QueryClient, userManager: Pick<UserManager, "events">) =>
  userManager.events.addUserUnloaded(() => client.clear());
