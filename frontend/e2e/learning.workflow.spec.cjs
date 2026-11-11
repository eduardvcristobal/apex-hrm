const { test, expect, request } = require('@playwright/test');
const { totp } = require('./totp.cjs');

const appUrl = 'http://localhost:5173';
const api = path => `${appUrl}/api/v1${path}`;

async function json(response) {
  const body = await response.text();
  if (!response.ok()) throw new Error(`HTTP ${response.status()}: ${body}`);
  return body ? JSON.parse(body) : null;
}

async function bearer(page, token, path, options = {}) {
  return page.request.fetch(api(path), {
    ...options,
    headers: { Authorization: `Bearer ${token}`, ...(options.headers || {}) },
  });
}

async function signInToken(page, email, password, extraHeaders = {}) {
  const credentials = Buffer.from(`${email}:${password}`).toString('base64');
  const csrf = await json(await page.request.get(api('/security/csrf')));
  return json(await page.request.post(api('/security/tokens'), {
    headers: { Authorization: `Basic ${credentials}`, [csrf.headerName || 'X-CSRF-TOKEN']: csrf.token, ...extraHeaders },
  }));
}

async function verifyMfaAndIssueToken(page, email, password, bootstrapToken) {
  const headers = { Authorization: `Bearer ${bootstrapToken}` };
  const mfa = await json(await page.request.post(api('/security/mfa/enroll'), { headers }));
  const firstCounter = Math.floor(Date.now() / 30_000);
  const confirm = await page.request.post(api('/security/mfa/confirm'), {
    headers: { ...headers, 'Content-Type': 'application/json' },
    data: { code: totp(mfa.secretHex, firstCounter) },
  });
  expect(confirm.status()).toBe(204);
  while (Math.floor(Date.now() / 30_000) === firstCounter) await new Promise(resolve => setTimeout(resolve, 500));
  return signInToken(page, email, password, { 'X-TOTP-Code': totp(mfa.secretHex) });
}

async function inviteAccount(page, ownerToken, email, role, password) {
  const invite = await json(await bearer(page, ownerToken, '/admin/invitations', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    data: { email, role },
  }));
  const inviteToken = new URL(invite.acceptanceUrl, appUrl).searchParams.get('token');
  const csrf = await json(await page.request.get(api('/security/csrf')));
  const accept = await page.request.post(api('/invitations/accept'), {
    headers: { [csrf.headerName || 'X-CSRF-TOKEN']: csrf.token },
    data: { token: inviteToken, password },
  });
  expect(accept.status()).toBe(204);
  const token = await signInToken(page, email, password);
  const accounts = await json(await bearer(page, ownerToken, '/admin/users'));
  const account = accounts.find(item => item.email === email);
  expect(account).toBeTruthy();
  return { ...token, identityId: account.id };
}

test('Learning course lifecycle, employee portal, and role boundaries', async ({ page }) => {
  test.setTimeout(300_000);
  page.setDefaultTimeout(10_000);

  const suffix = `${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
  const owner = {
    email: `learning.owner.${suffix}@example.test`,
    username: `ldowner${Date.now()}`.slice(0, 32),
    password: 'Learning-Acceptance-Owner-2026!',
  };
  const csrf = await json(await page.request.get(api('/security/csrf')));
  const bootstrap = await json(await page.request.post(api('/security/signup'), {
    headers: { [csrf.headerName || 'X-CSRF-TOKEN']: csrf.token },
    data: { workspaceName: `Learning Acceptance ${suffix}`, ...owner },
  }));
  const bootstrapHeaders = { Authorization: `Bearer ${bootstrap.value}` };

  const mfa = await json(await page.request.post(api('/security/mfa/enroll'), { headers: bootstrapHeaders }));
  const firstCounter = Math.floor(Date.now() / 30_000);
  let code = totp(mfa.secretHex, firstCounter);
  expect((await page.request.post(api('/security/mfa/confirm'), {
    headers: { ...bootstrapHeaders, 'Content-Type': 'application/json' }, data: { code },
  })).status()).toBe(204);
  while (Math.floor(Date.now() / 30_000) === firstCounter) {
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  code = totp(mfa.secretHex);
  const tokenCsrf = await json(await page.request.get(api('/security/csrf')));
  const ownerToken = (await json(await page.request.post(api('/security/tokens'), {
    headers: { Authorization: `Basic ${Buffer.from(`${owner.email}:${owner.password}`).toString('base64')}`, 'X-TOTP-Code': code, [tokenCsrf.headerName || 'X-CSRF-TOKEN']: tokenCsrf.token },
  }))).value;

  const companies = await json(await bearer(page, ownerToken, '/learning/companies'));
  expect(companies.length).toBeGreaterThan(0);
  const company = companies[0];
  const employee = await json(await bearer(page, ownerToken, '/personnel/employees', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    data: { companyId: company.id, employeeNumber: `LD-${suffix}`.slice(0, 50), firstName: 'Learning', lastName: 'Learner', hireDate: new Date().toISOString().slice(0, 10) },
  }));

  const deniedBootstrapWrite = await bearer(page, bootstrap.value, '/learning/skills', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, data: { name: `Must Not Save ${suffix}` },
  });
  expect(deniedBootstrapWrite.status()).toBe(403);

  await page.goto('/');
  await page.evaluate(({ token, expires }) => {
    sessionStorage.setItem('apexHrm.accessToken', token);
    sessionStorage.setItem('apexHrm.expiresAt', expires);
  }, { token: ownerToken, expires: new Date(Date.now() + 3_600_000).toISOString() });
  await page.goto('/dashboard/learning-and-development');
  await expect(page.getByRole('heading', { name: 'Learning & development' })).toBeVisible();
  await page.getByLabel('Company').first().selectOption(company.id);
  await page.getByLabel('Company').nth(1).selectOption(company.id);
  await page.getByLabel('Course code').fill(`SAFE-${suffix}`.slice(0, 50));
  const courseTitle = `Security training ${suffix}`;
  await page.getByLabel('Course title').fill(courseTitle);
  await page.getByLabel('Description').fill('Acceptance workflow course');
  await page.getByLabel('Delivery mode').selectOption('SELF_PACED');
  await page.getByLabel('Duration (hours)').fill('2');
  await page.getByRole('button', { name: 'Save record' }).click();
  await expect(page.getByRole('status')).toContainText('Record saved successfully.');
  await expect(page.getByText(courseTitle, { exact: true })).toBeVisible();

  await page.getByRole('tab', { name: 'Enrollments & history' }).click();
  await page.getByLabel('Course').selectOption({ label: courseTitle });
  await page.getByLabel('Employee').selectOption(employee.id);
  await page.getByRole('button', { name: 'Save record' }).click();
  await expect(page.getByRole('status')).toContainText('Record saved successfully.');
  const enrollmentCard = page.locator('.announcement-card').filter({ hasText: courseTitle });
  await expect(enrollmentCard).toContainText('ENROLLED');
  const startResponsePromise = page.waitForResponse(response =>
    response.url().includes('/api/v1/learning/enrollments/') && response.request().method() === 'PATCH');
  await enrollmentCard.getByRole('button', { name: 'Start' }).click();
  const startResponse = await startResponsePromise;
  expect(startResponse.status()).toBe(200);
  await expect(enrollmentCard).toContainText('IN_PROGRESS');
  await enrollmentCard.getByLabel('Score (optional, 0–100)').fill('96');
  await enrollmentCard.getByRole('button', { name: 'Complete' }).click();
  await expect(enrollmentCard).toContainText('COMPLETED');

  await page.getByRole('tab', { name: 'Reports & analytics' }).click();
  await expect(page.getByRole('heading', { name: 'Training analytics' })).toBeVisible();
  const courseReport = page.getByRole('heading', { name: 'Training completion by course' }).locator('..').locator('..');
  await expect(courseReport).toContainText(courseTitle);
  const csvDownload = page.waitForEvent('download');
  await courseReport.getByRole('button', { name: 'Export CSV' }).click();
  expect((await csvDownload).suggestedFilename()).toMatch(/^learning-training-completion-by-course.*\.csv$/);

  const employeePassword = 'Learning-Acceptance-Employee-2026!';
  const employeeEmail = `learner.${suffix}@example.test`;
  const employeeAccount = await inviteAccount(page, ownerToken, employeeEmail, 'EMPLOYEE', employeePassword);
  const linkNotice = await bearer(page, ownerToken, '/learning/accounts', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    data: { identityId: employeeAccount.identityId, employeeId: employee.id },
  });
  expect(linkNotice.status()).toBe(200);

  const employeeMine = await bearer(page, employeeAccount.value, '/learning/me');
  expect(employeeMine.status()).toBe(200);
  const mine = await json(employeeMine);
  expect(mine.enrollments).toEqual(expect.arrayContaining([
    expect.objectContaining({ courseTitle, status: 'COMPLETED', score: 96 }),
  ]));
  expect((await bearer(page, employeeAccount.value, '/learning/courses')).status()).toBe(403);
  expect((await bearer(page, employeeAccount.value, '/learning/companies')).status()).toBe(403);
  expect((await bearer(page, employeeAccount.value, '/learning/skills', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, data: { name: `Employee write ${suffix}` },
  })).status()).toBe(403);

  await page.goto('/');
  await page.evaluate(({ token, expires }) => {
    sessionStorage.setItem('apexHrm.accessToken', token);
    sessionStorage.setItem('apexHrm.expiresAt', expires);
  }, { token: employeeAccount.value, expires: new Date(Date.now() + 3_600_000).toISOString() });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/dashboard/my-learning');
  await expect(page.getByRole('heading', { name: 'My learning' })).toBeVisible();
  const portalCourse = page.locator('.announcement-card').filter({ hasText: courseTitle });
  await expect(portalCourse).toContainText('COMPLETED');
  await expect(portalCourse).toContainText('96');
  const mobileWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(mobileWidth).toBeLessThanOrEqual(390);

  const auditor = await inviteAccount(page, ownerToken,
    `learning.auditor.${suffix}@example.test`, 'AUDITOR', 'Learning-Acceptance-Auditor-2026!');
  expect((await bearer(page, auditor.value, '/learning/reports/summary')).status()).toBe(200);
  expect((await bearer(page, auditor.value, '/learning/courses')).status()).toBe(200);
  expect((await bearer(page, auditor.value, '/learning/skills', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, data: { name: `Auditor write ${suffix}` },
  })).status()).toBe(403);
  expect((await bearer(page, auditor.value, '/learning/accounts', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    data: { identityId: auditor.identityId, employeeId: employee.id },
  })).status()).toBe(403);
  const anonymous = await request.newContext({ baseURL: appUrl });
  expect((await anonymous.get('/api/v1/learning/courses')).status()).toBe(401);
  await anonymous.dispose();

  const hrEmail = `learning.hr.${suffix}@example.test`;
  const hrPassword = 'Learning-Acceptance-HR-2026!';
  const hrBootstrap = await inviteAccount(page, ownerToken, hrEmail, 'HR_ADMIN', hrPassword);
  expect((await bearer(page, hrBootstrap.value, '/learning/skills', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, data: { name: `HR MFA gate ${suffix}` },
  })).status()).toBe(403);
  const hrAccount = await verifyMfaAndIssueToken(page, hrEmail, hrPassword, hrBootstrap.value);
  expect((await bearer(page, hrAccount.value, '/learning/skills', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, data: { name: `HR verified ${suffix}` },
  })).status()).toBe(201);
  expect((await bearer(page, hrAccount.value, '/learning/accounts', {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    data: { identityId: hrBootstrap.identityId, employeeId: employee.id },
  })).status()).toBe(403);

  const report = await json(await bearer(page, ownerToken, '/learning/reports/summary'));
  expect(report.totals.completed).toBeGreaterThanOrEqual(1);
  expect(report.courses).toEqual(expect.arrayContaining([
    expect.objectContaining({ title: courseTitle, enrolled: 1, completed: 1 }),
  ]));

  const secondOwner = {
    email: `learning.other.${suffix}@example.test`,
    username: `ldother${Date.now()}`.slice(0, 32),
    password: 'Learning-Acceptance-Other-2026!',
  };
  const secondCsrf = await json(await page.request.get(api('/security/csrf')));
  const secondBootstrap = await json(await page.request.post(api('/security/signup'), {
    headers: { [secondCsrf.headerName || 'X-CSRF-TOKEN']: secondCsrf.token },
    data: { workspaceName: `Other Learning Tenant ${suffix}`, ...secondOwner },
  }));
  const secondToken = await verifyMfaAndIssueToken(page, secondOwner.email, secondOwner.password, secondBootstrap.value);
  const secondCompanies = await json(await bearer(page, secondToken.value, '/learning/companies'));
  const otherEmployee = await json(await bearer(page, secondToken.value, '/personnel/employees', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    data: { companyId: secondCompanies[0].id, employeeNumber: `OTHER-${suffix}`.slice(0, 50), firstName: 'Other', lastName: 'Tenant', hireDate: new Date().toISOString().slice(0, 10) },
  }));
  expect((await bearer(page, ownerToken, `/learning/employees/${otherEmployee.id}`)).status()).toBe(404);
  expect((await bearer(page, ownerToken, '/learning/courses', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    data: { companyId: secondCompanies[0].id, code: `FOREIGN-${suffix}`.slice(0, 50), title: 'Foreign company', deliveryMode: 'ONLINE', durationHours: 1 },
  })).status()).toBe(404);
});
