const { test, expect } = require('@playwright/test');

const pngBytes = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAeElEQVR4nO3PUQkAIBTAwJfEzDbWEH4cwmABbrP2+brhgga0oAEtaEALGtCCBrSgAS1oQAsa0IIGtKABLWhACxrQgga0oAEtaEALGtCCBrSgAS1oQAsa0IIGtKABLWhACxrQgga0oAEtaEALGtCCBrSgAS1oQAseuwhu0YdVjn6RAAAAAElFTkSuQmCC',
  'base64',
);

const pdfBytes = Buffer.from(`%PDF-1.4
1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >> endobj
trailer << /Root 1 0 R /Size 4 >>
%%EOF
`);

test('HR admin onboards a new hire with complete data, dual-control approval, and a configured workflow', async ({ page }, testInfo) => {
  test.setTimeout(Number(process.env.E2E_TIMEOUT_MS ?? 420_000));
  page.setDefaultTimeout(15_000);

  const today = new Date().toISOString().slice(0, 10);
  const yesterday = new Date(`${today}T00:00:00Z`);
  yesterday.setUTCDate(yesterday.getUTCDate() - 1);
  const hireDate = yesterday.toISOString().slice(0, 10);
  const tomorrow = new Date(`${today}T00:00:00Z`);
  tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
  const leaveDate = tomorrow.toISOString().slice(0, 10);

  const runId = `${Date.now()}${Math.floor(Math.random() * 10_000)}`.slice(-12);
  const owner = {
    workspaceName: `Onboarding E2E ${runId.slice(-6)}`,
    username: `onb${runId}`.slice(0, 32),
    email: `owner.onboarding.${runId}@e2e.apexhrm.test`,
    password: 'Apex-E2E-Onboarding-Owner-2026!',
  };
  const employeeNumber = `ONB-${runId}`;
  const firstName = 'Nova';
  const lastName = `Hire${runId.slice(-5)}`;
  const branchName = `Onboarding Branch ${runId.slice(-5)}`;
  const departmentName = `Onboarding Department ${runId.slice(-5)}`;
  const positionName = 'Associate Software Engineer';
  const payGroupName = `Onboarding Monthly ${runId.slice(-5)}`;

  page.on('console', message => {
    if (message.type() === 'error' && message.text().includes('same key')) {
      throw new Error(`React duplicate-key warning: ${message.text()}`);
    }
  });

  await page.goto('/signup');
  await expect(page.getByRole('heading', { name: 'Create your workspace' })).toBeVisible();
  await page.getByLabel('Organization name').fill(owner.workspaceName);
  await page.getByLabel('Username').fill(owner.username);
  await page.locator('#signup-email').fill(owner.email);
  await page.locator('#signup-password').fill(owner.password);
  await page.getByRole('button', { name: 'Create workspace' }).click();
  await expect(page).toHaveURL(/\/dashboard\/overview$/, { timeout: 30_000 });

  const callAs = async (token, method, path, body) => page.evaluate(async input => {
    const response = await fetch(input.path, {
      method: input.method,
      headers: { Authorization: `Bearer ${input.token}`, ...(input.body ? { 'Content-Type': 'application/json' } : {}) },
      ...(input.body ? { body: JSON.stringify(input.body) } : {}),
    });
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }, { token, method, path, body });
  const callApi = async (method, path, body) => {
    const token = await page.evaluate(() => sessionStorage.getItem('apexHrm.accessToken'));
    return callAs(token, method, path, body);
  };
  const expectOk = result => expect(result.status, JSON.stringify(result.body)).toBeLessThan(300);

  const companyResult = await callApi('GET', '/api/v1/companies');
  expectOk(companyResult);
  const company = companyResult.body[0];
  expect(company, 'signup creates a company').toBeTruthy();

  const branchResult = await callApi('POST', `/api/v1/companies/${company.id}/branches`, {
    branchCode: `BR-${runId}`, branchName, regionCode: 'NCR',
  });
  expectOk(branchResult);
  const departmentResult = await callApi('POST', '/api/v1/personnel/departments', {
    companyId: company.id, code: `DEPT-${runId}`, name: departmentName,
  });
  expectOk(departmentResult);
  const positionResult = await callApi('POST', '/api/v1/personnel/positions', {
    companyId: company.id, code: `POS-${runId}`, name: positionName,
  });
  expectOk(positionResult);
  const payGroupResult = await callApi('POST', '/api/pay-groups', {
    companyId: company.id, code: `PG-${runId}`, name: payGroupName, frequency: 'MONTHLY',
    daysFactor: 261, hoursPerDay: 8, contributionSchedule: 'LAST_CUTOFF', taxMethod: 'STRICT',
  });
  expectOk(payGroupResult);

  const sidebar = p => p.getByRole('complementary', { name: 'Workspace navigation' });
  const openSidebar = async p => {
    const openWorkspace = p.getByRole('button', { name: 'Open workspace navigation' });
    if (await openWorkspace.isVisible().catch(() => false)) { await openWorkspace.click(); return; }
    const openPeopleNavigation = p.getByRole('button', { name: 'Open navigation', exact: true });
    if (await openPeopleNavigation.isVisible().catch(() => false)) await openPeopleNavigation.click();
  };
  const navigateTo = async (p, module, section) => {
    await openSidebar(p);
    await expect(sidebar(p)).toBeVisible();
    await sidebar(p).getByRole('button', { name: module }).first().click();
    const workspaceNav = sidebar(p).getByRole('navigation', { name: /areas$/ });
    await expect(workspaceNav).toBeVisible();
    await workspaceNav.getByRole('button', { name: section, exact: true }).click();
  };
  const profileTabs = p => p.getByRole('navigation', { name: 'Employee profile sections' });
  const openEmployee = async (p, pageName) => {
    if (pageName) await navigateTo(p, 'Personnel', pageName);
    const row = p.getByRole('row').filter({ hasText: employeeNumber }).first();
    await expect(row).toBeVisible({ timeout: 20_000 });
    await row.getByRole('button', { name: /Open profile/ }).click();
    await expect(profileTabs(p)).toBeVisible({ timeout: 20_000 });
  };

  await navigateTo(page, 'Personnel', 'Employee directory');
  await expect(page.getByRole('heading', { name: 'People', exact: true })).toBeVisible();
  await page.getByRole('button', { name: /Add employee/ }).first().click();
  const addModal = page.getByRole('dialog', { name: 'Add employee' });
  await addModal.getByLabel('Employee number').fill(employeeNumber);
  await addModal.getByLabel('First name').fill(firstName);
  await addModal.getByLabel('Last name').fill(lastName);
  await addModal.getByLabel('Hire date').fill(hireDate);
  await addModal.getByRole('button', { name: 'Create employee' }).click();
  await expect(page.getByText('Employee record created and linked to timekeeping.', { exact: true })).toBeVisible({ timeout: 20_000 });
  await testInfo.attach('onboarding-01-employee-created', { body: await page.screenshot(), contentType: 'image/png' });

  const employeeResult = await callApi('GET', `/api/v1/personnel/employees?companyId=${company.id}`);
  expectOk(employeeResult);
  const employee = employeeResult.body.find(item => item.employeeNumber === employeeNumber);
  expect(employee, 'employee created through the UI is available through the API').toBeTruthy();

  await openEmployee(page);

  const profileForm = page.locator('form.people-subcard').filter({ hasText: 'Personal profile' });
  await profileForm.getByLabel('Employee photo').setInputFiles({ name: 'onboarding-photo.png', mimeType: 'image/png', buffer: pngBytes });
  await expect(profileForm.getByAltText('Employee profile preview')).toBeVisible({ timeout: 15_000 });
  await profileForm.getByLabel('Middle name').fill('Onboarding');
  await profileForm.getByLabel('Preferred name').fill('Nova');
  await profileForm.getByLabel('Suffix').fill('III');
  await profileForm.getByRole('button', { name: 'Save profile' }).click();
  await expect(page.getByText('Profile details saved.', { exact: true })).toBeVisible({ timeout: 20_000 });

  await profileTabs(page).getByRole('button', { name: 'Assignments', exact: true }).click();
  const assignmentForm = page.locator('form').filter({ has: page.getByRole('button', { name: 'Save assignment' }) });
  await assignmentForm.getByRole('combobox', { name: 'Branch', exact: true }).selectOption(branchResult.body.id);
  await assignmentForm.getByRole('combobox', { name: 'Department', exact: true }).selectOption(departmentResult.body.id);
  await assignmentForm.getByRole('combobox', { name: 'Position', exact: true }).selectOption(positionResult.body.id);
  await assignmentForm.getByLabel('Effective date').fill(today);
  await assignmentForm.getByLabel('Reason').fill('Initial onboarding assignment');
  await assignmentForm.getByRole('button', { name: 'Save assignment' }).click();
  await expect(page.getByText('Assignment recorded.', { exact: true })).toBeVisible({ timeout: 20_000 });

  const payGroupForm = page.locator('form').filter({ has: page.getByRole('button', { name: 'Assign pay group' }) });
  await payGroupForm.getByLabel('Pay group').selectOption(payGroupResult.body.id);
  await payGroupForm.getByLabel('Effective from').fill(hireDate);
  await payGroupForm.getByLabel('Reason').fill('Initial onboarding pay group');
  await payGroupForm.getByRole('button', { name: 'Assign pay group' }).click();
  await expect(page.getByText('Payroll assignment recorded.', { exact: true })).toBeVisible({ timeout: 20_000 });

  await profileTabs(page).getByRole('button', { name: 'Employment', exact: true }).click();
  const employmentForm = page.locator('form').filter({ has: page.getByRole('button', { name: 'Record change' }) });
  await employmentForm.getByLabel('Effective date').fill(today);
  await employmentForm.getByLabel('Employment status').selectOption('ACTIVE');
  await employmentForm.getByLabel('Employment type').selectOption('PROBATIONARY');
  await employmentForm.getByLabel('Job title').fill(positionName);
  await employmentForm.getByLabel('Reason').fill('Onboarding classification and job title');
  await employmentForm.getByRole('button', { name: 'Record change' }).click();
  await expect(page.getByText('Employment change recorded.', { exact: true })).toBeVisible({ timeout: 20_000 });

  await profileTabs(page).getByRole('button', { name: 'Compensation', exact: true }).click();
  const compensationForm = page.locator('form').filter({ has: page.getByRole('button', { name: 'Add compensation' }) });
  await compensationForm.getByLabel('Effective date').fill(today);
  await compensationForm.getByLabel('Amount (PHP)').fill('45000');
  await compensationForm.getByLabel('Pay basis').selectOption('MONTHLY');
  await compensationForm.getByLabel('Reason').fill('Initial onboarding compensation');
  await compensationForm.getByRole('button', { name: 'Add compensation' }).click();
  await expect(page.getByText('Compensation entry recorded.', { exact: true })).toBeVisible({ timeout: 20_000 });

  await profileTabs(page).getByRole('button', { name: 'Private data', exact: true }).click();
  const privateForm = page.locator('form.people-private-form');
  await privateForm.getByLabel('Government ID').fill(`GOV-${runId}`);
  await privateForm.getByLabel('Contact email').fill(`${firstName.toLowerCase()}.${runId}@e2e.apexhrm.test`);
  await privateForm.getByLabel('Mobile').fill('+639171234567');
  await privateForm.getByLabel('Bank account').fill(`BANK-${runId}`);
  await privateForm.getByRole('button', { name: 'Save private data' }).click();
  await expect(page.getByText('Encrypted private employee data saved.', { exact: true })).toBeVisible({ timeout: 20_000 });

  await profileTabs(page).getByRole('button', { name: 'Documents', exact: true }).click();
  const documentForm = page.locator('form').filter({ has: page.getByRole('button', { name: 'Upload document' }) });
  await documentForm.getByLabel('Category').selectOption('ONBOARDING');
  await documentForm.getByLabel('PDF file').setInputFiles({
    name: `onboarding-contract-${runId}.pdf`, mimeType: 'application/pdf', buffer: pdfBytes,
  });
  await documentForm.getByRole('button', { name: 'Upload document' }).click();
  await expect(page.getByText('Document uploaded to encrypted quarantine for review.', { exact: true })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText(`onboarding-contract-${runId}.pdf`, { exact: false }).first()).toBeVisible();

  await testInfo.attach('onboarding-02-profile-data-complete', { body: await page.screenshot(), contentType: 'image/png' });

  await profileTabs(page).getByRole('button', { name: 'HR lifecycle', exact: true }).click();
  const caseForm = page.locator('form').filter({ has: page.getByRole('button', { name: 'Request case' }) });
  await caseForm.getByLabel('Case type').selectOption('ONBOARDING');
  await caseForm.getByLabel('Effective date').fill(today);
  await caseForm.getByLabel('Reason').fill(`Onboard ${firstName} ${lastName} into ${departmentName}`);
  await caseForm.getByRole('button', { name: 'Request case' }).click();
  await expect(page.getByText('HR case requested.', { exact: true })).toBeVisible({ timeout: 20_000 });

  const caseCard = page.locator('.people-case').filter({ hasText: 'ONBOARDING' }).first();
  await expect(caseCard.getByText('PENDING', { exact: true })).toBeVisible({ timeout: 20_000 });
  const tasks = page.locator('.people-case-tasks');
  await expect(tasks).toBeVisible({ timeout: 20_000 });
  await expect.poll(() => tasks.getByRole('button', { name: 'Verify', exact: true }).count(), { timeout: 20_000 }).toBeGreaterThan(0);
  for (let guard = 0; guard < 20; guard += 1) {
    const verifyButton = tasks.getByRole('button', { name: 'Verify', exact: true }).first();
    if (!(await verifyButton.count())) break;
    const verification = page.waitForResponse(response =>
      response.url().includes('/verification') && response.request().method() === 'PATCH');
    await verifyButton.click();
    expectOk({ status: (await verification).status() });
  }
  await expect(tasks.getByText(/^\d+ \/ \d+ verified$/)).toBeVisible({ timeout: 20_000 });
  await expect(tasks.getByRole('button', { name: 'Verify', exact: true })).toHaveCount(0);
  await expect(caseCard.getByRole('button', { name: 'Approve', exact: true })).toBeDisabled();
  await testInfo.attach('onboarding-03-requirements-verified-owner-cannot-approve', { body: await page.screenshot(), contentType: 'image/png' });

  const ownerAccounts = await callApi('GET', '/api/v1/admin/users');
  expectOk(ownerAccounts);
  const ownerAccount = ownerAccounts.body.find(account => account.email === owner.email);
  expect(ownerAccount, 'owner identity').toBeTruthy();

  const approverEmail = `onboarding.approver.${runId}@e2e.apexhrm.test`;
  const approverPassword = `Apex-E2E-Onboarding-Approver-${runId}!`;
  const invitation = await callApi('POST', '/api/v1/admin/invitations', { email: approverEmail, role: 'HR_ADMIN' });
  expectOk(invitation);
  const invitationToken = new URL(invitation.body.acceptanceUrl, 'http://localhost').searchParams.get('token');
  expectOk(await callApi('POST', '/api/v1/invitations/accept', { token: invitationToken, password: approverPassword }));
  const approverAccounts = await callApi('GET', '/api/v1/admin/users');
  expectOk(approverAccounts);
  const approverAccount = approverAccounts.body.find(account => account.email === approverEmail);
  expect(approverAccount, 'independent approver identity').toBeTruthy();
  expectOk(await callApi('PUT', `/api/v1/admin/users/${approverAccount.id}/roles/TENANT_ADMIN`));

  const approverPage = await page.context().newPage();
  approverPage.setDefaultTimeout(15_000);
  await signInWithPassword(approverPage, approverEmail, approverPassword);
  await openEmployee(approverPage, 'Onboarding');
  const approverCase = approverPage.locator('.people-case').filter({ hasText: 'ONBOARDING' }).first();
  const approveCaseButton = approverCase.getByRole('button', { name: 'Approve', exact: true });
  await expect(approveCaseButton).toBeEnabled({ timeout: 20_000 });
  await approveCaseButton.click();
  await expect(approverCase.getByText('APPROVED', { exact: true })).toBeVisible({ timeout: 20_000 });
  await testInfo.attach('onboarding-04-independent-approver-approved-case', { body: await approverPage.screenshot(), contentType: 'image/png' });

  await page.getByRole('button', { name: /People directory/ }).click();
  await navigateTo(page, 'Personnel', 'Onboarding');
  await expect(page.getByRole('heading', { name: 'Onboarding', exact: true })).toBeVisible();
  await openEmployee(page, 'Onboarding');
  const approvedCase = page.locator('.people-case').filter({ hasText: 'ONBOARDING' }).first();
  await expect(approvedCase.getByText('APPROVED', { exact: true })).toBeVisible({ timeout: 20_000 });

  await navigateTo(page, 'Personnel', 'Approval workflows');
  await expect(page.getByRole('heading', { name: 'Approval workflows', exact: true })).toBeVisible();
  const workflowName = `Onboarding leave approval ${runId.slice(-5)}`;
  await page.getByLabel('Workflow name').fill(workflowName);
  await page.getByLabel('Business process').first().selectOption('LEAVE');
  await page.getByRole('button', { name: 'Create draft' }).click();
  await expect(page.getByText(`${workflowName} · VERSION 1`, { exact: true })).toBeVisible({ timeout: 20_000 });
  const publishButton = page.getByRole('button', { name: 'Publish version' });
  if (!(await publishButton.isEnabled())) {
    await page.getByLabel('Step label').fill('Independent HR approval');
    await page.getByLabel('Route to').selectOption('ROLE');
    await page.getByRole('combobox', { name: 'Role' }).selectOption({ label: 'HR_ADMIN' });
    await page.getByRole('button', { name: /^Add step \d+$/ }).click();
  }
  await expect(publishButton).toBeEnabled();
  await publishButton.click();
  await expect(page.getByRole('status')).toContainText('Workflow version published.');
  await page.locator('form.announcements-editor').last().locator('select').first().selectOption('LEAVE');
  await page.getByLabel('Published workflow').selectOption({ label: `${workflowName} · v1` });
  await page.getByRole('button', { name: 'Save assignment' }).click();
  await expect(page.getByText('Workflow assignment saved.', { exact: true })).toBeVisible({ timeout: 20_000 });
  await testInfo.attach('onboarding-05-leave-workflow-published-and-assigned', { body: await page.screenshot(), contentType: 'image/png' });

  const leaveTypes = await callApi('GET', '/api/leave-types');
  expectOk(leaveTypes);
  let leaveType = leaveTypes.body[0];
  if (!leaveType) {
    const createdLeaveType = await callApi('POST', '/api/leave-types', {
      code: `ONB-LEAVE-${runId}`, name: `Onboarding leave ${runId}`, paid: true, annualEntitlement: null,
      hireProrationEnabled: false, convertible: false,
    });
    expectOk(createdLeaveType);
    leaveType = createdLeaveType.body;
  }
  const leaveRequest = await callApi('POST', '/api/leave/requests', {
    employeeId: employee.id, leaveTypeId: leaveType.id, dateFrom: leaveDate, dateTo: leaveDate,
    days: 1, halfDay: false, reason: 'Onboarding workflow demonstration',
  });
  expectOk(leaveRequest);

  const approverToken = await approverPage.evaluate(() => sessionStorage.getItem('apexHrm.accessToken'));
  await expect.poll(async () => {
    const inbox = await callAs(approverToken, 'GET', '/api/v1/approvals/inbox');
    return inbox.status === 200 && inbox.body.some(task =>
      task.process_code === 'LEAVE' && task.first_name === firstName && task.last_name === lastName);
  }, { timeout: 20_000 }).toBe(true);

  await navigateTo(approverPage, 'Timekeeping', 'Approval center');
  await expect(approverPage.getByRole('heading', { name: 'Approval center', exact: true })).toBeVisible();
  const leaveTask = approverPage.locator('.approval-card')
    .filter({ hasText: `${firstName} ${lastName}` })
    .filter({ hasText: 'LEAVE' })
    .first();
  await expect(leaveTask).toBeVisible({ timeout: 20_000 });
  approverPage.once('dialog', dialog => dialog.accept('Approved in the onboarding E2E workflow'));
  await leaveTask.getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(approverPage.getByRole('status')).toContainText('Workflow decision completed.', { timeout: 20_000 });
  await testInfo.attach('onboarding-06-independent-workflow-approval', { body: await approverPage.screenshot(), contentType: 'image/png' });

  await expect.poll(async () => {
    const requests = await callApi('GET', '/api/leave-requests?size=200&sort=createdAt,desc');
    if (requests.status !== 200) return `HTTP ${requests.status}`;
    const request = requests.body.content.find(item => item.id === leaveRequest.body.id);
    return request ? request.status : 'NOT_FOUND';
  }, { timeout: 20_000 }).toBe('APPROVED');

  await navigateTo(page, 'Personnel', 'Onboarding');
  await openEmployee(page, 'Onboarding');
  await expect(page.locator('.people-case').filter({ hasText: 'ONBOARDING' }).first().getByText('APPROVED', { exact: true })).toBeVisible();
  await testInfo.attach('onboarding-07-owner-final-approved-state', { body: await page.screenshot(), contentType: 'image/png' });
});

async function signInWithPassword(page, email, password) {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Sign in to your workspace' })).toBeVisible();
  await page.getByRole('tab', { name: 'Username & password' }).click();
  await page.getByLabel('Email or username').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  const tokenResponse = page.waitForResponse(response =>
    response.url().endsWith('/api/v1/security/tokens') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Sign in' }).click();
  const response = await tokenResponse;
  expect(response.status(), `MFA is disabled for E2E, so sign-in should not be challenged (${response.status()})`).toBe(201);
  await expect(page).toHaveURL(/\/dashboard\/overview$/, { timeout: 30_000 });
}
