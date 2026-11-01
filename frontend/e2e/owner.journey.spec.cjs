const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { totp } = require('./totp.cjs');

test('workspace owner signs up, secures the account, and tours the HRM', async ({ page }, testInfo) => {
  test.setTimeout(180_000);
  page.setDefaultTimeout(10_000);
  const sampleFile = path.resolve(__dirname, '../.e2e-local/owner-sample.json');
  page.on('request', request => {
    if (request.url().includes('/api/v1/security/')) console.log(`request ${request.method()} ${new URL(request.url()).pathname}`);
  });
  page.on('response', response => {
    if (response.url().includes('/api/v1/security/')) console.log(`response ${response.status()} ${new URL(response.url()).pathname}`);
  });
  page.on('requestfailed', request => {
    if (request.url().includes('/api/v1/security/')) console.log(`request failed ${request.method()} ${new URL(request.url()).pathname}: ${request.failure()?.errorText}`);
  });

  // The first run creates one sample tenant in the selected database. Later runs reuse it
  // so repeated tours do not create duplicate tenants or owner accounts.
  let sample;
  if (fs.existsSync(sampleFile)) {
    sample = JSON.parse(fs.readFileSync(sampleFile, 'utf8'));
    await signIn(page, sample);
  } else {
    const runId = `${Date.now()}${Math.floor(Math.random() * 10_000)}`;
    sample = {
      workspaceName: `Apex E2E Sample ${runId.slice(-8)}`,
      email: `owner.${runId}@e2e.apexhrm.test`,
      username: `e2e${runId}`.slice(0, 32),
      password: 'Apex-E2E-Local-Owner-2026!',
      mfaSecretHex: null,
    };
    await page.goto('/signup');
    await expect(page.getByRole('heading', { name: 'Create your workspace' })).toBeVisible();
    await page.getByLabel('Organization name').fill(sample.workspaceName);
    await page.getByLabel('Username').fill(sample.username);
    await page.locator('#signup-email').fill(sample.email);
    await page.locator('#signup-password').fill(sample.password);
    await page.getByRole('button', { name: 'Create workspace' }).click();
    await expect(page).toHaveURL(/\/dashboard\/overview$/, { timeout: 30_000 });
    saveSample(sampleFile, sample);
  }

  await expect(page.getByRole('heading', { name: /Good to see you, owner/ })).toBeVisible();
  await expect(page.getByText(sample.workspaceName, { exact: true }).first()).toBeVisible();
  await page.getByRole('button', { name: 'Open account menu' }).click();
  await expect(page.getByText('Workspace owner').first()).toBeVisible();
  await page.keyboard.press('Escape');

  // Complete first-owner MFA enrollment once, then exercise the actual MFA sign-in challenge.
  if (!sample.mfaSecretHex) {
    const enrollmentResponse = page.waitForResponse(response =>
      response.url().endsWith('/api/v1/security/mfa/enroll') && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Set up MFA' }).click();
    const enrollment = await (await enrollmentResponse).json();
    await expect(page.getByText('Scan this QR code with your authenticator app')).toBeVisible();
    sample.mfaSecretHex = enrollment.secretHex;
    const firstCounter = Math.floor(Date.now() / 1000 / 30);
    await page.getByLabel('6-digit code').fill(totp(sample.mfaSecretHex, firstCounter));
    await page.getByRole('button', { name: 'Verify code' }).click();
    await expect(page.getByText('Authenticator is enabled and requested at sign-in.')).toBeVisible();
    sample.mfaCounter = firstCounter;
    saveSample(sampleFile, sample);
    await page.getByRole('button', { name: 'Open account menu' }).click();
    await page.getByRole('menuitem', { name: /Sign out/ }).click();
    await signIn(page, sample);
  }

  await expect(page).toHaveURL(/\/dashboard\/overview$/);
  await expect(page.getByRole('heading', { name: /Good to see you, owner/ })).toBeVisible();

  const sections = [
    ['Personnel', 'Employee directory', 'People'],
    ['Personnel', 'Onboarding', 'Onboarding'],
    ['Personnel', 'Organization', 'Organization'],
    ['Personnel', 'Documents', 'Documents'],
    ['Personnel', 'HR policies', 'HR policies'],
    ['Personnel', 'Approval workflows', 'Approval workflows'],
    ['Personnel', 'Recruitment', 'Recruitment & applicant tracking'],
    ['Personnel', 'Headcount requests', 'Headcount requests'],
    ['Personnel', 'Performance', 'Performance management'],
    ['Personnel', 'Learning & development', 'Learning & development'],
    ['Personnel', 'My learning', 'My learning'],
    ['Timekeeping', 'Time & attendance', 'Time & attendance'],
    ['Timekeeping', 'Leave management', 'Leave management'],
    ['Timekeeping', 'Schedules & shifts', 'Schedules & shifts'],
    ['Timekeeping', 'Overtime', 'Overtime'],
    ['Timekeeping', 'Approval center', 'Approval center'],
    ['Payroll', 'Payroll runs', 'Payroll runs'],
    ['Payroll', 'Pay groups & periods', 'Pay groups & periods'],
    ['Payroll', 'Benefits & deductions', 'Benefits & deductions'],
    ['Payroll', 'Payslips', 'Payslips'],
    ['Payroll', 'Tax & compliance', 'Tax & compliance'],
    ['Insights', 'Reports & analytics', 'Reports & analytics'],
    ['Insights', 'Announcements', 'Announcements'],
    ['Administration', 'Settings', 'Settings'],
    ['Administration', 'Users & roles', 'Staff assignments'],
    ['Administration', 'Billing', 'Plan & billing'],
    ['Administration', 'Audit logs', 'Audit logs'],
  ];

  for (const [module, section, heading] of sections) {
    console.log(`owner tour: ${section}`);
    const moduleButton = page.getByRole('navigation', { name: 'Main sections' }).getByRole('button', { name: module });
    if (await moduleButton.isVisible()) await moduleButton.click();
    else await page.getByRole('button', { name: 'Open navigation' }).click();
    const workspaceNav = page.getByRole('navigation', { name: 'Workspace navigation' });
    await expect(workspaceNav).toBeVisible();
    await workspaceNav.getByRole('button', { name: section, exact: true }).click();
    await expect(page.getByRole('heading', { name: heading, exact: true }).first()).toBeVisible({ timeout: 15_000 });
    if (section === 'My learning') {
      await expect(page.getByRole('alert')).toContainText('Your learning profile has not been linked to your employee record.');
    } else {
      await expect(page.getByRole('alert')).toHaveCount(0);
    }
    await testInfo.attach(`owner-tour-${section.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`, {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
  }

  // Configure a real sequential workflow and tenant-wide assignment as the new owner.
  await page.getByRole('navigation', { name: 'Main sections' }).getByRole('button', { name: 'Personnel' }).click();
  const workspaceNav = page.getByRole('navigation', { name: 'Workspace navigation' });
  await workspaceNav.getByRole('button', { name: 'Approval workflows', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Approval workflows', exact: true })).toBeVisible();
  const workflowName = `E2E headcount approval ${sample.workspaceName.slice(-8)}`;
  let workflowCard = page.locator('.approval-card').filter({ hasText: workflowName }).first();
  if (await workflowCard.count() === 0) {
    await page.getByLabel('Workflow name').fill(workflowName);
    await page.getByLabel('Business process').first().selectOption('HEADCOUNT');
    await page.getByRole('button', { name: 'Create draft' }).click();
  } else {
    await workflowCard.getByRole('button', { name: 'Configure' }).click();
  }
  await expect(page.getByText(`${workflowName} · VERSION 1`, { exact: true })).toBeVisible();
  const publishButton = page.getByRole('button', { name: 'Publish version' });
  if (await publishButton.isVisible()) {
    if (!(await publishButton.isEnabled())) {
      await page.getByLabel('Step label').fill('Owner approval');
      await page.getByLabel('Route to').selectOption('ROLE');
      await page.getByRole('combobox', { name: 'Role' }).selectOption({ label: 'TENANT_ADMIN' });
      await page.getByRole('button', { name: /^Add step \d+$/ }).click();
    }
    await expect(publishButton).toBeEnabled();
    await publishButton.click();
    await expect(page.getByRole('status')).toContainText('Workflow version published.');
  }
  await page.locator('form.announcements-editor').last().locator('select').first().selectOption('HEADCOUNT');
  await page.getByLabel('Published workflow').selectOption({ label: `${workflowName} · v1` });
  await page.getByRole('button', { name: 'Save assignment' }).click();
  await expect(page.getByText('Workflow assignment saved.', { exact: true })).toBeVisible();

  await expect(page.getByText(`${workflowName} v1`, { exact: false }).first()).toBeVisible();
});

async function signIn(page, sample) {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Sign in to your workspace' })).toBeVisible();
  await page.getByRole('tab', { name: 'Username & password' }).click();
  await page.getByLabel('Email or username').fill(sample.username);
  await page.getByLabel('Password', { exact: true }).fill(sample.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  if (sample.mfaSecretHex) {
    await expect(page.getByLabel('Authenticator code')).toBeVisible();
    const counter = Math.max(Number(sample.mfaCounter || 0) + 1, Math.floor(Date.now() / 1000 / 30));
    await page.getByLabel('Authenticator code').fill(totp(sample.mfaSecretHex, counter));
    await page.getByRole('button', { name: 'Sign in' }).click();
    sample.mfaCounter = counter;
  }
  await expect(page).toHaveURL(/\/dashboard\/overview$/, { timeout: 30_000 });
  if (sample.mfaSecretHex) saveSample(path.resolve(__dirname, '../.e2e-local/owner-sample.json'), sample);
}

function saveSample(file, sample) {
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  fs.writeFileSync(file, `${JSON.stringify(sample, null, 2)}\n`, { mode: 0o600 });
  fs.chmodSync(file, 0o600);
}
