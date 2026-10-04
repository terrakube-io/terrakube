import { test, expect } from "@playwright/test";

test.describe("theme settings", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/settings/theme");
    await expect(page.getByRole("heading", { name: "Theme Settings" })).toBeVisible();
  });

  test("switches color scheme and theme mode with the tiles", async ({ page }) => {
    const html = page.locator("html");

    await page.getByRole("radio", { name: /^Blue/ }).check();
    await expect(html).toHaveAttribute("data-color-scheme", "blue");

    await page.getByRole("radio", { name: "Dark" }).check();
    await expect(html).toHaveAttribute("data-theme", "dark");

    // Restore defaults so other tests start from the stored light/terrakube theme.
    await page.getByRole("radio", { name: /^Terrakube/ }).check();
    await page.getByRole("radio", { name: "Light" }).check();
    await expect(html).toHaveAttribute("data-color-scheme", "terrakube");
    await expect(html).toHaveAttribute("data-theme", "light");
  });

  test("theme mode is operable from the keyboard", async ({ page }) => {
    const light = page.getByRole("radio", { name: "Light" });
    await light.check();
    await light.focus();
    await page.keyboard.press("ArrowRight");
    await expect(page.getByRole("radio", { name: "Dark" })).toBeChecked();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

    await page.keyboard.press("ArrowLeft");
    await expect(light).toBeChecked();
  });
});
