const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { totp } = require('./totp.cjs');
const { openFormEditor, openEditor, closeWorkspaceDialog } = require('./workspace-modal.cjs');

test('fresh workspace setup, employee onboarding, timekeeping, payroll and payslip journey', async ({ page }) => {
  // This is an end-to-end first-run setup and employee lifecycle, not a navigation tour.
  test.setTimeout(Number(process.env.E2E_TIMEOUT_MS ?? 600_000));
  page.setDefaultTimeout(10_000);
  const sampleFile = path.resolve(process.env.E2E_SAMPLE_PATH || path.resolve(__dirname, '../.e2e-local/owner-sample.json'));
  const reactKeyWarnings = [];
  page.on('console', message => { if (message.type() === 'error' && message.text().includes('same key')) reactKeyWarnings.push(message.text()); });
  page.on('request', request => {
    if (request.url().includes('/api/v1/security/')) console.log(`request ${request.method()} ${new URL(request.url()).pathname}`);
  });
  page.on('response', response => {
    if (response.url().includes('/api/v1/security/')) console.log(`response ${response.status()} ${new URL(response.url()).pathname}`);
    if (response.url().includes('/api/') && response.status() >= 400 && response.status() !== 428) console.log(`api error ${response.status()} ${new URL(response.url()).pathname}`);
  });
  page.on('requestfailed', request => {
    if (request.url().includes('/api/v1/security/')) console.log(`request failed ${request.method()} ${new URL(request.url()).pathname}: ${request.failure()?.errorText}`);
  });

  // Point E2E_SAMPLE_PATH at a new path for a completely fresh tenant on each run.
  // The default sample is reused for local runs; pass a new E2E_SAMPLE_PATH for signup.
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
    await expect(page.getByText('Authenticator enabled', { exact: true })).toBeVisible();
    sample.mfaCounter = firstCounter;
    saveSample(sampleFile, sample);
    await page.getByRole('button', { name: 'Open account menu' }).click();
    await page.getByRole('menuitem', { name: /Sign out/ }).click();
    await signIn(page, sample);
  }

  await expect(page).toHaveURL(/\/dashboard\/overview$/);
  await expect(page.getByRole('heading', { name: /Good to see you, owner/ })).toBeVisible();

  const sidebar = page.locator('aside.workspace-sidebar[aria-hidden="false"]');
  const openSidebar = async p => {
    const drawer = p.locator('aside.workspace-sidebar');
    if (await drawer.getAttribute('aria-hidden') === 'false') return;
    const toggle = p.getByRole('button', { name: 'Open workspace navigation' });
    await expect(toggle).toBeVisible();
    await toggle.click();
    await expect(p.getByRole('button', { name: 'Close workspace navigation' })).toBeVisible();
    await expect(drawer).toHaveAttribute('aria-hidden', 'false');
  };
  const navigateTo = async (module, section) => {
    const dashboardLink = page.getByRole('link', { name: /Dashboard/ }).first();
    if (await dashboardLink.isVisible().catch(() => false)) {
      await dashboardLink.click();
      await expect(page).toHaveURL(/\/dashboard\/overview$/);
    }
    await openSidebar(page);
    await expect(sidebar).toBeVisible();
    await sidebar.getByRole('button', { name: module }).first().click();
    const workspaceNav = sidebar.getByRole('navigation', { name: /areas$/ });
    await expect(workspaceNav).toBeVisible();
    await workspaceNav.getByRole('button', { name: section, exact: true }).click();
  };

  expect(reactKeyWarnings).toHaveLength(0);

  // Begin the actual setup journey in the organization maintenance UI. Signup
  // provisions the first legal entity; complete its company, branch, position,
  // and organization chart records before hiring anyone.
  await navigateTo('Personnel', 'Organization');
  await expect(page.getByRole('heading', { name: 'Organization', exact: true })).toBeVisible();
  const setupRunId = `${Date.now()}`.slice(-7);
  const branchCode = `BR-${setupRunId}`;
  const branchName = `Main office ${setupRunId}`;
  const positionCode = `POS-${setupRunId}`;
  const positionName = `People Operations Associate ${setupRunId}`;
  await page.getByRole('button', { name: 'Company setup & positions' }).click();
  const companyForm = page.locator('form.org-entity-details');
  await openFormEditor(page, companyForm, 'Edit company details');
  await companyForm.getByLabel('Registered legal name').fill(`${sample.workspaceName} ${setupRunId} Incorporated`);
  await companyForm.getByRole('button', { name: 'Save legal entity' }).click();
  await expect(page.getByText('Legal entity details saved.', { exact: true })).toBeVisible();
  const branchForm = page.locator('form.people-inline-form').nth(0);
  await openFormEditor(page, branchForm, 'Add branch');
  await branchForm.getByLabel('Code').fill(branchCode);
  await branchForm.getByLabel('Branch name').fill(branchName);
  await branchForm.getByRole('button', { name: 'Add branch' }).click();
  await expect(page.getByText(branchName, { exact: true })).toBeVisible();
  const positionForm = page.locator('form.people-inline-form').nth(1);
  await openFormEditor(page, positionForm, 'Add position');
  await positionForm.getByLabel('Code').fill(positionCode);
  await positionForm.getByLabel('Position title').fill(positionName);
  await positionForm.getByRole('button', { name: 'Add position' }).click();
  await expect(page.getByText(positionName, { exact: true }).last()).toBeVisible();
  await closeWorkspaceDialog(page);
  await page.getByRole('button', { name: 'Organization chart' }).click();
  const firstNodeForm = page.locator('form.org-chart-first-node');
  const ownerNode = page.locator('.org-chart-card.type-owner');
  await expect(firstNodeForm.or(ownerNode).first()).toBeVisible();
  if (await firstNodeForm.isVisible().catch(() => false)) {
    await firstNodeForm.getByLabel('Owner name').fill('Apex E2E Owner');
    await firstNodeForm.getByRole('button', { name: 'Create owner node' }).click();
  }
  await expect(ownerNode).toBeVisible();
  await ownerNode.getByRole('button', { name: /Add child/ }).click();
  const departmentDialog = page.getByRole('dialog');
  await departmentDialog.getByLabel('Node type').selectOption('DEPARTMENT');
  await departmentDialog.getByLabel('Label / unit name').fill(`People Operations ${setupRunId}`);
  await departmentDialog.getByLabel('Position or description').fill('Employee services and payroll');
  await departmentDialog.getByRole('button', { name: 'Add to chart' }).click();
  await expect(page.getByText(`People Operations ${setupRunId}`, { exact: true })).toBeVisible();

  // Publish leave and overtime approval routes through their maintenance UI.
  await navigateTo('Personnel', 'Approval workflows');
  await expect(page.getByRole('heading', { name: 'Approval workflows', exact: true })).toBeVisible();
  const createApprovalWorkflow = async processCode => {
    const workflowName = `E2E ${processCode} approval ${Date.now()}`;
    await openEditor(page, 'Create workflow');
    await page.getByLabel('Workflow name').fill(workflowName);
    await page.getByLabel('Business process').first().selectOption(processCode);
    await page.getByRole('button', { name: 'Create draft' }).click();
    await expect(page.getByText(`${workflowName} · VERSION 1`, { exact: true })).toBeVisible();
    const publishButton = page.getByRole('button', { name: 'Publish version' });
    await openEditor(page, 'Assign approval workflow');
    await page.getByLabel('Step label').fill('Independent HR approval');
    await page.getByLabel('Route to').selectOption('ROLE');
    await page.getByRole('combobox', { name: 'Role' }).selectOption({ label: 'HR_ADMIN' });
    await page.getByRole('button', { name: /^Add step \d+$/ }).click();
    await expect(publishButton).toBeEnabled();
    await closeWorkspaceDialog(page);
    await publishButton.click();
    await expect(page.getByRole('status')).toContainText('Workflow version published.');
    const workflowAssignment = page.locator('form.announcements-editor').last();
    await openFormEditor(page, workflowAssignment, 'Configure approval steps');
    await workflowAssignment.locator('select').first().selectOption(processCode);
    await page.getByLabel('Published workflow').selectOption({ label: `${workflowName} · v1` });
    await page.getByRole('button', { name: 'Save assignment' }).click();
    await expect(page.getByText('Workflow assignment saved.', { exact: true })).toBeVisible();
    await closeWorkspaceDialog(page);
  };
  await createApprovalWorkflow('LEAVE');
  await createApprovalWorkflow('OVERTIME');

  // Exercise the personnel-to-timekeeping-to-payroll bridge for weekly and daily staff.
  const today = new Date().toISOString().slice(0, 10);
  const hireDate = new Date(`${today}T00:00:00Z`);
  hireDate.setUTCDate(hireDate.getUTCDate() - 180);
  const priorDay = hireDate.toISOString().slice(0, 10);
  const runId = `${Date.now()}`.slice(-9);
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
  expect(company).toBeTruthy();
  // The tenant starts with effective-dated statutory reference tables. Verify
  // that the tax and contribution inputs needed for payroll are present.
  const contributionRules = await callApi('GET', '/api/reference/statutory-contribution-rules?size=500&page=0');
  expectOk(contributionRules);
  const currentContributionRules = (contributionRules.body.content || []).filter(rule =>
    rule.effectiveFrom && rule.effectiveFrom <= today && (!rule.effectiveTo || rule.effectiveTo >= today));
  expect(currentContributionRules.length, 'effective statutory contribution tables are available').toBeGreaterThan(0);
  const taxBrackets = await callApi('GET', '/api/reference/withholding-tax-brackets?size=500&page=0');
  expectOk(taxBrackets);
  const currentTaxBrackets = (taxBrackets.body.content || []).filter(bracket =>
    bracket.effectiveFrom && bracket.effectiveFrom <= today);
  expect(currentTaxBrackets.length, 'effective withholding tax brackets are available').toBeGreaterThan(0);
  // Establish the organization's core reference data before adding the journey hire.
  const branchRows = await callApi('GET', `/api/v1/companies/${company.id}/branches`);
  expectOk(branchRows);
  const branchResult = { body: branchRows.body.find(item => item.branchCode === branchCode) };
  expect(branchResult.body, 'branch created in Organization maintenance').toBeTruthy();
  const departmentResult = await callApi('POST', '/api/v1/personnel/departments', {
    companyId: company.id, code: `DEPT-${runId}`, name: `Journey Operations ${runId}`,
  });
  expectOk(departmentResult);
  const positionRows = await callApi('GET', `/api/v1/personnel/positions?companyId=${company.id}`);
  expectOk(positionRows);
  const positionResult = { body: positionRows.body.find(item => item.code === positionCode) };
  expect(positionResult.body, 'position created in Organization maintenance').toBeTruthy();
  const monday = new Date(`${today}T00:00:00Z`);
  monday.setUTCDate(monday.getUTCDate() - ((monday.getUTCDay() + 6) % 7));
  const weekStart = monday.toISOString().slice(0, 10);
  const sunday = new Date(`${weekStart}T00:00:00Z`);
  sunday.setUTCDate(sunday.getUTCDate() + 6);
  const weekEnd = sunday.toISOString().slice(0, 10);
  await navigateTo('Payroll', 'Pay groups & periods');
  await expect(page.getByRole('heading', { name: 'Pay groups & periods', exact: true })).toBeVisible();
  const createPayGroup = async (kind, frequency, from, through) => {
    const form = page.locator('form.pay-setup-form').first();
    await openFormEditor(page, form, 'Create pay group');
    await form.getByLabel('Company').selectOption(company.id);
    await form.getByLabel('Group code').fill(`E2E-${kind[0].toUpperCase()}-${runId}`);
    await form.getByLabel('Name').fill(`E2E ${kind} ${runId}`);
    await form.getByLabel('Frequency').selectOption(frequency);
    await form.getByLabel('Days factor per year').fill('261');
    await form.getByLabel('Hours per workday').fill('8');
    await form.getByLabel('Statutory contribution schedule').selectOption('EVERY_CUTOFF');
    await form.getByRole('button', { name: /Create pay group/ }).click();
    await expect(page.getByRole('status')).toContainText(`Pay group E2E ${kind} ${runId} created.`);
    const rows = await callApi('GET', '/api/pay-groups?size=500&page=0');
    expectOk(rows);
    const group = rows.body.content.find(item => item.code === `E2E-${kind[0].toUpperCase()}-${runId}`);
    expect(group, `${frequency} group created through pay setup UI`).toBeTruthy();
    const calendar = page.locator('form.pay-setup-form').nth(2);
    await openFormEditor(page, calendar, 'Generate pay calendar', 'Generate calendar');
    await calendar.getByLabel('Pay group').selectOption(group.id);
    await calendar.getByLabel('Calendar starts').fill(from);
    await calendar.getByLabel('Generate through').fill(through);
    await calendar.getByLabel('Pay date after period end (calendar days)').fill('5');
    await calendar.getByRole('button', { name: 'Generate pay calendar' }).click();
    await expect(page.getByRole('status')).toContainText('Calendar created');
    await closeWorkspaceDialog(page);
    return { body: group };
  };
  const weeklyGroupResult = await createPayGroup('weekly', 'WEEKLY', weekStart, weekEnd);
  const dailyGroupResult = await createPayGroup('daily', 'DAILY', today, today);
  // Set up a hire-prorated annual leave policy and local employee class before hire.
  await navigateTo('Timekeeping', 'Leave management');
  await page.getByRole('tab', { name: 'Leave types' }).click();
  const leaveTypeForm = page.locator('form.leave-file-form').first();
    await openFormEditor(page, leaveTypeForm, 'Create leave policy');
  const leaveCode = `E2E-${runId}`;
  const leaveName = `E2E annual leave ${runId}`;
  await leaveTypeForm.getByLabel('Policy code').fill(leaveCode);
  await leaveTypeForm.getByLabel('Policy name').fill(leaveName);
  await leaveTypeForm.getByLabel('Annual entitlement (days)').fill('');
  await leaveTypeForm.getByLabel('Prorate hire-year balance').check();
  await leaveTypeForm.getByRole('button', { name: 'Create leave policy' }).click();
  await expect(page.getByRole('status')).toContainText(`${leaveName} leave policy created.`);
  await closeWorkspaceDialog(page);
  const leaveRows = await callApi('GET', '/api/leave-types');
  expectOk(leaveRows);
  const leaveRecords = Array.isArray(leaveRows.body) ? leaveRows.body : leaveRows.body.content || [];
  const leaveType = leaveRecords.find(item => item.code === leaveCode);
  expect(leaveType, 'leave type created in Leave management').toBeTruthy();
  await page.getByRole('tab', { name: 'Benefit profiles' }).click();
  const benefitProfileForm = page.locator('form.leave-file-form').first();
    await openFormEditor(page, benefitProfileForm, 'Create leave benefit profile');
  const benefitProfileName = `E2E local prorated benefits ${runId}`;
  await benefitProfileForm.getByLabel('Profile code').fill(`E2E-LOCAL-${runId}`);
  await benefitProfileForm.getByLabel('Profile name').fill(benefitProfileName);
  await benefitProfileForm.getByLabel('Employee class').selectOption('LOCAL');
  await benefitProfileForm.getByLabel('Description').fill('Annual leave prorated from hire date.');
  await benefitProfileForm.getByRole('button', { name: 'Create profile' }).click();
  await expect(page.getByRole('status')).toContainText(`${benefitProfileName} profile created.`);
  await page.locator('section.payroll-panel').filter({ has: page.getByRole('heading', { name: 'Configure leave entitlements' }) }).getByLabel('Benefit profile').selectOption({ label: `${benefitProfileName} · LOCAL` });
  const entitlementForm = page.locator('form.tax-form-grid').first();
  await openFormEditor(page, entitlementForm, 'Edit leave entitlement');
  await entitlementForm.getByLabel('Leave type').selectOption(leaveType.id);
  await entitlementForm.getByLabel('Maximum days per year').fill('12');
  await entitlementForm.getByLabel('Effective from').fill(priorDay);
  await entitlementForm.getByRole('checkbox', { name: 'Prorate assignment year' }).check();
  await entitlementForm.getByRole('button', { name: 'Save entitlement' }).click();
  await expect(page.getByRole('status')).toContainText('Effective-dated leave entitlement saved to this benefit profile.');
  await closeWorkspaceDialog(page);
  const benefitRows = await callApi('GET', '/api/leave-benefit-profiles?size=200&page=0');
  expectOk(benefitRows);
  const benefitRecords = Array.isArray(benefitRows.body) ? benefitRows.body : benefitRows.body.content || [];
  const benefitProfile = benefitRecords.find(item => item.code === `E2E-LOCAL-${runId}`);
  expect(benefitProfile, 'leave profile created before employee hire').toBeTruthy();
  const employees = [];
  for (const kind of ['weekly', 'daily']) {
    let created;
    if (kind === 'weekly') {
      // The primary journey employee is hired through the actual personnel UI.
      await navigateTo('Personnel', 'Employee directory');
      await page.getByRole('button', { name: /Add employee/ }).first().click();
      const add = page.getByRole('dialog', { name: 'Add employee' });
      await add.getByLabel('Employee number').fill(`E2E-WEEKLY-${runId}`);
      await add.getByLabel('First name').fill('Nova');
      await add.getByLabel('Last name').fill(`Journey${runId}`);
      await add.getByLabel('Hire date').fill(priorDay);
      await add.getByRole('button', { name: 'Create employee' }).click();
      await expect(page.getByText('Employee record created and linked to timekeeping.', { exact: true })).toBeVisible();
      created = await callApi('GET', `/api/v1/personnel/employees?companyId=${company.id}`);
      expectOk(created);
      created.body = created.body.find(item => item.employeeNumber === `E2E-WEEKLY-${runId}`);
      expect(created.body, 'employee created through the personnel UI').toBeTruthy();
      await page.getByPlaceholder('Search name, employee number, or status').fill(`E2E-WEEKLY-${runId}`);
      await page.getByRole('row').filter({ hasText: `E2E-WEEKLY-${runId}` }).getByRole('button', { name: /Open profile/ }).click();
      const tabs = page.getByRole('navigation', { name: 'Employee profile sections' });
      await tabs.getByRole('button', { name: 'Private data', exact: true }).click();
      const privateForm = page.locator('form.people-private-form');
    await openFormEditor(page, privateForm, 'Save private employee data');
      await privateForm.getByLabel('Government ID').fill(`GOV-${runId}`);
      await privateForm.getByLabel('Contact email').fill(`nova.${runId}@e2e.apexhrm.test`);
      await privateForm.getByLabel('Mobile').fill('+639171234567');
      await privateForm.getByLabel('Bank account').fill(`BANK-${runId}`);
      await privateForm.getByRole('button', { name: 'Save private data' }).click();
      await expect(page.getByText('Encrypted private employee data saved.', { exact: true })).toBeVisible();
      await closeWorkspaceDialog(page);
      await tabs.getByRole('button', { name: 'Documents', exact: true }).click();
      const documentForm = page.locator('form').filter({ has: page.getByRole('button', { name: 'Upload document' }) });
    await openFormEditor(page, documentForm, 'Upload employee document');
      await documentForm.getByLabel('Category').selectOption('ONBOARDING');
      await documentForm.getByLabel('PDF file').setInputFiles({
        name: `employment-agreement-${runId}.pdf`, mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF'),
      });
      await documentForm.getByRole('button', { name: 'Upload document' }).click();
      await expect(page.getByText('Document uploaded to encrypted quarantine for review.', { exact: true })).toBeVisible();
      await closeWorkspaceDialog(page);
      await tabs.getByRole('button', { name: 'Assignments', exact: true }).click();
      await tabs.getByRole('button', { name: 'Assignments', exact: true }).click();
      const assignment = page.locator('form').filter({ has: page.getByRole('button', { name: 'Save assignment' }) });
    await openFormEditor(page, assignment, 'Record employee assignment');
      await assignment.getByRole('combobox', { name: 'Branch', exact: true }).selectOption(branchResult.body.id);
      await assignment.getByRole('combobox', { name: 'Department', exact: true }).selectOption(departmentResult.body.id);
      await assignment.getByRole('combobox', { name: 'Position', exact: true }).selectOption(positionResult.body.id);
      await assignment.getByLabel('Effective date').fill(today);
      await assignment.getByLabel('Reason').fill('Initial journey assignment');
      await assignment.getByRole('button', { name: 'Save assignment' }).click();
      await expect(page.getByText('Assignment recorded.', { exact: true })).toBeVisible();
      await closeWorkspaceDialog(page);
      const payAssignment = page.locator('form').filter({ has: page.getByRole('button', { name: 'Assign pay group' }) });
    await openFormEditor(page, payAssignment, 'Assign pay group');
      await payAssignment.getByLabel('Pay group').selectOption(weeklyGroupResult.body.id);
      await payAssignment.getByLabel('Effective from').fill(priorDay);
      await payAssignment.getByLabel('Reason').fill('Initial weekly payroll assignment');
      await payAssignment.getByRole('button', { name: 'Assign pay group' }).click();
      await expect(page.getByText('Payroll assignment recorded.', { exact: true })).toBeVisible();
      await closeWorkspaceDialog(page);
      await tabs.getByRole('button', { name: 'Compensation', exact: true }).click();
      const pay = page.locator('form').filter({ has: page.getByRole('button', { name: 'Add compensation' }) });
    await openFormEditor(page, pay, 'Record compensation');
      await pay.getByLabel('Effective date').fill(today);
      await pay.getByLabel('Amount (PHP)').fill('30000');
      await pay.getByLabel('Pay basis').selectOption('MONTHLY');
      await pay.getByLabel('Reason').fill('Initial journey compensation');
      await pay.getByRole('button', { name: 'Add compensation' }).click();
      await expect(page.getByText('Compensation entry recorded.', { exact: true })).toBeVisible();
      await closeWorkspaceDialog(page);
      await page.getByRole('button', { name: /People directory/ }).click();
    } else {
      created = await callApi('POST', '/api/v1/personnel/employees', {
        companyId: company.id, employeeNumber: `E2E-${kind.toUpperCase()}-${runId}`,
        firstName: 'Daily', lastName: `Journey${runId}`, hireDate: priorDay,
      });
    }
    expectOk(created);
    const employee = created.body;
    employees.push(employee);
    const group = kind === 'weekly' ? weeklyGroupResult.body : dailyGroupResult.body;
    if (kind === 'daily') {
      const compensation = await callApi('POST', `/api/v1/personnel/employees/${employee.id}/compensation`, {
        effectiveDate: today, amount: 750, payBasis: 'DAILY', reason: 'E2E payroll journey',
      });
      expectOk(compensation);
      const assignment = await callApi('POST', `/api/v1/personnel/employees/${employee.id}/pay-group-assignments`, {
        payGroupId: group.id, effectiveFrom: today, remarks: 'E2E payroll frequency assignment',
      });
      expectOk(assignment);
    }
    if (kind === 'weekly') {
      await navigateTo('Timekeeping', 'Schedules & shifts');
      const scheduleForm = page.locator('form.schedule-create-form');
    await openFormEditor(page, scheduleForm, 'Create schedule');
      await scheduleForm.getByLabel('Code').fill(`E2E-${kind.toUpperCase()}-${runId}`);
      await scheduleForm.getByLabel('Name').fill(`E2E ${kind} schedule`);
      await scheduleForm.getByLabel('Start time').fill('09:00');
      await scheduleForm.getByLabel('End time').fill('17:00');
      await scheduleForm.getByLabel('Break (minutes)').fill('60');
      await scheduleForm.getByLabel('Grace (minutes)').fill('5');
      await scheduleForm.getByLabel('Rest days (comma separated)').fill('SAT,SUN');
      await scheduleForm.getByRole('button', { name: 'Create schedule' }).click();
      await expect(page.getByText(`E2E ${kind} schedule`, { exact: true }).first()).toBeVisible();
      await closeWorkspaceDialog(page);
      await page.getByRole('tab', { name: 'Employee assignments' }).click();
      const scheduleAssignmentForm = page.locator('form.timekeeping-manual-form').last();
    await openFormEditor(page, scheduleAssignmentForm, 'Assign schedule');
      await scheduleAssignmentForm.getByLabel('Employee').selectOption(employee.id);
      await scheduleAssignmentForm.getByLabel('Work schedule').selectOption({ label: `E2E-${kind.toUpperCase()}-${runId} · E2E ${kind} schedule` });
      await scheduleAssignmentForm.getByLabel('Effective from').fill(today);
      await scheduleAssignmentForm.getByRole('button', { name: 'Assign schedule' }).click();
      await closeWorkspaceDialog(page);
      await expect(page.getByText(`E2E ${kind} schedule`, { exact: true }).last()).toBeVisible();
      await page.getByRole('tab', { name: 'Holiday calendar' }).click();
      const holidayForm = page.locator('form.timekeeping-manual-form').last();
    await openFormEditor(page, holidayForm, 'Add holiday');
      await holidayForm.getByLabel('Date').fill(today);
      await holidayForm.getByLabel('Name').fill(`E2E sample holiday ${runId}`);
      await holidayForm.getByLabel('Type').selectOption('SPECIAL_WORKING');
      await holidayForm.getByRole('button', { name: 'Add holiday' }).click();
      await closeWorkspaceDialog(page);
      await expect(page.getByText(`E2E sample holiday ${runId}`, { exact: true })).toBeVisible();
    } else {
      const schedule = await callApi('POST', '/api/work-schedules', {
        code: `E2E-${kind.toUpperCase()}-${runId}`, name: `E2E ${kind} schedule`, startTime: '09:00', endTime: '17:00',
        breakMinutes: 60, graceMinutes: 5, restDays: 'SAT,SUN',
      });
      expectOk(schedule);
      const scheduleAssignment = await callApi('POST', '/api/employee-schedules', {
        employeeId: employee.id, scheduleId: schedule.body.id, effectiveFrom: today,
      });
      expectOk(scheduleAssignment);
    }
    for (const [logType, time] of [['IN', '09:00:00'], ['OUT', '17:00:00']]) {
      const log = await callApi('POST', '/api/attendance/manual', {
        employeeId: employee.id, logType, logTime: new Date(`${today}T${time}+08:00`).toISOString(), remarks: 'E2E payroll journey',
      });
      expectOk(log);
    }
  }
  const dependent = await callApi('POST', '/api/employee-dependents', {
    employeeId: employees[0].id, fullName: `Journey Dependent ${runId}`,
    relationship: 'CHILD', birthDate: '2018-04-12',
  });
  expectOk(dependent);

  // Provision an independent HR approver in the isolated test workspace. The
  // owner remains the employee requester; approval routing forbids self-approval.
  const accounts = await callApi('GET', '/api/v1/admin/users');
  expectOk(accounts);
  const ownerAccount = accounts.body.find(account => account.email === sample.email);
  expect(ownerAccount, 'owner identity available for E2E self-service').toBeTruthy();
  const approverEmail = `hr.approver.${runId}@e2e.apexhrm.test`;
  const approverPassword = `Apex-E2E-Approver-${runId}!`;
  const invitation = await callApi('POST', '/api/v1/admin/invitations', { email: approverEmail, role: 'HR_ADMIN' });
  expectOk(invitation);
  const invitationToken = new URL(invitation.body.acceptanceUrl, 'http://localhost').searchParams.get('token');
  const acceptedInvite = await callApi('POST', '/api/v1/invitations/accept', { token: invitationToken, password: approverPassword });
  expectOk(acceptedInvite);
  const approvers = await callApi('GET', '/api/v1/admin/users');
  expectOk(approvers);
  const approver = approvers.body.find(account => account.email === approverEmail);
  expect(approver, 'independent HR approver identity').toBeTruthy();
  const ownerRole = await callApi('PUT', `/api/v1/admin/users/${approver.id}/roles/TENANT_ADMIN`);
  expectOk(ownerRole);
  const initialApproverLogin = await page.evaluate(async input => {
    const csrfResponse = await fetch('/api/v1/security/csrf', { credentials: 'same-origin' });
    const csrf = await csrfResponse.json();
    const response = await fetch('/api/v1/security/tokens', {
      method: 'POST', credentials: 'same-origin',
      headers: { [csrf.headerName || 'X-CSRF-TOKEN']: csrf.token, Authorization: `Basic ${btoa(`${input.email}:${input.password}`)}` },
    });
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }, { email: approverEmail, password: approverPassword });
  expectOk(initialApproverLogin);
  const unenrolledApproverToken = initialApproverLogin.body.value;
  const approverEnrollment = await callAs(unenrolledApproverToken, 'POST', '/api/v1/security/mfa/enroll');
  expectOk(approverEnrollment);
  const approverCounter = Math.floor(Date.now() / 1000 / 30);
  const approverMfaConfirmed = await callAs(unenrolledApproverToken, 'POST', '/api/v1/security/mfa/confirm', {
    code: totp(approverEnrollment.body.secretHex, approverCounter),
  });
  expectOk(approverMfaConfirmed);
  const approverLogin = await page.evaluate(async input => {
    const csrfResponse = await fetch('/api/v1/security/csrf', { credentials: 'same-origin' });
    const csrf = await csrfResponse.json();
    const response = await fetch('/api/v1/security/tokens', {
      method: 'POST', credentials: 'same-origin',
      headers: { [csrf.headerName || 'X-CSRF-TOKEN']: csrf.token, Authorization: `Basic ${btoa(`${input.email}:${input.password}`)}`, 'X-TOTP-Code': input.code },
    });
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }, { email: approverEmail, password: approverPassword, code: totp(approverEnrollment.body.secretHex, approverCounter + 1) });
  expectOk(approverLogin);
  const approverToken = approverLogin.body.value;
  const link = await callApi('PUT', '/api/v1/learning/accounts', {
    identityId: ownerAccount.id, employeeId: employees[0].id,
  });
  expectOk(link);

  await navigateTo('Timekeeping', 'Leave management');
  await page.getByRole('tab', { name: 'Benefit profiles' }).click();
  const benefitAssignmentForm = page.locator('form.leave-file-form').filter({ has: page.getByRole('button', { name: 'Assign profile' }) });
    await openFormEditor(page, benefitAssignmentForm, 'Assign leave profile');
  await benefitAssignmentForm.getByLabel('Employee').selectOption(employees[0].id);
  await benefitAssignmentForm.getByLabel('Benefit profile').selectOption(benefitProfile.id);
  await benefitAssignmentForm.getByLabel('Effective from').fill(priorDay);
  await benefitAssignmentForm.getByLabel('Reason').fill('Initial local employee prorated leave entitlement');
  await benefitAssignmentForm.getByRole('button', { name: 'Assign profile' }).click();
  await expect(page.getByRole('status')).toContainText('Benefit profile assigned. Effective-date prorated leave credits were added to the ledger.');
  await closeWorkspaceDialog(page);
  const leaveDate = new Date(`${today}T00:00:00Z`);
  leaveDate.setUTCDate(leaveDate.getUTCDate() + 3);
  const requestedLeaveDate = leaveDate.toISOString().slice(0, 10);
  await navigateTo('Personnel', 'My workspace');
  await page.getByRole('tab', { name: 'Request leave' }).click();
  const leaveRequestForm = page.locator('form.announcements-editor').filter({ has: page.getByRole('heading', { name: 'Request leave', exact: true }) });
    await openFormEditor(page, leaveRequestForm, 'File leave request');
  await leaveRequestForm.getByLabel('Leave type').selectOption(leaveType.id);
  await leaveRequestForm.getByLabel('From').fill(requestedLeaveDate);
  await leaveRequestForm.getByLabel('To').fill(requestedLeaveDate);
  await leaveRequestForm.getByLabel('Days').fill('1');
  await leaveRequestForm.getByLabel('Reason').fill('E2E leave approval workflow');
  const leaveResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/self-service/leave-requests') && response.request().method() === 'POST');
  await leaveRequestForm.getByRole('button', { name: 'Submit leave request' }).click();
  await closeWorkspaceDialog(page);
  const leaveResult = await leaveResponse;
  const leaveRequest = { status: leaveResult.status(), body: await leaveResult.json() };
  expectOk(leaveRequest);
  await page.getByRole('tab', { name: 'Request overtime' }).click();
  const overtimeRequestForm = page.locator('form.announcements-editor').filter({ has: page.getByRole('heading', { name: 'Request overtime', exact: true }) });
    await openFormEditor(page, overtimeRequestForm, 'File overtime request');
  await overtimeRequestForm.getByLabel('Work date').fill(today);
  await overtimeRequestForm.getByLabel('Minutes').fill('60');
  await overtimeRequestForm.getByLabel('Reason').fill('E2E overtime approval workflow');
  const overtimeResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/self-service/overtime-requests') && response.request().method() === 'POST');
  await overtimeRequestForm.getByRole('button', { name: 'Submit overtime request' }).click();
  await closeWorkspaceDialog(page);
  const overtimeResult = await overtimeResponse;
  const overtimeRequest = { status: overtimeResult.status(), body: await overtimeResult.json() };
  expectOk(overtimeRequest);
  for (const [processCode, request, approvedMinutes] of [
    ['LEAVE', leaveRequest.body, null], ['OVERTIME', overtimeRequest.body, 60],
  ]) {
    let status = 'PENDING';
    for (let step = 0; step < 10 && status === 'PENDING'; step += 1) {
      const inbox = await callAs(approverToken, 'GET', '/api/v1/approvals/inbox');
      expectOk(inbox);
      const task = inbox.body.find(item => item.instance_id === request.approvalId && item.process_code === processCode);
      expect(task, `${processCode} approval step ${step + 1} routed to the independent approver`).toBeTruthy();
      const decision = await callAs(approverToken, 'POST', `/api/v1/approvals/tasks/${task.task_id}/decision`, {
        decision: 'APPROVE', comments: `E2E independent approval step ${step + 1}`, approvedMinutes,
      });
      expectOk(decision);
      status = decision.body.status;
    }
    expect(status).toBe('APPROVED');
  }

  const dtr = await callApi('POST', '/api/dtr/process', { from: today, to: today, employeeId: null });
  expectOk(dtr);
  let weeklyRunId;
  for (const [group, from, through] of [[weeklyGroupResult.body, weekStart, weekEnd], [dailyGroupResult.body, today, today]]) {
    const generated = await callApi('POST', '/api/pay-periods/generate', {
      payGroupId: group.id, from, through, payDateOffsetDays: 5,
    });
    expectOk(generated);
    const periods = await callApi('GET', '/api/pay-periods?size=500&page=0');
    expectOk(periods);
    const period = (periods.body.content || []).find(item => item.payGroupId === group.id && item.startDate === from && item.endDate === through);
    expect(period, `pay period ${from} to ${through} for ${group.frequency}`).toBeTruthy();
    let createdRun;
    if (group.frequency === 'WEEKLY') {
      await navigateTo('Payroll', 'Payroll runs');
      await page.getByLabel('Open pay period').selectOption(period.id);
      const createResponse = page.waitForResponse(response => response.url().endsWith('/api/payroll/runs') && response.request().method() === 'POST');
      await page.getByRole('button', { name: 'Create payroll run' }).click();
      createdRun = { status: (await createResponse).status(), body: await (await createResponse).json() };
      expectOk(createdRun);
      const computeResponse = page.waitForResponse(response => response.url().endsWith(`/api/payroll/runs/${createdRun.body.id}/compute`));
      await page.getByRole('button', { name: 'Compute payroll' }).click();
      expect((await computeResponse).status()).toBeLessThan(300);
      const reviewResponse = page.waitForResponse(response => response.url().endsWith(`/api/payroll/runs/${createdRun.body.id}/review`));
      await page.getByRole('button', { name: 'Mark reviewed' }).click();
      expect((await reviewResponse).status()).toBeLessThan(300);
      page.once('dialog', dialog => void dialog.accept());
      const approveResponse = page.waitForResponse(response => response.url().endsWith(`/api/payroll/runs/${createdRun.body.id}/approve`));
      await page.getByRole('button', { name: 'Approve run' }).click();
      expect((await approveResponse).status()).toBeLessThan(300);
      page.once('dialog', dialog => void dialog.accept());
      const publishResponse = page.waitForResponse(response => response.url().endsWith(`/api/payroll/runs/${createdRun.body.id}/publish-for-employee-review`));
      await page.getByRole('button', { name: 'Publish payslips for employee review' }).click();
      expect((await publishResponse).status()).toBeLessThan(300);
      weeklyRunId = createdRun.body.id;
    } else {
      createdRun = await callApi('POST', '/api/payroll/runs', { payPeriodId: period.id, runType: 'REGULAR' });
      expectOk(createdRun);
      const computedRun = await callApi('POST', `/api/payroll/runs/${createdRun.body.id}/compute`);
      expectOk(computedRun);
      expect(computedRun.body.status).toBe('COMPUTED');
    }
    const runEmployees = await callApi('GET', `/api/payroll/runs/${createdRun.body.id}/employees`);
    expectOk(runEmployees);
    expect(runEmployees.body.some(item => employees.some(employee => employee.id === item.employeeId))).toBeTruthy();
  }

  // Link the owner login to this isolated employee, then review and acknowledge
  // the published payslip in the actual employee self-service screen.
  const portal = await callApi('GET', '/api/v1/self-service/me');
  expectOk(portal);
  expect(portal.body.leaveRequests.find(item => item.id === leaveRequest.body.id)?.status).toBe('APPROVED');
  expect(portal.body.overtimeRequests.find(item => item.id === overtimeRequest.body.id)?.status).toBe('APPROVED');
  const weeklyPayslip = portal.body.payslips.find(slip => slip.run_type === 'REGULAR' && slip.run_status === 'EMPLOYEE_REVIEW');
  expect(weeklyPayslip, 'published weekly payslip in employee history').toBeTruthy();
  await navigateTo('Personnel', 'My workspace');
  await expect(page.getByRole('heading', { name: 'Payslip history', exact: true })).toBeVisible();
  const payslipCard = page.locator('.announcement-card').filter({ has: page.getByRole('button', { name: 'Acknowledge this payslip' }) }).first();
  await payslipCard.getByRole('button', { name: 'Acknowledge this payslip' }).click();
  await expect(page.getByRole('status')).toContainText('Payslip acknowledged.');
  const acknowledged = await callApi('GET', '/api/v1/self-service/me');
  expectOk(acknowledged);
  expect(acknowledged.body.payslips.find(slip => slip.id === weeklyPayslip.id)?.acknowledged_at).toBeTruthy();
  const counts = await callApi('GET', `/api/payroll/runs/${weeklyRunId}/employee-acknowledgements`);
  expectOk(counts);
  expect(counts.body.pending).toBe(0);
  await navigateTo('Payroll', 'Payroll runs');
  const weeklyRunRow = page.getByRole('row').filter({ hasText: `E2E weekly ${runId}` });
  await weeklyRunRow.getByRole('button').first().click();
  page.once('dialog', dialog => void dialog.accept());
  const closeResponse = page.waitForResponse(response => response.url().endsWith(`/api/payroll/runs/${weeklyRunId}/lock`));
  await page.getByRole('button', { name: 'Close payroll after acknowledgements' }).click();
  expect((await closeResponse).status()).toBeLessThan(300);
  const closedRuns = await callApi('GET', '/api/payroll-runs?size=500&sort=createdAt,desc');
  expectOk(closedRuns);
  const closed = closedRuns.body.content.find(run => run.id === weeklyRunId);
  expect(closed?.status).toBe('LOCKED');

  const nextWeekStartDate = new Date(`${weekEnd}T00:00:00Z`);
  nextWeekStartDate.setUTCDate(nextWeekStartDate.getUTCDate() + 1);
  const nextWeekStart = nextWeekStartDate.toISOString().slice(0, 10);
  const nextWeekEndDate = new Date(`${nextWeekStart}T00:00:00Z`);
  nextWeekEndDate.setUTCDate(nextWeekEndDate.getUTCDate() + 6);
  const nextWeekEnd = nextWeekEndDate.toISOString().slice(0, 10);
  const nextPeriodGenerate = await callApi('POST', '/api/pay-periods/generate', {
    payGroupId: weeklyGroupResult.body.id, from: nextWeekStart, through: nextWeekEnd, payDateOffsetDays: 5,
  });
  expectOk(nextPeriodGenerate);
  const allPeriods = await callApi('GET', '/api/pay-periods?size=500&page=0');
  expectOk(allPeriods);
  const finalPayPeriod = (allPeriods.body.content || []).find(item => item.payGroupId === weeklyGroupResult.body.id && item.startDate === nextWeekStart && item.endDate === nextWeekEnd);
  expect(finalPayPeriod, 'next open weekly 13th-month and final-pay period').toBeTruthy();

  // Calculate 13th-month pay from the locked basic-pay YTD lines, then issue its
  // own employee-visible payslip and require acknowledgement before lock.
  const thirteenthRun = await callApi('POST', '/api/payroll/runs', {
    payPeriodId: finalPayPeriod.id, runType: 'THIRTEENTH_MONTH',
  });
  expectOk(thirteenthRun);
  const thirteenthComputed = await callApi('POST', `/api/payroll/runs/${thirteenthRun.body.id}/compute`);
  expectOk(thirteenthComputed);
  expect(thirteenthComputed.body.status).toBe('COMPUTED');
  const thirteenthLines = await callApi('GET', `/api/payroll/runs/${thirteenthRun.body.id}/lines`);
  expectOk(thirteenthLines);
  const thirteenthPayLines = thirteenthLines.body.filter(line => line.itemCode === 'THIRTEENTH_MONTH');
  expect(thirteenthPayLines.length).toBeGreaterThan(0);
  expect(thirteenthPayLines.reduce((total, line) => total + Number(line.amount), 0)).toBeGreaterThan(0);
  const thirteenthReviewed = await callApi('POST', `/api/payroll/runs/${thirteenthRun.body.id}/review`);
  expectOk(thirteenthReviewed);
  const thirteenthApproved = await callApi('POST', `/api/payroll/runs/${thirteenthRun.body.id}/approve`);
  expectOk(thirteenthApproved);
  const thirteenthPublished = await callApi('POST', `/api/payroll/runs/${thirteenthRun.body.id}/publish-for-employee-review`);
  expectOk(thirteenthPublished);
  const thirteenthPortal = await callApi('GET', '/api/v1/self-service/me');
  expectOk(thirteenthPortal);
  const thirteenthPayslip = thirteenthPortal.body.payslips.find(slip => slip.run_type === 'THIRTEENTH_MONTH' && slip.run_status === 'EMPLOYEE_REVIEW');
  expect(thirteenthPayslip, '13th-month payslip published to employee self-service').toBeTruthy();
  const thirteenthAck = await callApi('POST', `/api/v1/self-service/payslips/${thirteenthPayslip.id}/acknowledge`);
  expectOk(thirteenthAck);
  const thirteenthCounts = await callApi('GET', `/api/payroll/runs/${thirteenthRun.body.id}/employee-acknowledgements`);
  expectOk(thirteenthCounts);
  expect(thirteenthCounts.body.pending).toBe(0);
  const thirteenthClosed = await callApi('POST', `/api/payroll/runs/${thirteenthRun.body.id}/lock`);
  expectOk(thirteenthClosed);
  expect(thirteenthClosed.body.status).toBe('LOCKED');

  // Complete a real separation, with a distinct authorized approver and exit
  // clearance, then settle final pay in the next open weekly payroll period.
  const separation = await callApi('POST', `/api/v1/personnel/employees/${employees[0].id}/hr-cases`, {
    type: 'SEPARATION', effectiveDate: today, reason: 'E2E final pay settlement',
  });
  expectOk(separation);
  const clearance = await callApi('POST', `/api/v1/personnel/employees/${employees[0].id}/hr-cases/${separation.body.id}/clearances`, {
    code: 'E2E_RETURN', label: 'Return company property', required: true,
  });
  expectOk(clearance);
  const cleared = await callApi('PATCH', `/api/v1/personnel/employees/${employees[0].id}/hr-cases/${separation.body.id}/clearances/${clearance.body.id}`, { status: 'CLEARED' });
  expectOk(cleared);
  const separationDecision = await callAs(approverToken, 'POST', `/api/v1/personnel/employees/${employees[0].id}/hr-cases/${separation.body.id}/decision?decision=APPROVED`);
  expectOk(separationDecision);
  expect(separationDecision.body.status).toBe('APPROVED');
  const finalPayRun = await callApi('POST', '/api/payroll/final-pay', {
    payPeriodId: finalPayPeriod.id, employeeId: employees[0].id,
  });
  expectOk(finalPayRun);
  const finalPayComputed = await callApi('POST', `/api/payroll/runs/${finalPayRun.body.id}/compute`);
  expectOk(finalPayComputed);
  expect(finalPayComputed.body.status).toBe('COMPUTED');
  const finalPayReviewed = await callApi('POST', `/api/payroll/runs/${finalPayRun.body.id}/review`);
  expectOk(finalPayReviewed);
  const finalPayApproved = await callApi('POST', `/api/payroll/runs/${finalPayRun.body.id}/approve`);
  expectOk(finalPayApproved);
  const finalPayPublished = await callApi('POST', `/api/payroll/runs/${finalPayRun.body.id}/publish-for-employee-review`);
  expectOk(finalPayPublished);
  const finalPayPortal = await callApi('GET', '/api/v1/self-service/me');
  expectOk(finalPayPortal);
  const finalPayslip = finalPayPortal.body.payslips.find(slip => slip.run_type === 'FINAL_PAY' && slip.run_status === 'EMPLOYEE_REVIEW');
  expect(finalPayslip, 'final-pay payslip published to employee self-service').toBeTruthy();
  const finalPayAck = await callApi('POST', `/api/v1/self-service/payslips/${finalPayslip.id}/acknowledge`);
  expectOk(finalPayAck);
  const finalPayCounts = await callApi('GET', `/api/payroll/runs/${finalPayRun.body.id}/employee-acknowledgements`);
  expectOk(finalPayCounts);
  expect(finalPayCounts.body.pending).toBe(0);
  const finalPayClosed = await callApi('POST', `/api/payroll/runs/${finalPayRun.body.id}/lock`);
  expectOk(finalPayClosed);
  expect(finalPayClosed.body.status).toBe('LOCKED');

  // Prepare an annual certificate row from finalized regular, 13th-month, and
  // final-pay lines. The application intentionally labels this as a review aid;
  // it is not the official BIR Form 2316 or a signed filing artifact.
  const certificateYear = today.slice(0, 4);
  const incompleteCertificate = await callApi('GET', `/api/payroll/reports/annual-tax-certificate?year=${certificateYear}&employeeId=${employees[0].id}`);
  expectOk(incompleteCertificate);
  expect(incompleteCertificate.body.readyForReview).toBe(false);
  expect(incompleteCertificate.body.missingFields).toContain('Employee TIN');
  const employeeTaxProfile = await callApi('PUT', `/api/payroll/employees/${employees[0].id}/tax-profile`, {
    tin: '123-456-789', minimumWageEarner: true, employeeRdoCode: '050',
    registeredZipCode: '1000', localHomeAddress: '123 E2E Test Street, Manila', localHomeZipCode: '1000',
    foreignAddress: null, mobile: '09171234567', statutoryMinimumWageDaily: 750, statutoryMinimumWageMonthly: null,
  });
  expectOk(employeeTaxProfile);
  const dailyEmployeeTaxProfile = await callApi('PUT', `/api/payroll/employees/${employees[1].id}/tax-profile`, {
    tin: '987-123-456', minimumWageEarner: true, employeeRdoCode: '050',
    registeredZipCode: '1000', localHomeAddress: '456 E2E Daily Street, Manila', localHomeZipCode: '1000',
    foreignAddress: null, mobile: '09171234568', statutoryMinimumWageDaily: 750, statutoryMinimumWageMonthly: null,
  });
  expectOk(dailyEmployeeTaxProfile);
  const employeeTaxDetails = await callApi('PUT', `/api/employees/${employees[0].id}`, {
    address: '123 E2E Test Street, Manila', birthDate: '1990-05-14',
  });
  expectOk(employeeTaxDetails);
  const employerTaxDetails = await callApi('PUT', `/api/companies/${company.id}`, {
    legalName: 'Apex E2E Test Employer Inc.', tin: '987-654-321',
    address: '100 E2E Business Avenue, Makati City', rdoCode: '050',
  });
  expectOk(employerTaxDetails);
  const employer2316Profile = await callApi('PUT', `/api/payroll/companies/${company.id}/tax-profile`, {
    companyId: company.id, legalName: 'Apex E2E Test Employer Inc.', tin: '987-654-321',
    address: '100 E2E Business Avenue, Makati City', rdoCode: '050', zipCode: '1226',
    employerType: 'PRIVATE', mainEmployer: true,
  });
  expectOk(employer2316Profile);
  const peraProfile = await callApi('PUT', `/api/payroll/employees/${employees[0].id}/pera-tax-credit?taxYear=${certificateYear}`, {
    employeeId: employees[0].id, taxYear: Number(certificateYear), qualifiedContributions: 20000,
    overseasFilipino: false, taxCreditAmount: 1000, certificateNumber: `E2E-PERA-${runId}`,
    certificateReceived: true,
  });
  expectOk(peraProfile);
  const annualCertificate = await callApi('GET', `/api/payroll/reports/annual-tax-certificate?year=${certificateYear}&employeeId=${employees[0].id}`);
  expectOk(annualCertificate);
  expect(annualCertificate.body.readyForReview).toBe(true);
  expect(annualCertificate.body.grossCompensation).toBeGreaterThan(0);
  expect(annualCertificate.body.taxableRuns).toBeGreaterThanOrEqual(3);
  const annualCsv = await page.evaluate(async input => {
    const token = sessionStorage.getItem('apexHrm.accessToken');
    const response = await fetch(input.path, { headers: { Authorization: `Bearer ${token}` } });
    return { status: response.status, contentType: response.headers.get('content-type'), employeeCount: response.headers.get('X-Report-Employee-Count'), schedule2Count: response.headers.get('X-Report-Schedule2-Count'), body: await response.text() };
  }, { path: `/api/payroll/reports/annual-tax-certificate.csv?year=${certificateYear}&employeeId=${employees[0].id}` });
  expect(annualCsv.status).toBe(200);
  expect(annualCsv.contentType).toContain('text/csv');
  expect(annualCsv.body).toContain('Ready for review');
  expect(annualCsv.body).toContain('true');

  const formPreview = await callApi('GET', `/api/payroll/reports/bir-2316-draft?year=${certificateYear}&employeeId=${employees[0].id}`);
  expectOk(formPreview);
  expect(formPreview.body.peraTaxCreditProfile.taxCreditAmount).toBe(1000);
  expect(formPreview.body.peraTaxCredit).toBeGreaterThanOrEqual(0);
  expect(formPreview.body.peraTaxCredit).toBeLessThanOrEqual(1000);
  expect(formPreview.body.totalTaxesWithheld).toBe(formPreview.body.presentEmployerTaxWithheld
    + formPreview.body.previousEmployerTaxWithheld + formPreview.body.peraTaxCredit);
  expect(formPreview.body.peraTaxCreditProfile.certificateNumber).toBe(`E2E-PERA-${runId}`);
  expect(formPreview.body.compensationItems['29']).toBeGreaterThan(0);
  expect(formPreview.body.compensationItems['38']).toBeGreaterThan(0);
  expect(formPreview.body.taxableCompensationPresentEmployer).toBeGreaterThanOrEqual(0);
  const blockedPrint = await callApi('GET', `/api/payroll/reports/bir-2316-issued?year=${certificateYear}&employeeId=${employees[0].id}`);
  expect(blockedPrint.status).toBe(422);
  const submittedReview = await callApi('POST', `/api/payroll/reports/bir-2316-reviews?year=${certificateYear}&employeeId=${employees[0].id}`);
  expectOk(submittedReview);
  expect(submittedReview.body.status).toBe('PENDING');
  const selfApproval = await callApi('POST', `/api/payroll/reports/bir-2316-reviews/${submittedReview.body.id}/decision`, { decision: 'APPROVE' });
  expect(selfApproval.status).toBe(403);
  const approvedReview = await callAs(approverToken, 'POST', `/api/payroll/reports/bir-2316-reviews/${submittedReview.body.id}/decision`, {
    decision: 'APPROVE', note: 'Reviewed payroll, employee/employer profile, prior-employer data, and PERA TCC evidence.',
  });
  expectOk(approvedReview);
  expect(approvedReview.body.status).toBe('APPROVED');
  const issuedForm = await callApi('GET', `/api/payroll/reports/bir-2316-issued?year=${certificateYear}&employeeId=${employees[0].id}`);
  expectOk(issuedForm);
  expect(issuedForm.body.reviewStatus).toBe('APPROVED');
  const alphalist = await callApi('GET', `/api/payroll/reports/bir-1604c-reconciliation?year=${certificateYear}&companyId=${employees[0].companyId}`);
  expectOk(alphalist);
  expect(alphalist.body.employeeCount).toBeGreaterThan(0);
  expect(alphalist.body.approved2316Count).toBeGreaterThan(0);
  expect(alphalist.body.schedule2Count).toBeGreaterThan(0);
  const weeklyAlphalistRow = alphalist.body.rows.find(row => row.employeeName.includes(`Journey${runId}`) && row.employeeName.includes('Nova'));
  expect(weeklyAlphalistRow).toBeTruthy();
  expect(weeklyAlphalistRow.form2316Status).toBe('APPROVED');
  expect(weeklyAlphalistRow.schedule).toBe('SCHEDULE_2_MWE');
  const blockedDat = await callApi('GET', `/api/payroll/reports/bir-1604c.dat?year=${certificateYear}&companyId=${employees[0].companyId}`);
  expect(blockedDat.status).toBe(422);
  expect(blockedDat.body.message || blockedDat.body.detail).toContain('BIR DAT export blocked');
  const alphalistCsv = await page.evaluate(async path => {
    const token = sessionStorage.getItem('apexHrm.accessToken');
    const response = await fetch(path, { headers: { Authorization: `Bearer ${token}` } });
    return { status: response.status, contentType: response.headers.get('content-type'), employeeCount: response.headers.get('X-Report-Employee-Count'), schedule2Count: response.headers.get('X-Report-Schedule2-Count'), body: await response.text() };
  }, `/api/payroll/reports/bir-1604c-reconciliation.csv?year=${certificateYear}&companyId=${employees[0].companyId}`);
  expect(alphalistCsv.status).toBe(200);
  expect(alphalistCsv.contentType).toContain('text/csv');
  expect(Number(alphalistCsv.employeeCount)).toBeGreaterThan(0);
  expect(Number(alphalistCsv.schedule2Count)).toBeGreaterThan(0);
  expect(alphalistCsv.body).toContain('Internal reconciliation workpaper');
  expect(alphalistCsv.body).toContain('Form 2316 Review Status');
  const changedTaxProfile = await callApi('PUT', `/api/payroll/employees/${employees[0].id}/tax-profile`, {
    ...employeeTaxProfile.body, localHomeAddress: 'Changed after approval',
  });
  expectOk(changedTaxProfile);
  const staleAlphalist = await callApi('GET', `/api/payroll/reports/bir-1604c-reconciliation?year=${certificateYear}&companyId=${employees[0].companyId}`);
  expectOk(staleAlphalist);
  expect(staleAlphalist.body.rows.find(row => row.employeeName.includes(`Journey${runId}`) && row.employeeName.includes('Nova')).form2316Status).toBe('STALE');
  const stalePrint = await callApi('GET', `/api/payroll/reports/bir-2316-issued?year=${certificateYear}&employeeId=${employees[0].id}`);
  expect(stalePrint.status).toBe(422);
  const resubmittedReview = await callApi('POST', `/api/payroll/reports/bir-2316-reviews?year=${certificateYear}&employeeId=${employees[0].id}`);
  expectOk(resubmittedReview);
  expect(resubmittedReview.body.status).toBe('PENDING');
  const rejectedReview = await callAs(approverToken, 'POST', `/api/payroll/reports/bir-2316-reviews/${resubmittedReview.body.id}/decision`, {
    decision: 'REJECT', note: 'E2E rejection path',
  });
  expectOk(rejectedReview);
  expect(rejectedReview.body.status).toBe('REJECTED');
});

async function signIn(page, sample) {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Sign in to your workspace' })).toBeVisible();
  await page.getByRole('tab', { name: 'Username & password' }).click();
  await page.getByLabel('Email or username').fill(sample.username);
  await page.getByLabel('Password', { exact: true }).fill(sample.password);
  const tokenResponse = page.waitForResponse(response =>
    response.url().endsWith('/api/v1/security/tokens') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Sign in' }).click();
  if ((await tokenResponse).status() === 428) {
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
