const { test, expect } = require('@playwright/test');

test('guest can open the HRM sign-in screen without backend access', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByText('Sign in to your workspace')).toBeVisible();
  await expect(page.locator('body')).toBeVisible();
});
