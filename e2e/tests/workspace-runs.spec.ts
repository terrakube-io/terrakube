import { test, expect } from "@playwright/test";

// Seeded workspace whose only run failed on a hard-mandatory policy.
const WORKSPACE =
  "/organizations/70000000-0000-0000-0000-000000000001/workspaces/70000000-0000-0000-0000-000000000063";

test("runs list filters by status and opens a run", async ({ page }) => {
  await page.goto(`${WORKSPACE}/runs`);
  await expect(
    page.getByRole("heading", { name: /^Runs \(\d+\)$/ }),
  ).toBeVisible();

  const chips = page.getByRole("group", { name: "Filter by status" });
  await chips.getByRole("button", { name: /^Failed \d+$/ }).click();
  await expect(
    chips.getByRole("button", { name: /^Failed \d+$/ }),
  ).toHaveAttribute("aria-pressed", "true");
  // Statuses without runs are not offered.
  await expect(chips.getByRole("button", { name: /^Running/ })).toHaveCount(0);

  await page.locator(".run-row-link").first().click();
  await expect(page.getByRole("heading", { name: /^Run #\d+$/ })).toBeVisible();
});

test("run detail shows the policy failure with its result and logs", async ({
  page,
}) => {
  await page.goto(`${WORKSPACE}/runs/4`);
  await expect(page.getByRole("heading", { name: "Run #4" })).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Policy checks" }),
  ).toBeVisible();

  const results = page.getByRole("group", { name: "Filter by result" });
  await results.getByRole("button", { name: /^Hard mandatory \d+$/ }).click();
  const rule = page.getByTestId("rule-card").first();
  await expect(rule.getByText("Hard mandatory")).toBeVisible();
  await expect(
    rule.getByRole("button", { name: "View in plan diff" }),
  ).toBeVisible();

  await results.getByRole("button", { name: /^Passed \d+$/ }).click();
  await page
    .getByRole("button", { name: "View execution logs" })
    .first()
    .click();
  await expect(
    page.getByRole("button", { name: "Hide execution logs" }).first(),
  ).toBeVisible();
});
