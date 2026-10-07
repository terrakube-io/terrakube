import { test, expect } from "@playwright/test";

test("organization general settings show a copyable ID and save changes", async ({ page }) => {
  await page.goto("/organizations");
  await page.getByRole("link", { name: "Open organization simple", exact: true }).click();
  const orgId = page.url().match(/organizations\/([^/]+)\//)![1];

  await page.goto(`/organizations/${orgId}/settings`);
  await expect(page.getByRole("heading", { name: "General settings" })).toBeVisible();

  // The ID is a read-only field with a separate copy control.
  await expect(page.getByLabel("ID", { exact: true })).toHaveValue(orgId);
  await expect(page.getByLabel("ID", { exact: true })).toHaveAttribute("readonly");
  await expect(page.getByRole("button", { name: "Copy ID" })).toBeVisible();

  const description = page.getByLabel("Description");
  const original = await description.inputValue();
  try {
    await description.fill(`${original} (e2e)`);
    await page.getByRole("button", { name: "Update organization" }).click();
    await expect(page.getByText("Organization updated successfully")).toBeVisible();

    await page.reload();
    await expect(page.getByLabel("Description")).toHaveValue(`${original} (e2e)`);
  } finally {
    await page.getByLabel("Description").fill(original);
    await page.getByRole("button", { name: "Update organization" }).click();
    await expect(page.getByText("Organization updated successfully").last()).toBeVisible();
  }

  await expect(page.getByRole("heading", { name: "Destruction and deletion" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete this organization" })).toBeVisible();
});
