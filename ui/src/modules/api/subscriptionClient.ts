import { Client, createClient } from "graphql-ws";
import getUserFromStorage from "../../config/authUser";
import { subscriptionsUrl } from "../../config/apiUrls";

let client: Client | undefined;

export function getSubscriptionClient(): Client {
  if (client == null) {
    const wsUrl = subscriptionsUrl();

    client = createClient({
      url: wsUrl,
      connectionParams: () => {
        const user = getUserFromStorage();
        return { Authorization: `Bearer ${user?.access_token ?? ""}` };
      },
    });
  }
  return client;
}
