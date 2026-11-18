import { test, expect, Request } from "@playwright/test";

// Single-domain stack only (TERRAKUBE_SINGLE_DOMAIN=1): the browser must talk to one Terrakube hostname and
// reach the backend through the canonical /api prefix.
test.skip(!process.env.TERRAKUBE_SINGLE_DOMAIN, "set TERRAKUBE_SINGLE_DOMAIN=1 against a single-domain stack");

test("every request stays on the shared host", async ({ page, baseURL }) => {
  const sharedHost = new URL(baseURL ?? "https://terrakube.localhost").host;
  const requests: string[] = [];
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    if (url.protocol.startsWith("http") || url.protocol.startsWith("ws")) {
      requests.push(url.toString());
    }
  });

  await page.goto("/");
  await page.getByRole("link", { name: "Open organization simple", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Workspaces" })).toBeVisible();
  const orgId = page.url().match(/\/organizations\/([^/]+)/)?.[1];
  await page.goto(`/organizations/${orgId}/registry/search?tab=providers&q=random`);
  await page.waitForLoadState("networkidle");

  // Third-party images (avatars, provider logos) are fine; other Terrakube hosts are not.
  const parentDomain = sharedHost.split(".").slice(1).join(".");
  const isTerrakubeHost = (host: string) => host.includes("terrakube") || host.endsWith(`.${parentDomain}`);
  const foreign = requests.filter((url) => {
    const { host } = new URL(url);
    return host !== sharedHost && isTerrakubeHost(host);
  });
  expect(foreign, "requests to other Terrakube hosts").toEqual([]);
  expect(requests.some((url) => new URL(url).pathname.startsWith("/api/"))).toBe(true);
});
