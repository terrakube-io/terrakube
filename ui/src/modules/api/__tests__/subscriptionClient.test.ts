import { createClient } from "graphql-ws";
import { getSubscriptionClient } from "../subscriptionClient";

jest.mock("graphql-ws");

describe("getSubscriptionClient", () => {
  beforeAll(() => {
    (createClient as jest.Mock).mockReturnValue({ subscribe: jest.fn() });
  });

  // getSubscriptionClient() caches a module-level singleton, so both behaviors are asserted from the
  // same pair of calls in one test rather than split across tests - a second `it()` calling
  // getSubscriptionClient() again would just hit the cache and never call createClient again, since Jest
  // doesn't reset module-level state between tests in the same file the way it resets mocks.
  it("creates the client once, reuses it on subsequent calls, and configures connectionParams with the access token", async () => {
    const first = getSubscriptionClient();
    const second = getSubscriptionClient();

    expect(createClient).toHaveBeenCalledTimes(1);
    expect(second).toBe(first);

    const options = (createClient as jest.Mock).mock.calls[0][0];
    const params = await options.connectionParams();

    expect(params).toHaveProperty("Authorization");
    expect(params.Authorization).toMatch(/^Bearer /);
  });
});
