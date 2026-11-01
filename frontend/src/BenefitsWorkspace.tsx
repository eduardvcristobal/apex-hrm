import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';

type Page<T> = { content: T[] };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; status: string };
type RecurringItem = { id: string; employeeId: string; itemCode: string; category: string; description: string | null; amount: number; taxable: boolean; effectiveFrom: string; effectiveTo: string | null };
type Loan = { id: string; employeeId: string; loanType: string; referenceNo: string | null; principal: number; amortization: number; balance: number; startDate: string; status: string };
type EntryKind = 'earning' | 'deduction' | 'employer';
const today = () => { const date = new Date(); return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; };
const php = (amount: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP' }).format(Number(amount || 0));
const prettyDate = (value: string | null) => value ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${value}T12:00:00`)) : 'Ongoing';

export function BenefitsWorkspace({ token, onOpenSecurity }: { token: string; onOpenSecurity: () => void }) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [items, setItems] = useState<RecurringItem[]>([]);
  const [loans, setLoans] = useState<Loan[]>([]);
  const [tab, setTab] = useState<'benefits' | 'deductions' | 'loans'>('benefits');
  const [employeeId, setEmployeeId] = useState('');
  const [code, setCode] = useState('');
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [kind, setKind] = useState<EntryKind>('earning');
  const [taxable, setTaxable] = useState(true);
  const [effectiveFrom, setEffectiveFrom] = useState(today);
  const [effectiveTo, setEffectiveTo] = useState('');
  const [loanType, setLoanType] = useState('');
  const [referenceNo, setReferenceNo] = useState('');
  const [principal, setPrincipal] = useState('');
  const [amortization, setAmortization] = useState('');
  const [balance, setBalance] = useState('');
  const [startDate, setStartDate] = useState(today);
  const [query, setQuery] = useState('');
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
      if (response.status === 401) message = 'Your sign-in session has expired. Sign in again.';
      if (response.status === 403) message = 'This action requires a workspace owner, HR administrator, or payroll officer with a verified MFA session.';
      throw new Error(message);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [employeePage, itemPage, loanPage] = await Promise.all([
        request<Page<Employee>>('/api/payroll/employees?size=1000&sort=employeeNo,asc'),
        request<Page<RecurringItem>>('/api/recurring-items?size=1000&sort=effectiveFrom,desc'),
        request<Page<Loan>>('/api/loans?size=1000&sort=startDate,desc'),
      ]);
      setEmployees(employeePage.content.filter(employee => employee.status !== 'SEPARATED'));
      setItems(itemPage.content); setLoans(loanPage.content);
      setEmployeeId(current => current || employeePage.content.find(employee => employee.status !== 'SEPARATED')?.id || '');
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load benefits and deductions.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    void request<{ enabled: boolean; tokenVerified: boolean }>('/api/v1/security/mfa').then(status => setMfaVerified(status.tokenVerified)).catch(() => setMfaVerified(false));
  }, [request]);

  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const employeeName = (id: string) => { const employee = employeeById.get(id); return employee ? `${employee.firstName} ${employee.lastName}` : 'Employee'; };
  const matches = (id: string, extra: string) => !query || `${employeeName(id)} ${employeeById.get(id)?.employeeNo || ''} ${extra}`.toLowerCase().includes(query.toLowerCase());
  const earnings = items.filter(item => item.category === 'EARNING');
  const deductions = items.filter(item => item.category === 'DEDUCTION');
  const employerContributions = items.filter(item => item.category === 'EMPLOYER_COST');
  const activeLoans = loans.filter(loan => loan.status === 'ACTIVE');
  const recurringForTab = (tab === 'benefits' ? [...earnings, ...employerContributions] : deductions).filter(item => matches(item.employeeId, `${item.itemCode} ${item.description || ''} ${item.category} ${item.effectiveTo || 'ongoing'}`));
  const visibleLoans = loans.filter(loan => matches(loan.employeeId, `${loan.loanType} ${loan.referenceNo || ''} ${loan.status}`));

  async function addRecurring(event: FormEvent) {
    event.preventDefault(); setBusy('recurring'); setError(''); setNotice('');
    try {
      const category = kind === 'earning' ? 'EARNING' : kind === 'deduction' ? 'DEDUCTION' : 'EMPLOYER_COST';
      const created = await request<RecurringItem>('/api/recurring-items', { method: 'POST', body: JSON.stringify({ employeeId, itemCode: code.trim().toUpperCase(), category, description: description.trim() || null, amount: Number(amount), taxable: kind === 'earning' && taxable, effectiveFrom, effectiveTo: effectiveTo || null }) });
      setItems(current => [created, ...current]); setCode(''); setDescription(''); setAmount(''); setEffectiveTo(''); setTaxable(true);
      setNotice(`${kind === 'earning' ? 'Benefit' : kind === 'deduction' ? 'Deduction' : 'Employer contribution'} added for ${employeeName(employeeId)}.`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not add recurring payroll item.'); }
    finally { setBusy(''); }
  }

  async function endRecurring(item: RecurringItem) {
    if (!window.confirm(`End ${item.itemCode} for ${employeeName(item.employeeId)} effective today?`)) return;
    setBusy(item.id); setError(''); setNotice('');
    try {
      const updated = await request<RecurringItem>(`/api/recurring-items/${item.id}`, { method: 'PUT', body: JSON.stringify({ effectiveTo: today() }) });
      setItems(current => current.map(row => row.id === item.id ? updated : row)); setNotice(`${item.itemCode} will no longer be included in future payroll periods.`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not end recurring item.'); }
    finally { setBusy(''); }
  }

  async function addLoan(event: FormEvent) {
    event.preventDefault(); setBusy('loan'); setError(''); setNotice('');
    try {
      const created = await request<Loan>('/api/loans', { method: 'POST', body: JSON.stringify({ employeeId, loanType: loanType.trim(), referenceNo: referenceNo.trim() || null, principal: Number(principal), amortization: Number(amortization), balance: Number(balance || principal), startDate, status: 'ACTIVE' }) });
      setLoans(current => [created, ...current]); setLoanType(''); setReferenceNo(''); setPrincipal(''); setAmortization(''); setBalance('');
      setNotice(`${created.loanType} loan recorded for ${employeeName(employeeId)}.`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not add employee loan.'); }
    finally { setBusy(''); }
  }

  async function toggleLoan(loan: Loan) {
    const nextStatus = loan.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
    if (!window.confirm(`${nextStatus === 'SUSPENDED' ? 'Suspend' : 'Reactivate'} the ${loan.loanType} loan for ${employeeName(loan.employeeId)}?`)) return;
    setBusy(loan.id); setError(''); setNotice('');
    try {
      const updated = await request<Loan>(`/api/loans/${loan.id}`, { method: 'PUT', body: JSON.stringify({ status: nextStatus }) });
      setLoans(current => current.map(row => row.id === loan.id ? updated : row)); setNotice(`Loan ${nextStatus === 'SUSPENDED' ? 'suspended' : 'reactivated'}; balance remains ${php(updated.balance)}.`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not update employee loan.'); }
    finally { setBusy(''); }
  }

  return <section className="timekeeping-workspace benefits-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">PAYROLL SETUP</p><h1>Benefits &amp; deductions</h1><p>Maintain employee recurring pay items and loan repayments that the payroll calculator applies.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to manage payroll items</strong><small>Enable and verify MFA in account security, then sign in again to get an authorized session.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics payroll-metrics benefits-metrics"><article><span>RECURRING EARNINGS</span><strong>{earnings.length}</strong><small>Benefits and allowances</small></article><article><span>RECURRING DEDUCTIONS</span><strong>{deductions.length}</strong><small>Employee deductions</small></article><article><span>EMPLOYER ITEMS</span><strong>{employerContributions.length}</strong><small>Employer cost entries</small></article><article><span>ACTIVE LOANS</span><strong>{activeLoans.length}</strong><small>Included in regular payroll</small></article></div>
    <div className="timekeeping-tabs" role="tablist" aria-label="Benefits and deductions"><button type="button" role="tab" aria-selected={tab === 'benefits'} className={tab === 'benefits' ? 'active' : ''} onClick={() => { setTab('benefits'); setKind('earning'); }}>Benefits &amp; allowances <span>{earnings.length + employerContributions.length}</span></button><button type="button" role="tab" aria-selected={tab === 'deductions'} className={tab === 'deductions' ? 'active' : ''} onClick={() => { setTab('deductions'); setKind('deduction'); }}>Deductions <span>{deductions.length}</span></button><button type="button" role="tab" aria-selected={tab === 'loans'} className={tab === 'loans' ? 'active' : ''} onClick={() => setTab('loans')}>Employee loans <span>{activeLoans.length}</span></button></div>
    <div className="benefits-layout">
      {tab === 'loans' ? <form className="payroll-panel benefits-form" onSubmit={event => void addLoan(event)}><div className="payroll-panel-heading"><div><p className="eyebrow">LOAN REGISTER</p><h2>Record employee loan</h2></div></div><label className="payroll-field">Employee<select value={employeeId} onChange={event => setEmployeeId(event.target.value)} required><option value="">Select employee…</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label><label className="payroll-field">Loan type<input value={loanType} onChange={event => setLoanType(event.target.value)} required maxLength={80} placeholder="e.g. SSS salary loan" /></label><label className="payroll-field">Reference number (optional)<input value={referenceNo} onChange={event => setReferenceNo(event.target.value)} maxLength={100} /></label><div className="benefits-form-grid"><label className="payroll-field">Principal<input type="number" min="0.01" step="0.01" value={principal} onChange={event => { setPrincipal(event.target.value); if (!balance) setBalance(event.target.value); }} required /></label><label className="payroll-field">Balance<input type="number" min="0" step="0.01" value={balance} onChange={event => setBalance(event.target.value)} required /></label></div><label className="payroll-field">Per-run amortization<input type="number" min="0.01" step="0.01" value={amortization} onChange={event => setAmortization(event.target.value)} required /></label><label className="payroll-field">First deduction date<input type="date" value={startDate} onChange={event => setStartDate(event.target.value)} required /></label><button type="submit" className="security-submit" disabled={!!busy || loading || !employeeId || mfaVerified === false}>{busy === 'loan' ? 'Saving…' : '＋ Record loan'}</button><p className="payroll-help">Loan deductions start with the next regular payroll run. Each deduction is capped at the remaining loan balance.</p></form>
        : <form className="payroll-panel benefits-form" onSubmit={event => void addRecurring(event)}><div className="payroll-panel-heading"><div><p className="eyebrow">RECURRING ITEM</p><h2>Add payroll item</h2></div></div><label className="payroll-field">Employee<select value={employeeId} onChange={event => setEmployeeId(event.target.value)} required><option value="">Select employee…</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label><label className="payroll-field">Item type<select value={kind} onChange={event => setKind(event.target.value as EntryKind)}><option value="earning">Benefit / allowance (earning)</option><option value="deduction">Recurring deduction</option><option value="employer">Employer contribution (cost)</option></select></label><div className="benefits-form-grid"><label className="payroll-field">Payroll item code<input value={code} onChange={event => setCode(event.target.value.toUpperCase())} required maxLength={40} placeholder="e.g. MEAL_ALLOWANCE" /></label><label className="payroll-field">Amount per pay run<input type="number" min="0.01" step="0.01" value={amount} onChange={event => setAmount(event.target.value)} required /></label></div><label className="payroll-field">Description<input value={description} onChange={event => setDescription(event.target.value)} maxLength={255} placeholder="Optional label shown on the payslip" /></label><div className="benefits-form-grid"><label className="payroll-field">Effective from<input type="date" value={effectiveFrom} onChange={event => setEffectiveFrom(event.target.value)} required /></label><label className="payroll-field">Effective to (optional)<input type="date" value={effectiveTo} min={effectiveFrom} onChange={event => setEffectiveTo(event.target.value)} /></label></div>{kind === 'earning' && <label className="benefits-taxable"><input type="checkbox" checked={taxable} onChange={event => setTaxable(event.target.checked)} /> Taxable earning</label>}<button type="submit" className="security-submit" disabled={!!busy || loading || !employeeId || mfaVerified === false}>{busy === 'recurring' ? 'Saving…' : '＋ Add recurring item'}</button><p className="payroll-help">The amount is applied per payroll run while the effective dates overlap a regular pay period.</p></form>}
      <section className="payroll-panel benefits-register"><div className="payroll-panel-heading"><div><p className="eyebrow">WORKSPACE REGISTER</p><h2>{tab === 'loans' ? 'Employee loans' : tab === 'benefits' ? 'Benefits & allowances' : 'Recurring deductions'}</h2></div><span>{tab === 'loans' ? visibleLoans.length : recurringForTab.length} records</span></div><label className="timekeeping-search benefits-search"><span aria-hidden="true">⌕</span><input type="search" aria-label="Search employee or payroll item" placeholder="Search employee or item…" value={query} onChange={event => setQuery(event.target.value)} /></label>
        {loading ? <div className="timekeeping-empty">Loading payroll items…</div> : tab === 'loans' ? visibleLoans.length ? <div className="benefits-record-list">{visibleLoans.map(loan => <article className="benefits-record" key={loan.id}><div className="benefits-record-main"><div><strong>{loan.loanType}</strong><span className={`timekeeping-status ${loan.status === 'ACTIVE' ? 'warning' : loan.status === 'PAID' ? 'open' : 'locked'}`}>{loan.status}</span></div><small>{employeeName(loan.employeeId)} · {employeeById.get(loan.employeeId)?.employeeNo || '—'}{loan.referenceNo ? ` · Ref ${loan.referenceNo}` : ''}</small><div className="benefits-loan-values"><span><small>PRINCIPAL</small><strong>{php(loan.principal)}</strong></span><span><small>REMAINING</small><strong>{php(loan.balance)}</strong></span><span><small>PER RUN</small><strong>{php(loan.amortization)}</strong></span></div><small>Started {prettyDate(loan.startDate)}</small></div>{['ACTIVE', 'SUSPENDED'].includes(loan.status) && <button type="button" className={loan.status === 'ACTIVE' ? 'timekeeping-reject' : 'session-revoke'} onClick={() => void toggleLoan(loan)} disabled={!!busy}>{busy === loan.id ? 'Saving…' : loan.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'}</button>}</article>)}</div> : <div className="timekeeping-empty">No matching loans recorded.</div> : recurringForTab.length ? <div className="benefits-record-list">{recurringForTab.map(item => <article className="benefits-record" key={item.id}><div className="benefits-record-main"><div><strong>{item.itemCode}</strong><span className={`timekeeping-status ${item.effectiveTo && item.effectiveTo < today() ? 'locked' : 'open'}`}>{item.effectiveTo && item.effectiveTo < today() ? 'ENDED' : item.category.replaceAll('_', ' ')}</span></div><small>{employeeName(item.employeeId)} · {employeeById.get(item.employeeId)?.employeeNo || '—'}</small><p>{item.description || 'No description'}{item.taxable ? ' · Taxable' : ''}</p><div className="benefits-record-dates"><span>{php(item.amount)} per run</span><small>{prettyDate(item.effectiveFrom)} – {prettyDate(item.effectiveTo)}</small></div></div>{(!item.effectiveTo || item.effectiveTo >= today()) && <button type="button" className="session-revoke" onClick={() => void endRecurring(item)} disabled={!!busy}>{busy === item.id ? 'Saving…' : 'End item'}</button>}</article>)}</div> : <div className="timekeeping-empty">No matching recurring payroll items.</div>}
      </section>
    </div>
    <p className="timekeeping-footnote">Recurring items and active loan amortizations are included by the payroll calculator. Recompute a draft or computed run after changing an item to refresh its calculation. Approved/locked runs keep their existing payroll snapshot.</p>
  </section>;
}
