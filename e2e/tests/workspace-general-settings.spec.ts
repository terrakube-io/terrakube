import { test, expect } from "@playwright/test";

test("workspace general settings save changes and show the workspace's own agent", async ({ page }) => {
  await page.goto("/organizations");
  await page.getByRole("link", { name: "Open organization simple", exact: true }).click();
  await expect(page).toHaveURL(/\/organizations\/[^/]+\/workspaces/);
  // First workspace in the list: its link ends with the workspace UUID.
  const findWorkspacePath = () =>
    page
      .locator("a[href*='/workspaces/']")
      .evaluateAll((links) =>
        links.map((a) => a.getAttribute("href") ?? "").find((href) => /\/workspaces\/[0-9a-f-]{36}$/.test(href))
      );
  await expect.poll(findWorkspacePath).toBeTruthy();
  const workspacePath = await findWorkspacePath();

  await page.goto(`${workspacePath}/settings/general`);
  await expect(page.getByRole("heading", { name: "General settings" })).toBeVisible();

  // The agent select shows the workspace's agent (or "default"), not another field's value.
  await expect(page.locator(".ant-form-item").filter({ hasText: "Executor agent" })).toContainText("default");
  await expect(page.getByRole("radio", { name: /^Remote/ })).toBeVisible();

  const description = page.getByLabel("Description");
  const original = await description.inputValue();
  try {
    await description.fill(`${original} (e2e)`);
    await page.getByRole("button", { name: "Save settings" }).click();
    await expect(page.getByText("Workspace updated successfully")).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("Description")).toHaveValue(`${original} (e2e)`);
  } finally {
    await page.getByLabel("Description").fill(original);
    await page.getByRole("button", { name: "Save settings" }).click();
    await expect(page.getByText("Workspace updated successfully").last()).toBeVisible();
  }
});
