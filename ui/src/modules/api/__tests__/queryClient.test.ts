import { UserManager } from "oidc-client-ts";
import { clearQueriesOnSignOut, createQueryClient } from "../queryClient";

describe("clearQueriesOnSignOut", () => {
  const newUserManager = () =>
    new UserManager({ authority: "https://idp.test", client_id: "ui", redirect_uri: "https://ui.test" });

  it("drops every cached query when the user is removed", async () => {
    const client = createQueryClient();
    const users = newUserManager();
    clearQueriesOnSignOut(client, users);
    client.setQueryData(["organization", "org-1", "teams"], [{ id: "t-1" }]);

    await users.removeUser();

    expect(client.getQueryData(["organization", "org-1", "teams"])).toBeUndefined();
  });

  it("stops clearing once unsubscribed", async () => {
    const client = createQueryClient();
    const users = newUserManager();
    clearQueriesOnSignOut(client, users)();
    client.setQueryData(["organization", "org-1", "teams"], [{ id: "t-1" }]);

    await users.removeUser();

    expect(client.getQueryData(["organization", "org-1", "teams"])).toEqual([{ id: "t-1" }]);
  });
});
