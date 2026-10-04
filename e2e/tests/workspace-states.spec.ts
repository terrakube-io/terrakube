import { test, expect } from "@playwright/test";

// Seeded workspace with at least one applied state version.
const STATES =
  "/organizations/70000000-0000-0000-0000-000000000001/workspaces/70000000-0000-0000-0000-000000000060/states";

test("state versions list opens the newest version with its state and changes", async ({
  page,
}) => {
  await page.goto(STATES);
  await expect(
    page.getByRole("heading", { name: "State versions" }),
  ).toBeVisible();

  const rows = page.locator(".state-version-row");
  await expect(rows.first()).toBeVisible();
  await rows.first().locator(".state-version-title").click();

  await expect(page.getByRole("button", { name: "Newer" })).toBeDisabled();
  await page.getByRole("tab", { name: "State", exact: true }).click();
  await expect(
    page
      .getByRole("region", { name: "State", exact: true })
      .getByText('"format_version"'),
  ).toBeVisible();

  await expect(
    page.getByRole("heading", { name: "Changes in this version" }),
  ).toBeVisible();

  await page.getByRole("button", { name: "Back to all versions" }).click();
  await expect(rows.first()).toBeVisible();
});
