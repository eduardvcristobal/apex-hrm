import { useCallback, useEffect, useMemo, useState } from 'react';

type Page<T> = { content: T[] };
type PayPeriod = { id: string; payGroupId: string; periodOfMonth: number; startDate: string; endDate: string; payDate: string; status: string };
type PayGroup = { id: string; companyId: string; code: string; name: string; frequency: string };
type Run = { id: string; payPeriodId: string; payGroupId: string; runType: string; status: string; computedAt: string | null; approvedAt: string | null; lockedAt: string | null; paidAt: string | null; remarks: string | null; employeeCount: number; totalGross: number; totalDeductions: number; totalNet: number; totalEmployerCost: number };
type PayrollEmployee = { id: string; runId: string; employeeId: string; gross: number; taxableIncome: number; totalDeductions: number; netPay: number; employerCost: number; warnings: string | null };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string };
type PayrollLine = { id: string; payrollEmployeeId: string; employeeId: string; itemCode: string; description: string; category: string; quantity: number | null; rate: number | null; amount: number; taxable: boolean };

const money = (amount: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP' }).format(Number(amount || 0));
const date = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${value}T12:00:00`));
const stages = ['DRAFT', 'COMPUTED', 'REVIEWED', 'APPROVED', 'LOCKED', 'PAID'];

export function PayrollWorkspace({ token, onOpenSecurity }: { token: string; onOpenSecurity: () => void }) {
  const [periods, setPeriods] = useState<PayPeriod[]>([]);
  const [groups, setGroups] = useState<PayGroup[]>([]);
  const [runs, setRuns] = useState<Run[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [selected, setSelected] = useState<Run | null>(null);
  const [runEmployees, setRunEmployees] = useState<PayrollEmployee[]>([]);
  const [lines, setLines] = useState<PayrollLine[]>([]);
  const [periodId, setPeriodId] = useState('');
  const [runType, setRunType] = useState('REGULAR');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [mfaVerified, setMfaVerified] = useState<boolean | null>(null);

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    const response = await fetch(path, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers } });
    if (!response.ok) {
      let message = `Request failed (${response.status}).`;
      try { const body = await response.json() as { message?: string; detail?: string }; message = body.detail || body.message || message; } catch { /* response has no JSON body */ }
      if (response.status === 401) message = 'Your sign-in session has expired. Please sign in again.';
      if (response.status === 403) message = 'Payroll access requires a workspace owner or payroll officer account and an MFA-verified session.';
      throw new Error(message);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [periodPage, groupPage, runPage, employeePage] = await Promise.all([
        request<Page<PayPeriod>>('/api/pay-periods?size=500&sort=startDate,desc'),
        request<Page<PayGroup>>('/api/pay-groups?size=200&sort=name,asc'),
        request<Page<Run>>('/api/payroll-runs?size=500&sort=createdAt,desc'),
        request<Page<Employee>>('/api/payroll/employees?size=1000&sort=employeeNo,asc'),
      ]);
      setPeriods(periodPage.content); setGroups(groupPage.content); setRuns(runPage.content); setEmployees(employeePage.content);
      setPeriodId(current => current || periodPage.content.find(period => period.status === 'OPEN')?.id || '');
      setSelected(current => current ? runPage.content.find(run => run.id === current.id) || null : null);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load payroll.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => {
    void request<{ enabled: boolean; tokenVerified: boolean }>('/api/v1/security/mfa').then(status => setMfaVerified(status.tokenVerified)).catch(() => setMfaVerified(false));
  }, [request]);
  useEffect(() => { void load(); }, [load]);

  const groupById = useMemo(() => new Map(groups.map(group => [group.id, group])), [groups]);
  const periodById = useMemo(() => new Map(periods.map(period => [period.id, period])), [periods]);
  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const selectedPeriod = selected ? periodById.get(selected.payPeriodId) : null;
  const openPeriods = periods.filter(period => period.status === 'OPEN');

  async function selectRun(run: Run) {
    setSelected(run); setError('');
    try {
      const [employeeRows, lineRows] = await Promise.all([
        request<PayrollEmployee[]>(`/api/payroll/runs/${run.id}/employees`),
        request<PayrollLine[]>(`/api/payroll/runs/${run.id}/lines`),
      ]);
      setRunEmployees(employeeRows); setLines(lineRows);
    } catch (reason) { setRunEmployees([]); setLines([]); setError(reason instanceof Error ? reason.message : 'Could not load payroll run details.'); }
  }

  async function createRun() {
    if (!periodId) return;
    setBusy('create'); setError(''); setNotice('');
    try {
      const run = await request<Run>('/api/payroll/runs', { method: 'POST', body: JSON.stringify({ payPeriodId: periodId, runType }) });
      setNotice('Payroll run created. Compute it to build employee pay and statutory deductions.');
      await load(); await selectRun(run);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not create payroll run.'); }
    finally { setBusy(''); }
  }

  async function transition(run: Run, action: string, confirmation?: string) {
    if (confirmation && !window.confirm(confirmation)) return;
    setBusy(action); setError(''); setNotice('');
    try {
      const updated = await request<Run>(`/api/payroll/runs/${run.id}/${action}`, { method: 'POST' });
      setNotice(`Run moved to ${updated.status}.`);
      await load(); await selectRun(updated);
    } catch (reason) { setError(reason instanceof Error ? reason.message : `Could not ${action} this payroll run.`); }
    finally { setBusy(''); }
  }

  const lifecycleAction: Record<string, { action: string; label: string; confirm?: string }> = {
    DRAFT: { action: 'compute', label: 'Compute payroll' },
    COMPUTED: { action: 'review', label: 'Mark reviewed' },
    REVIEWED: { action: 'approve', label: 'Approve run', confirm: 'Approve this payroll run? This records you as the approver.' },
    APPROVED: { action: 'lock', label: 'Lock & issue payslips', confirm: 'Lock this payroll run? Payslips will be issued and the period’s DTR and pay period will be frozen.' },
    LOCKED: { action: 'pay', label: 'Mark paid', confirm: 'Confirm that this payroll run has been paid?' },
  };
  const action = selected ? lifecycleAction[selected.status] : undefined;

  return <section className="timekeeping-workspace payroll-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">PAYROLL</p><h1>Payroll runs</h1><p>Create payroll for an open pay period, review the calculation, and complete the controlled approval lifecycle.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to use payroll</strong><small>Payroll actions require MFA. Enable and verify an authenticator in account security, then sign in again so this session receives payroll access.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics payroll-metrics"><article><span>PAY PERIODS</span><strong>{periods.length}</strong><small>{openPeriods.length} open</small></article><article><span>PAYROLL RUNS</span><strong>{runs.length}</strong><small>{runs.filter(run => !['PAID'].includes(run.status)).length} in progress</small></article><article><span>PENDING APPROVAL</span><strong>{runs.filter(run => run.status === 'REVIEWED').length}</strong><small>Reviewed runs</small></article><article><span>PAID RUNS</span><strong>{runs.filter(run => run.status === 'PAID').length}</strong><small>Completed</small></article></div>
    <div className="payroll-layout">
      <section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">PAY CYCLE</p><h2>Start a payroll run</h2></div></div>
        <p className="payroll-help">Payroll runs use approved attendance, employee compensation, pay group rules, contributions, taxes, and approved adjustments.</p>
        <label className="payroll-field">Open pay period<select value={periodId} onChange={event => setPeriodId(event.target.value)}><option value="">Select a pay period…</option>{openPeriods.map(period => <option key={period.id} value={period.id}>{date(period.startDate)} – {date(period.endDate)} · Pay {date(period.payDate)} · {groupById.get(period.payGroupId)?.name || 'Pay group'}</option>)}</select></label>
        <label className="payroll-field">Run type<select value={runType} onChange={event => setRunType(event.target.value)}><option value="REGULAR">Regular</option><option value="OFF_CYCLE">Off-cycle</option><option value="THIRTEENTH_MONTH">13th month</option></select></label>
        <button type="button" className="security-submit" onClick={() => void createRun()} disabled={!!busy || loading || !periodId || mfaVerified === false}>{busy === 'create' ? 'Creating…' : '＋ Create payroll run'}</button>
        {!periods.length && !loading && <p className="payroll-empty-note">No pay periods are configured yet. Payroll setup requires a pay group and pay periods.</p>}
      </section>
      <section className="payroll-panel payroll-runs-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">PAYROLL REGISTER</p><h2>Recent runs</h2></div><span>{runs.length} run{runs.length === 1 ? '' : 's'}</span></div>
        {loading ? <div className="timekeeping-empty">Loading payroll records…</div> : runs.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table payroll-table"><thead><tr><th>PAY PERIOD</th><th>PAY GROUP</th><th>TYPE</th><th>STATUS</th><th>EMPLOYEES</th><th>NET PAY</th></tr></thead><tbody>{runs.map(run => { const period = periodById.get(run.payPeriodId); return <tr key={run.id} className={selected?.id === run.id ? 'payroll-selected-row' : ''}><td><button type="button" className="payroll-run-link" onClick={() => void selectRun(run)}>{period ? `${date(period.startDate)} – ${date(period.endDate)}` : 'Period details unavailable'}<small>Pay date {period ? date(period.payDate) : '—'}</small></button></td><td>{groupById.get(run.payGroupId)?.name || '—'}</td><td>{run.runType.replaceAll('_', ' ')}</td><td><span className={`timekeeping-status ${run.status === 'PAID' ? 'open' : run.status === 'DRAFT' ? 'warning' : run.status === 'LOCKED' ? 'locked' : 'open'}`}>{run.status}</span></td><td>{run.employeeCount}</td><td><strong>{money(run.totalNet)}</strong></td></tr>; })}</tbody></table></div> : <div className="timekeeping-empty">No payroll runs yet. Select an open pay period to get started.</div>}
      </section>
    </div>
    {selected && <section className="payroll-panel payroll-detail-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">RUN DETAILS</p><h2>{selected.runType.replaceAll('_', ' ')} payroll</h2><p>{selectedPeriod ? `${date(selectedPeriod.startDate)} – ${date(selectedPeriod.endDate)} · Pay date ${date(selectedPeriod.payDate)}` : 'Pay period'}</p></div><div className="payroll-detail-actions">{action && <button type="button" className="security-submit" onClick={() => void transition(selected, action.action, action.confirm)} disabled={!!busy || mfaVerified === false}>{busy === action.action ? 'Saving…' : action.label}</button>}</div></div>
      {selected.remarks && <div className="payroll-warning">{selected.remarks}</div>}
      <div className="payroll-lifecycle">{stages.map((stage, index) => <div key={stage} className={stages.indexOf(selected.status) >= index ? 'complete' : ''}><i>{stages.indexOf(selected.status) >= index ? '✓' : index + 1}</i><span>{stage.replaceAll('_', ' ')}</span></div>)}</div>
      <div className="timekeeping-metrics payroll-totals"><article><span>GROSS PAY</span><strong>{money(selected.totalGross)}</strong></article><article><span>DEDUCTIONS</span><strong>{money(selected.totalDeductions)}</strong></article><article><span>NET PAY</span><strong>{money(selected.totalNet)}</strong></article><article><span>EMPLOYER COST</span><strong>{money(selected.totalEmployerCost)}</strong></article></div>
      <div className="payroll-panel-heading payroll-register-heading"><div><p className="eyebrow">EMPLOYEE REGISTER</p><h2>Employee calculations</h2></div><span>{runEmployees.length} employees</span></div>
      {runEmployees.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table payroll-employee-table"><thead><tr><th>EMPLOYEE</th><th>GROSS</th><th>TAXABLE</th><th>DEDUCTIONS</th><th>NET PAY</th><th>EMPLOYER COST</th></tr></thead><tbody>{runEmployees.map(item => { const employee = employeeById.get(item.employeeId); return <tr key={item.id}><td><strong>{employee ? `${employee.firstName} ${employee.lastName}` : 'Employee'}</strong><small>{employee?.employeeNo || item.employeeId}</small>{item.warnings && <small className="payroll-warning-text">{item.warnings}</small>}</td><td>{money(item.gross)}</td><td>{money(item.taxableIncome)}</td><td>{money(item.totalDeductions)}</td><td><strong>{money(item.netPay)}</strong></td><td>{money(item.employerCost)}</td></tr>; })}</tbody></table></div> : <div className="timekeeping-empty">{selected.status === 'DRAFT' ? 'Compute the run to create employee calculations.' : 'No employees were included in this run.'}</div>}
      {runEmployees.length > 0 && <details className="payroll-lines-details"><summary>View {lines.length} payroll line items</summary><div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>CODE</th><th>DESCRIPTION</th><th>CATEGORY</th><th>TAXABLE</th><th>AMOUNT</th></tr></thead><tbody>{lines.map(line => { const employee = employeeById.get(line.employeeId); return <tr key={line.id}><td>{employee ? `${employee.firstName} ${employee.lastName}` : 'Employee'}</td><td>{line.itemCode}</td><td>{line.description}</td><td>{line.category}</td><td>{line.taxable ? 'Yes' : 'No'}</td><td>{money(line.amount)}</td></tr>; })}</tbody></table></div></details>}
      <p className="timekeeping-footnote">The sequence is DRAFT → COMPUTED → REVIEWED → APPROVED → LOCKED → PAID. Locking issues payslips and freezes the regular pay period and included DTR days.</p>
    </section>}
    {selected && !action && selected.status === 'PAID' && <p className="payroll-complete-note">This run is paid and complete.</p>}
    <p className="timekeeping-footnote">Pay groups and pay periods are managed through the payroll setup API. This screen does not create missing pay configuration automatically.</p>
  </section>;
}
