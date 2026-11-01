import { test, expect } from '@playwright/test';

test('login page renders and exposes credentials controls', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveTitle(/Apex People/i);
  await expect(page.locator('body')).toContainText(/sign in/i);
  await expect(page.locator('input')).not.toHaveCount(0);
});

test('invitation without token explains missing invitation', async ({ page }) => {
  await page.goto('/invite/accept');
  await expect(page.getByText('This invitation link is missing its token.')).toBeVisible();
});

test('signup route renders without uncaught page errors', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/signup');
  await expect(page.locator('#app')).not.toBeEmpty();
  expect(errors).toEqual([]);
});
