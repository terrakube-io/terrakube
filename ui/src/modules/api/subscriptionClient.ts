import { Client, createClient } from "graphql-ws";
import getUserFromStorage from "../../config/authUser";
import { getPublicApiOrigin } from "../../domain/Jobs/outputUrl";

let client: Client | undefined;

export function getSubscriptionClient(): Client {
  if (client == null) {
    const wsUrl = getPublicApiOrigin().replace(/^http/, "ws") + "/subscriptions";

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
