import { test, expect } from "@playwright/test";

// Relies on the demo data seeded by the API's "demo" Spring profile.
test("lists demo organizations and opens one", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Manage your organizations")).toBeVisible();
  await expect(page.getByText("simple sample organization")).toBeVisible();

  await page.getByRole("link", { name: "Open organization simple", exact: true }).click();
  await expect(page).toHaveURL(/\/organizations\/[^/]+\/workspaces/);
  await expect(page.getByRole("heading", { name: "Workspaces" })).toBeVisible();
});
