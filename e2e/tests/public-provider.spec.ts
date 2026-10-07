import { test, expect, type Page } from "@playwright/test";

// Talks to the real public Terraform Registry through the API proxy.
const providerPath = (orgId: string) => `/organizations/${orgId}/registry/public/providers/hashicorp/random`;

// Removes hashicorp/random from the organization if a previous run left it there.
async function removeImportedProvider(page: Page, orgId: string) {
  await page.goto(providerPath(orgId));
  const add = page.getByRole("button", { name: "Add to Terrakube" });
  const inRegistry = page.getByRole("link", { name: /In your registry/ });
  await expect(add.or(inRegistry)).toBeVisible();
  if (!(await inRegistry.isVisible())) return;
  await inRegistry.click();
  await page.getByRole("button", { name: /Manage provider/ }).click();
  await page.getByRole("menuitem", { name: "Delete provider" }).click();
  const dialog = page.getByRole("dialog", { name: "Delete provider" });
  await dialog.getByLabel("Type the name to confirm").fill("random");
  await dialog.getByRole("button", { name: "Delete provider" }).click();
  await expect(page).toHaveURL(/\/registry(\?|$)/);
}

test("adds a public provider at its latest version from the provider page", async ({ page }) => {
  test.setTimeout(150_000);

  await page.goto("/organizations");
  await page.getByRole("link", { name: "Open organization simple", exact: true }).click();
  await expect(page).toHaveURL(/\/organizations\/([^/]+)\/workspaces/);
  const orgId = page.url().match(/organizations\/([^/]+)\//)![1];
  await removeImportedProvider(page, orgId);

  try {
    // Search results link to the provider page instead of opening an import dialog.
    await page.goto(`/organizations/${orgId}/registry/search?tab=providers&q=random`);
    await page.getByRole("link", { name: /^random by hashicorp/ }).click();
    await expect(page).toHaveURL(new RegExp(`${providerPath(orgId)}$`));
    await expect(page.getByRole("heading", { name: "random", level: 2 })).toBeVisible();

    // The registry's latest release is preselected.
    const latest = await page.request
      .get("https://registry.terraform.io/v1/providers/hashicorp/random")
      .then((r) => r.json());
    await expect(page.locator(".public-provider-version")).toContainText(`${latest.version} (latest)`);
    await expect(page.locator(".public-provider-snippet")).toContainText(`version = "${latest.version}"`);

    // The breadcrumb returns to the same search results.
    await page.getByRole("link", { name: "Public Registry Search" }).click();
    await expect(page).toHaveURL(/tab=providers&q=random/);
    await page.goBack();

    await page.getByRole("button", { name: "Add to Terrakube" }).click();
    const dialog = page.getByRole("dialog", { name: "Add public provider to organization" });
    await expect(dialog).toContainText(`hashicorp / random · ${latest.version}`);
    await dialog.getByRole("button", { name: "Add", exact: true }).click();

    await expect(page).toHaveURL(/\/registry\/providers\/[^/]+$/, { timeout: 90_000 });
    await expect(page.getByText(latest.version).first()).toBeVisible();
  } finally {
    await removeImportedProvider(page, orgId);
  }
});

test("shows the page's own error for a provider the registry does not know", async ({ page }) => {
  await page.goto("/organizations");
  await page.getByRole("link", { name: "Open organization simple", exact: true }).click();
  const orgId = page.url().match(/organizations\/([^/]+)\//)![1];

  await page.goto(`/organizations/${orgId}/registry/public/providers/hashicorp/does-not-exist-tk`);
  await expect(page.getByText("Could not load hashicorp/does-not-exist-tk from the public registry.")).toBeVisible();
});
