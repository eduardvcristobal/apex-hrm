import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { WorkspaceModal } from './WorkspaceModal';

type Page<T> = { content: T[] };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; status: string; separationDate: string | null };
type PayGroup = { id: string; code: string; name: string };
type PayPeriod = { id: string; payGroupId: string; startDate: string; endDate: string; payDate: string; status: string };
type Adjustment = {
  id: string; employeeId: string; itemCode: string; description: string; category: 'EARNING' | 'DEDUCTION';
  incomeClass: 'REGULAR' | 'SUPPLEMENTARY'; amount: number; taxable: boolean; reason: string;
  sourcePeriodId: string | null; targetPeriodId: string | null; status: string; requestedBy: string;
  approvalInstanceId: string | null; createdAt: string; approvedAt: string | null;
};

const php = (amount: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP' }).format(Number(amount || 0));
const date = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${value}T12:00:00`));

export function PayrollAdjustmentsWorkspace({ token, currentUserId, canConfigureWorkflows, onOpenSecurity, onOpenApprovalWorkflows }: {
  token: string; currentUserId: string; canConfigureWorkflows: boolean; onOpenSecurity: () => void; onOpenApprovalWorkflows: () => void;
}) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [groups, setGroups] = useState<PayGroup[]>([]);
  const [periods, setPeriods] = useState<PayPeriod[]>([]);
  const [adjustments, setAdjustments] = useState<Adjustment[]>([]);
  const [employeeId, setEmployeeId] = useState('');
  const [itemCode, setItemCode] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState<'EARNING' | 'DEDUCTION'>('EARNING');
  const [incomeClass, setIncomeClass] = useState<'REGULAR' | 'SUPPLEMENTARY'>('SUPPLEMENTARY');
  const [amount, setAmount] = useState('');
  const [taxable, setTaxable] = useState(true);
  const [reason, setReason] = useState('');
  const [sourcePeriodId, setSourcePeriodId] = useState('');
  const [targetPeriodId, setTargetPeriodId] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [mfaVerified, setMfaVerified] = useState<boolean | null>(null);

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    const method = (init.method || 'GET').toUpperCase();
    const headers: Record<string, string> = { Authorization: `Bearer ${token}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...(init.headers as Record<string, string> || {}) };
    if (!path.startsWith('/api/v1/') && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      const csrfResponse = await fetch('/api/v1/security/csrf', { credentials: 'same-origin' });
      if (!csrfResponse.ok) throw new Error('Could not start a secure payroll adjustment session.');
      const csrf = await csrfResponse.json() as { headerName?: string; token: string };
      headers[csrf.headerName || 'X-CSRF-TOKEN'] = csrf.token;
    }
    const response = await fetch(path, { ...init, credentials: 'same-origin', headers });
    if (!response.ok) {
      let message = `Request failed (${response.status}).`;
      try { const body = await response.json() as { detail?: string; message?: string }; message = body.detail || body.message || message; } catch { /* response has no JSON body */ }
      if (response.status === 401) message = 'Your sign-in session has expired. Please sign in again.';
      if (response.status === 403) message = 'Payroll adjustments require a payroll role and an MFA-verified session.';
      if (response.status === 422 && /approval workflow/i.test(message)) message += ' Configure and assign a Payroll adjustment workflow under Approval workflows.';
      throw new Error(message);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [employeePage, groupPage, periodPage, adjustmentPage] = await Promise.all([
        request<Page<Employee>>('/api/payroll/employees?size=1000&sort=employeeNo,asc'),
        request<Page<PayGroup>>('/api/pay-groups?size=500&sort=name,asc'),
        request<Page<PayPeriod>>('/api/pay-periods?size=1000&sort=startDate,desc'),
        request<Page<Adjustment>>('/api/adjustments?size=1000&sort=createdAt,desc'),
      ]);
      setEmployees(employeePage.content.filter(employee => employee.status === 'ACTIVE'));
      setGroups(groupPage.content); setPeriods(periodPage.content); setAdjustments(adjustmentPage.content);
      setEmployeeId(current => current || employeePage.content.find(employee => employee.status === 'ACTIVE')?.id || '');
      setTargetPeriodId(current => current || periodPage.content.find(period => period.status === 'OPEN')?.id || '');
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load payroll adjustments.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    void request<{ tokenVerified: boolean }>('/api/v1/security/mfa').then(status => setMfaVerified(status.tokenVerified)).catch(() => setMfaVerified(false));
  }, [request]);

  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const groupById = useMemo(() => new Map(groups.map(group => [group.id, group])), [groups]);
  const periodById = useMemo(() => new Map(periods.map(period => [period.id, period])), [periods]);
  const openPeriods = periods.filter(period => period.status === 'OPEN');
  const lockedPeriods = periods.filter(period => period.status === 'LOCKED');
  const pendingCount = adjustments.filter(adjustment => adjustment.status === 'PENDING').length;
  const approvedCount = adjustments.filter(adjustment => adjustment.status === 'APPROVED').length;
  const appliedCount = adjustments.filter(adjustment => adjustment.status === 'APPLIED').length;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('');
    try {
      const created = await request<Adjustment>('/api/payroll/adjustments', { method: 'POST', body: JSON.stringify({
        employeeId, itemCode: itemCode.trim().toUpperCase(), description: description.trim(), category,
        incomeClass: category === 'EARNING' ? incomeClass : 'SUPPLEMENTARY', amount: Number(amount),
        taxable: category === 'EARNING' ? taxable : false, reason: reason.trim(),
        sourcePeriodId: sourcePeriodId || null, targetPeriodId: targetPeriodId || null,
      }) });
      setNotice(`Adjustment ${created.id.slice(0, 8)} submitted for approval. It will enter the selected payroll run only after the workflow approves it.`);
      setItemCode(''); setDescription(''); setAmount(''); setReason(''); setSourcePeriodId('');
      await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not submit this adjustment.'); }
    finally { setBusy(false); }
  }

  return <section className="payroll-adjustments-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">PAYROLL · ONE-TIME CORRECTIONS</p><h1>Payroll adjustments</h1><p>Record missed earnings or deductions with a source period, reason, and approval trail. Approved items are applied once to the selected open payroll run.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to submit adjustments</strong><small>Enable and verify MFA in account security, then sign in again.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="payroll-adjustment-metrics"><article><span>PENDING APPROVAL</span><strong>{pendingCount}</strong></article><article><span>APPROVED TO APPLY</span><strong>{approvedCount}</strong></article><article><span>APPLIED ON PAYROLL</span><strong>{appliedCount}</strong></article></div>
    <div className="payroll-adjustment-layout">
      <WorkspaceModal title="Create payroll adjustment" triggerLabel="＋ Create payroll adjustment"><form className="payroll-panel payroll-adjustment-form" onSubmit={event => void submit(event)}>
        <div className="payroll-panel-heading"><div><p className="eyebrow">NEW REQUEST</p><h2>Encode a payroll correction</h2></div></div>
        <label className="payroll-field">Employee<select value={employeeId} onChange={event => setEmployeeId(event.target.value)} required><option value="">Select employee…</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label>
        <div className="payroll-adjustment-fields"><label className="payroll-field">Adjustment type<select value={category} onChange={event => setCategory(event.target.value as typeof category)}><option value="EARNING">Additional income</option><option value="DEDUCTION">Additional deduction</option></select></label><label className="payroll-field">Amount (PHP)<input type="number" min="0.01" step="0.01" value={amount} onChange={event => setAmount(event.target.value)} required /></label></div>
        <div className="payroll-adjustment-fields"><label className="payroll-field">Payroll item code<input value={itemCode} onChange={event => setItemCode(event.target.value.toUpperCase())} pattern="[A-Z0-9][A-Z0-9_-]{0,39}" maxLength={40} placeholder="e.g. MISSED_OT" required /></label><label className="payroll-field">Payslip description<input value={description} onChange={event => setDescription(event.target.value)} maxLength={160} placeholder="Missed overtime · Nov 3" required /></label></div>
        {category === 'EARNING' && <div className="payroll-adjustment-fields"><label className="payroll-field">Income class<select value={incomeClass} onChange={event => setIncomeClass(event.target.value as typeof incomeClass)}><option value="SUPPLEMENTARY">Supplementary · overtime, bonus, correction</option><option value="REGULAR">Regular compensation</option></select></label><label className="benefits-taxable"><input type="checkbox" checked={taxable} onChange={event => setTaxable(event.target.checked)} /> Taxable earning</label></div>}
        <div className="payroll-adjustment-fields"><label className="payroll-field">Related source period (optional)<select value={sourcePeriodId} onChange={event => setSourcePeriodId(event.target.value)}><option value="">No prior payroll period</option>{lockedPeriods.map(period => <option key={period.id} value={period.id}>{groupById.get(period.payGroupId)?.name || 'Pay group'} · {date(period.startDate)} – {date(period.endDate)}</option>)}</select></label><label className="payroll-field">Apply to open pay period<select value={targetPeriodId} onChange={event => setTargetPeriodId(event.target.value)} required><option value="">Select target period…</option>{openPeriods.map(period => <option key={period.id} value={period.id}>{groupById.get(period.payGroupId)?.name || 'Pay group'} · {date(period.startDate)} – {date(period.endDate)} · pay {date(period.payDate)}</option>)}</select></label></div>
        <label className="payroll-field">Reason and supporting reference<textarea value={reason} onChange={event => setReason(event.target.value)} minLength={5} maxLength={2000} rows={4} placeholder="Explain what was missed or corrected and reference approved time records, a ticket, or other evidence." required /></label>
        <button type="submit" className="security-submit" disabled={busy || loading || mfaVerified === false || !employeeId || !targetPeriodId}>{busy ? 'Submitting…' : 'Submit for approval'}</button>
        <p className="payroll-help">A missed overtime correction should reference the locked payroll period and select the next open period. Approval is required before the item can be computed into payroll.</p>
        {canConfigureWorkflows ? <button type="button" className="platform-text-action payroll-adjustment-workflow-link" onClick={onOpenApprovalWorkflows}>Configure approval workflows <span aria-hidden="true">→</span></button> : <p className="payroll-help">If no workflow is assigned, ask a workspace owner or HR administrator to configure Payroll adjustment approvals.</p>}
      </form></WorkspaceModal>
      <section className="payroll-panel payroll-adjustment-history"><div className="payroll-panel-heading"><div><p className="eyebrow">AUDIT TRAIL</p><h2>Adjustment history</h2></div><span>{adjustments.length} records</span></div>
        {loading ? <div className="payroll-empty">Loading adjustments…</div> : !adjustments.length ? <div className="payroll-empty">No one-time payroll adjustments have been recorded.</div> : <div className="payroll-adjustment-list">{adjustments.map(adjustment => {
          const employee = employeeById.get(adjustment.employeeId);
          const source = adjustment.sourcePeriodId ? periodById.get(adjustment.sourcePeriodId) : null;
          const target = adjustment.targetPeriodId ? periodById.get(adjustment.targetPeriodId) : null;
          return <article className="payroll-adjustment-row" key={adjustment.id}>
            <div className="payroll-adjustment-row-heading"><div><strong>{adjustment.description}</strong><small>{employee ? `${employee.employeeNo} · ${employee.firstName} ${employee.lastName}` : 'Employee record'} · {adjustment.itemCode}</small></div><span className={`payroll-adjustment-status status-${adjustment.status.toLowerCase()}`}>{adjustment.status.replaceAll('_', ' ')}</span></div>
            <div className="payroll-adjustment-row-detail"><span>{adjustment.category === 'EARNING' ? 'Income' : 'Deduction'} · {adjustment.incomeClass || 'SUPPLEMENTARY'}{adjustment.category === 'EARNING' ? adjustment.taxable ? ' · taxable' : ' · non-taxable' : ''}</span><strong>{php(adjustment.amount)}</strong></div>
            <p>{adjustment.reason}</p>
            <small>{source ? `Source: ${date(source.startDate)} – ${date(source.endDate)}` : 'No source period'}{target ? ` · Target: ${date(target.startDate)} – ${date(target.endDate)}` : ''}{adjustment.requestedBy === currentUserId ? ' · Submitted by you' : ''}{adjustment.approvalInstanceId ? ' · Configured approval workflow' : ''}</small>
          </article>;
        })}</div>}
      </section>
    </div>
  </section>;
}
