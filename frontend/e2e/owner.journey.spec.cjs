const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const { totp } = require('./totp.cjs');

test('workspace owner signs up, secures the account, and tours the HRM', async ({ page }, testInfo) => {
  test.setTimeout(Number(process.env.E2E_TIMEOUT_MS ?? 180_000));
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
      const learningAlert = page.getByRole('alert');
      if (await learningAlert.count()) await expect(learningAlert).toContainText('Your learning profile has not been linked to your employee record.');
    } else {
      await expect(page.getByRole('alert')).toHaveCount(0);
    }
    if (section === 'Employee directory') expect(reactKeyWarnings).toHaveLength(0);
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

  // Exercise the personnel-to-timekeeping-to-payroll bridge for weekly and daily staff.
  const today = new Date().toISOString().slice(0, 10);
  const hireDate = new Date(`${today}T00:00:00Z`);
  hireDate.setUTCDate(hireDate.getUTCDate() - 1);
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
  const weeklyGroupResult = await callApi('POST', '/api/pay-groups', {
    companyId: company.id, code: `E2E-W-${runId}`, name: `E2E weekly ${runId}`, frequency: 'WEEKLY',
    daysFactor: 261, hoursPerDay: 8, contributionSchedule: 'EVERY_CUTOFF', taxMethod: 'STRICT',
  });
  expectOk(weeklyGroupResult);
  const dailyGroupResult = await callApi('POST', '/api/pay-groups', {
    companyId: company.id, code: `E2E-D-${runId}`, name: `E2E daily ${runId}`, frequency: 'DAILY',
    daysFactor: 261, hoursPerDay: 8, contributionSchedule: 'EVERY_CUTOFF', taxMethod: 'STRICT',
  });
  expectOk(dailyGroupResult);
  const employees = [];
  for (const kind of ['weekly', 'daily']) {
    const created = await callApi('POST', '/api/v1/personnel/employees', {
      companyId: company.id, employeeNumber: `E2E-${kind.toUpperCase()}-${runId}`,
      firstName: kind === 'weekly' ? 'Weekly' : 'Daily', lastName: `Journey${runId}`, hireDate: priorDay,
    });
    expectOk(created);
    const employee = created.body;
    employees.push(employee);
    const compensation = await callApi('POST', `/api/v1/personnel/employees/${employee.id}/compensation`, {
      effectiveDate: today, amount: kind === 'weekly' ? 30000 : 750, payBasis: kind === 'weekly' ? 'MONTHLY' : 'DAILY', reason: 'E2E payroll journey',
    });
    expectOk(compensation);
    const group = kind === 'weekly' ? weeklyGroupResult.body : dailyGroupResult.body;
    const assignment = await callApi('POST', `/api/v1/personnel/employees/${employee.id}/pay-group-assignments`, {
      payGroupId: group.id, effectiveFrom: today, remarks: 'E2E payroll frequency assignment',
    });
    expectOk(assignment);
    const schedule = await callApi('POST', '/api/work-schedules', {
      code: `E2E-${kind.toUpperCase()}-${runId}`, name: `E2E ${kind} schedule`, startTime: '09:00', endTime: '17:00',
      breakMinutes: 60, graceMinutes: 5, restDays: 'SAT,SUN',
    });
    expectOk(schedule);
    const scheduleAssignment = await callApi('POST', '/api/employee-schedules', {
      employeeId: employee.id, scheduleId: schedule.body.id, effectiveFrom: today,
    });
    expectOk(scheduleAssignment);
    for (const [logType, time] of [['IN', '09:00:00'], ['OUT', '17:00:00']]) {
      const log = await callApi('POST', '/api/attendance/manual', {
        employeeId: employee.id, logType, logTime: new Date(`${today}T${time}+08:00`).toISOString(), remarks: 'E2E payroll journey',
      });
      expectOk(log);
    }
  }

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

  const configureWorkflow = async processCode => {
    const workflow = await callApi('POST', '/api/v1/approvals/workflows', {
      name: `E2E ${processCode} approval ${runId}`, processCode,
    });
    expectOk(workflow);
    const steps = await callApi('PUT', `/api/v1/approvals/workflows/${workflow.body.id}/versions/${workflow.body.versionId}/steps`, [
      { stepNo: 1, label: 'Independent HR approval', actionType: 'APPROVE', approverType: 'ROLE', roleCode: 'HR_ADMIN' },
    ]);
    expectOk(steps);
    const published = await callApi('POST', `/api/v1/approvals/workflows/${workflow.body.id}/versions/${workflow.body.versionId}/publish`);
    expectOk(published);
    const assignment = await callApi('PUT', '/api/v1/approvals/assignments', {
      processCode, companyId: null, departmentId: null, workflowVersionId: workflow.body.versionId,
    });
    expectOk(assignment);
  };
  for (const processCode of ['LEAVE', 'OVERTIME']) await configureWorkflow(processCode);
  const leaveTypes = await callApi('GET', '/api/leave-types');
  expectOk(leaveTypes);
  let leaveType = leaveTypes.body[0];
  if (!leaveType) {
    const createdType = await callApi('POST', '/api/leave-types', {
      code: `E2E-${runId}`, name: `E2E leave ${runId}`, paid: true, annualEntitlement: null,
      hireProrationEnabled: false, convertible: false,
    });
    expectOk(createdType);
    leaveType = createdType.body;
  }
  const leaveDate = new Date(`${today}T00:00:00Z`);
  leaveDate.setUTCDate(leaveDate.getUTCDate() + 3);
  const requestedLeaveDate = leaveDate.toISOString().slice(0, 10);
  const leaveRequest = await callApi('POST', '/api/v1/self-service/leave-requests', {
    leaveTypeId: leaveType.id, dateFrom: requestedLeaveDate, dateTo: requestedLeaveDate,
    days: 1, halfDay: false, reason: 'E2E leave approval workflow',
  });
  expectOk(leaveRequest);
  const overtimeRequest = await callApi('POST', '/api/v1/self-service/overtime-requests', {
    workDate: today, minutes: 60, reason: 'E2E overtime approval workflow',
  });
  expectOk(overtimeRequest);
  for (const [processCode, request, approvedMinutes] of [
    ['LEAVE', leaveRequest.body, null], ['OVERTIME', overtimeRequest.body, 60],
  ]) {
    const inbox = await callAs(approverToken, 'GET', '/api/v1/approvals/inbox');
    expectOk(inbox);
    const task = inbox.body.find(item => item.instance_id === request.approvalId && item.process_code === processCode);
    expect(task, `${processCode} approval routed to independent HR user`).toBeTruthy();
    const decision = await callAs(approverToken, 'POST', `/api/v1/approvals/tasks/${task.task_id}/decision`, {
      decision: 'APPROVE', comments: 'E2E independent approval', approvedMinutes,
    });
    expectOk(decision);
    expect(decision.body.status).toBe('APPROVED');
  }

  const dtr = await callApi('POST', '/api/dtr/process', { from: today, to: today, employeeId: null });
  expectOk(dtr);
  const weekStartDate = new Date(`${today}T00:00:00Z`);
  weekStartDate.setUTCDate(weekStartDate.getUTCDate() - ((weekStartDate.getUTCDay() + 6) % 7));
  const weekStart = weekStartDate.toISOString().slice(0, 10);
  const weekEndDate = new Date(`${weekStart}T00:00:00Z`);
  weekEndDate.setUTCDate(weekEndDate.getUTCDate() + 6);
  const weekEnd = weekEndDate.toISOString().slice(0, 10);
  let weeklyRunId;
  for (const [group, from, through] of [[weeklyGroupResult.body, weekStart, weekEnd], [dailyGroupResult.body, today, today]]) {
    const generated = await callApi('POST', '/api/pay-periods/generate', {
      payGroupId: group.id, from, through, payDateOffsetDays: 5,
    });
    expectOk(generated);
    const periods = await callApi('GET', '/api/pay-periods');
    expectOk(periods);
    const period = (periods.body.content || []).find(item => item.payGroupId === group.id && item.startDate === from && item.endDate === through);
    expect(period, `pay period ${from} to ${through} for ${group.frequency}`).toBeTruthy();
    const createdRun = await callApi('POST', '/api/payroll/runs', { payPeriodId: period.id, runType: 'REGULAR' });
    expectOk(createdRun);
    const computedRun = await callApi('POST', `/api/payroll/runs/${createdRun.body.id}/compute`);
    expectOk(computedRun);
    expect(computedRun.body.status).toBe('COMPUTED');
    const runEmployees = await callApi('GET', `/api/payroll/runs/${createdRun.body.id}/employees`);
    expectOk(runEmployees);
    expect(runEmployees.body.some(item => employees.some(employee => employee.id === item.employeeId))).toBeTruthy();
    if (group.frequency === 'WEEKLY') weeklyRunId = createdRun.body.id;
  }

  // Complete the employee review and close lifecycle on the weekly run. Link the
  // owner login to the journey employee in this isolated E2E tenant so the
  // self-service acknowledgement is exercised through the real employee API.
  let reviewed = await callApi('POST', `/api/payroll/runs/${weeklyRunId}/review`);
  expectOk(reviewed);
  let approved = await callApi('POST', `/api/payroll/runs/${weeklyRunId}/approve`);
  expectOk(approved);
  const published = await callApi('POST', `/api/payroll/runs/${weeklyRunId}/publish-for-employee-review`);
  expectOk(published);
  const portal = await callApi('GET', '/api/v1/self-service/me');
  expectOk(portal);
  expect(portal.body.leaveRequests.find(item => item.id === leaveRequest.body.id)?.status).toBe('APPROVED');
  expect(portal.body.overtimeRequests.find(item => item.id === overtimeRequest.body.id)?.status).toBe('APPROVED');
  const weeklyPayslip = portal.body.payslips.find(slip => slip.run_type === 'REGULAR' && slip.run_status === 'EMPLOYEE_REVIEW');
  expect(weeklyPayslip, 'published weekly payslip in employee history').toBeTruthy();
  const acknowledged = await callApi('POST', `/api/v1/self-service/payslips/${weeklyPayslip.id}/acknowledge`);
  expectOk(acknowledged);
  expect(acknowledged.body.acknowledged).toBe(true);
  const counts = await callApi('GET', `/api/payroll/runs/${weeklyRunId}/employee-acknowledgements`);
  expectOk(counts);
  expect(counts.body.pending).toBe(0);
  const closed = await callApi('POST', `/api/payroll/runs/${weeklyRunId}/lock`);
  expectOk(closed);
  expect(closed.body.status).toBe('LOCKED');

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
  const allPeriods = await callApi('GET', '/api/pay-periods');
  expectOk(allPeriods);
  const finalPayPeriod = (allPeriods.body.content || []).find(item => item.payGroupId === weeklyGroupResult.body.id && item.startDate === nextWeekStart && item.endDate === nextWeekEnd);
  expect(finalPayPeriod, 'next open weekly final-pay period').toBeTruthy();
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
