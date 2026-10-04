import { test, expect } from "@playwright/test";

test.use({ viewport: { width: 390, height: 844 } });

test("settings stay usable at phone width with the navigation in a drawer", async ({
  page,
}) => {
  await page.goto("/organizations");
  await page
    .getByRole("link", { name: "Open organization simple", exact: true })
    .click();
  const orgId = page.url().match(/organizations\/([^/]+)\//)![1];

  await page.goto(`/organizations/${orgId}/settings`);
  await expect(
    page.getByRole("heading", { name: "General settings" }),
  ).toBeVisible();

  // The sidebar no longer takes width: no horizontal overflow, full-width fields.
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
  expect(
    (await page.getByLabel("Name", { exact: true }).boundingBox())!.width,
  ).toBeGreaterThanOrEqual(300);

  // The section list lives in a drawer behind a labelled toggle.
  const toggle = page.getByRole("button", { name: "Open navigation" });
  const teams = page.getByRole("link", { name: "Teams" });
  await expect(teams).toBeHidden();
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByRole("link", { name: "General" })).toHaveAttribute(
    "aria-current",
    "page",
  );

  await page.keyboard.press("Escape");
  await expect(teams).toBeHidden();
  await expect(toggle).toBeFocused();

  // The page behind the open drawer is inert: Tab cycles through the drawer only.
  await toggle.click();
  for (let i = 0; i < 30; i++) {
    await page.keyboard.press("Tab");
    expect(
      await page.evaluate(
        () =>
          document.activeElement === document.body ||
          Boolean(document.activeElement?.closest("#app-sidebar")),
      ),
    ).toBe(true);
  }

  await teams.click();
  await expect(page).toHaveURL(
    new RegExp(`/organizations/${orgId}/settings/teams$`),
  );
  await expect(teams).toBeHidden();
  await expect(toggle).toBeFocused();
});

test("the workspaces list fits a phone: actions wrap, the table scrolls inside its card", async ({
  page,
}) => {
  await page.goto("/organizations");
  await page.evaluate(() =>
    localStorage.setItem("terrakube.listViewMode", "compact"),
  );
  await page
    .getByRole("link", { name: "Open organization simple", exact: true })
    .click();
  await expect(page.getByRole("heading", { name: "Workspaces" })).toBeVisible();
  await expect(page.locator(".workspace-row").first()).toBeVisible();

  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
  const create = (await page
    .getByRole("link", { name: "New workspace" })
    .boundingBox())!;
  expect(create.x + create.width).toBeLessThanOrEqual(390 - 16);
  expect(
    await page
      .locator(".workspace-list-scroll")
      .evaluate((el) => el.scrollWidth > el.clientWidth),
  ).toBe(true);
});
