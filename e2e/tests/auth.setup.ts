import { test as setup, expect } from "@playwright/test";

// Signs in through Dex (LDAP) once; the OIDC token lives in localStorage and is reused via storageState.
setup("sign in as admin", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.locator("#login").fill(process.env.TERRAKUBE_USER ?? "admin@example.com");
  await page.locator("#password").fill(process.env.TERRAKUBE_PASSWORD ?? "admin");
  await page.locator("#submit-login").click();
  // Dex may show a consent screen on first login.
  const grant = page.getByRole("button", { name: "Grant Access" });
  if (await grant.isVisible({ timeout: 2_000 }).catch(() => false)) await grant.click();
  // oidc-client-ts stores the user under "oidc.user:<authority>:<client>" once the callback is processed.
  await page.waitForFunction(() => Object.keys(localStorage).some((k) => k.startsWith("oidc.user:")));
  await page.context().storageState({ path: ".auth/admin.json" });
});
