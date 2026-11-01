import { useCallback, useEffect, useMemo, useState } from 'react';

type Page<T> = { content: T[] };
type Payslip = { id: string; payrollEmployeeId: string; employeeId: string; runId: string; versionNo: number; snapshot: string; issuedAt: string };
type Run = { id: string; payPeriodId: string; runType: string; status: string };
type Period = { id: string; startDate: string; endDate: string; payDate: string };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string };
type PayLine = { code: string; description: string; amount: number };
type SlipSnapshot = { employeeNo?: string; employeeName?: string; runType?: string; periodStart?: string; periodEnd?: string; payDate?: string; earnings?: PayLine[]; deductions?: PayLine[]; gross?: number; totalDeductions?: number; netPay?: number; warnings?: string | null };
const php = (amount: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP' }).format(Number(amount || 0));
const formatDate = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${value}T12:00:00`));
const formatInstant = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));

function readSnapshot(slip: Payslip): SlipSnapshot {
  try { return JSON.parse(slip.snapshot) as SlipSnapshot; }
  catch { return {}; }
}

export function PayslipsWorkspace({ token, onOpenSecurity }: { token: string; onOpenSecurity: () => void }) {
  const [payslips, setPayslips] = useState<Payslip[]>([]);
  const [runs, setRuns] = useState<Run[]>([]);
  const [periods, setPeriods] = useState<Period[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [selected, setSelected] = useState<Payslip | null>(null);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [mfaVerified, setMfaVerified] = useState<boolean | null>(null);

  const request = useCallback(async <T,>(path: string): Promise<T> => {
    const response = await fetch(path, { headers: { Authorization: `Bearer ${token}` } });
    if (!response.ok) {
      let message = `Request failed (${response.status}).`;
      try { const body = await response.json() as { message?: string; detail?: string }; message = body.detail || body.message || message; } catch { /* response has no JSON body */ }
      if (response.status === 401) message = 'Your sign-in session has expired. Sign in again.';
      if (response.status === 403) message = 'Payslip access requires a workspace owner or payroll officer account with a verified MFA session.';
      throw new Error(message);
    }
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [slipPage, runPage, periodPage, employeePage] = await Promise.all([
        request<Page<Payslip>>('/api/payslips?size=1000&sort=issuedAt,desc'),
        request<Page<Run>>('/api/payroll-runs?size=1000&sort=createdAt,desc'),
        request<Page<Period>>('/api/pay-periods?size=1000&sort=startDate,desc'),
        request<Page<Employee>>('/api/payroll/employees?size=1000&sort=employeeNo,asc'),
      ]);
      setPayslips(slipPage.content); setRuns(runPage.content); setPeriods(periodPage.content); setEmployees(employeePage.content);
      setSelected(current => current ? slipPage.content.find(slip => slip.id === current.id) || null : null);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load issued payslips.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    void request<{ enabled: boolean; tokenVerified: boolean }>('/api/v1/security/mfa').then(status => setMfaVerified(status.tokenVerified)).catch(() => setMfaVerified(false));
  }, [request]);

  const runById = useMemo(() => new Map(runs.map(run => [run.id, run])), [runs]);
  const periodById = useMemo(() => new Map(periods.map(period => [period.id, period])), [periods]);
  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const selectedSnapshot = selected ? readSnapshot(selected) : null;
  const filtered = payslips.filter(slip => {
    const snapshot = readSnapshot(slip); const employee = employeeById.get(slip.employeeId);
    const haystack = `${snapshot.employeeName || ''} ${snapshot.employeeNo || ''} ${employee?.firstName || ''} ${employee?.lastName || ''} ${employee?.employeeNo || ''} ${snapshot.periodStart || ''} ${snapshot.periodEnd || ''} ${snapshot.payDate || ''}`.toLowerCase();
    return !query || haystack.includes(query.toLowerCase());
  });
  const paidYtd = payslips.reduce((sum, slip) => {
    const snapshot = readSnapshot(slip);
    if (snapshot.payDate?.slice(0, 4) !== String(new Date().getFullYear())) return sum;
    return sum + Number(snapshot.netPay || 0);
  }, 0);

  function printSlip() { window.print(); }

  return <section className="timekeeping-workspace payslips-workspace">
    <header className="timekeeping-heading payslips-page-header"><div><p className="eyebrow">PAYROLL</p><h1>Payslips</h1><p>Issued payslips are immutable snapshots from locked payroll runs.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to view payslips</strong><small>Enable and verify MFA in account security, then sign in again to get an authorized session.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    {error && <div className="platform-alert" role="alert">{error}</div>}
    <div className="timekeeping-metrics payroll-metrics payslip-metrics"><article><span>ISSUED PAYSLIPS</span><strong>{payslips.length}</strong><small>Immutable payroll records</small></article><article><span>PAYROLL RUNS</span><strong>{new Set(payslips.map(slip => slip.runId)).size}</strong><small>Runs with issued slips</small></article><article><span>NET PAY · {new Date().getFullYear()}</span><strong>{php(paidYtd)}</strong><small>Total from issued snapshots</small></article><article><span>LAST ISSUED</span><strong className="payslip-last-issued">{payslips[0] ? formatInstant(payslips[0].issuedAt) : '—'}</strong><small>Most recent issue time</small></article></div>
    <section className="payroll-panel payslip-register"><div className="payroll-panel-heading"><div><p className="eyebrow">PAYROLL RECORDS</p><h2>Issued payslips</h2></div><span>{filtered.length} records</span></div><label className="timekeeping-search payslip-search"><span aria-hidden="true">⌕</span><input type="search" aria-label="Search payslips" placeholder="Search employee or pay period…" value={query} onChange={event => setQuery(event.target.value)} /></label>
      {loading ? <div className="timekeeping-empty">Loading payslips…</div> : filtered.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table payslip-table"><thead><tr><th>EMPLOYEE</th><th>PAY PERIOD</th><th>PAY DATE</th><th>RUN TYPE</th><th>NET PAY</th><th>ISSUED</th><th></th></tr></thead><tbody>{filtered.map(slip => {
        const snapshot = readSnapshot(slip); const employee = employeeById.get(slip.employeeId); const run = runById.get(slip.runId); const period = run ? periodById.get(run.payPeriodId) : null;
        const start = snapshot.periodStart || period?.startDate; const end = snapshot.periodEnd || period?.endDate; const payDate = snapshot.payDate || period?.payDate;
        return <tr key={slip.id}><td><strong>{snapshot.employeeName || (employee ? `${employee.lastName}, ${employee.firstName}` : 'Employee')}</strong><small>{snapshot.employeeNo || employee?.employeeNo || '—'}</small></td><td>{start && end ? `${formatDate(start)} – ${formatDate(end)}` : '—'}<small>Version {slip.versionNo}</small></td><td>{payDate ? formatDate(payDate) : '—'}</td><td>{(snapshot.runType || run?.runType || 'PAYROLL').replaceAll('_', ' ')}</td><td><strong>{php(Number(snapshot.netPay || 0))}</strong></td><td>{formatInstant(slip.issuedAt)}</td><td><button type="button" className="session-revoke" onClick={() => setSelected(slip)}>View</button></td></tr>;
      })}</tbody></table></div> : <div className="timekeeping-empty">{payslips.length ? 'No payslips match this search.' : 'No payslips have been issued yet. A payroll run produces payslips when an approved run is locked.'}</div>}
    </section>
    {selected && selectedSnapshot && <div className="payslip-detail-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) setSelected(null); }}><section className="payslip-detail-dialog" role="dialog" aria-modal="true" aria-labelledby="payslip-dialog-title"><header className="payslip-dialog-actions"><div><p className="eyebrow">ISSUED PAYSLIP · VERSION {selected.versionNo}</p><h2 id="payslip-dialog-title">Pay statement</h2></div><div><button type="button" className="security-submit" onClick={printSlip}>Print / save PDF</button><button type="button" className="payslip-close" aria-label="Close payslip" onClick={() => setSelected(null)}>×</button></div></header>
      <article className="payslip-print-sheet"><div className="payslip-document-heading"><div><p className="eyebrow">APEX PEOPLE · PAYROLL</p><h1>Pay statement</h1><p>{selectedSnapshot.runType?.replaceAll('_', ' ') || 'Payroll'}</p></div><span className="payslip-issued-tag">ISSUED</span></div><div className="payslip-employee-summary"><div><small>EMPLOYEE</small><strong>{selectedSnapshot.employeeName || 'Employee'}</strong></div><div><small>EMPLOYEE NO.</small><strong>{selectedSnapshot.employeeNo || '—'}</strong></div><div><small>PAY PERIOD</small><strong>{selectedSnapshot.periodStart && selectedSnapshot.periodEnd ? `${formatDate(selectedSnapshot.periodStart)} – ${formatDate(selectedSnapshot.periodEnd)}` : '—'}</strong></div><div><small>PAY DATE</small><strong>{selectedSnapshot.payDate ? formatDate(selectedSnapshot.payDate) : '—'}</strong></div></div>
        <div className="payslip-lines-grid"><PayslipLines title="Earnings" lines={selectedSnapshot.earnings || []} /><PayslipLines title="Deductions" lines={selectedSnapshot.deductions || []} /></div><div className="payslip-totals"><div><span>Gross pay</span><strong>{php(Number(selectedSnapshot.gross || 0))}</strong></div><div><span>Total deductions</span><strong>{php(Number(selectedSnapshot.totalDeductions || 0))}</strong></div><div className="payslip-net"><span>Net pay</span><strong>{php(Number(selectedSnapshot.netPay || 0))}</strong></div></div>
        {selectedSnapshot.warnings && <p className="payslip-warning">Payroll notes: {selectedSnapshot.warnings}</p>}<footer className="payslip-document-footer"><span>Issued {formatInstant(selected.issuedAt)} · Snapshot version {selected.versionNo}</span><span>This statement is an official snapshot of the payroll run.</span></footer>
      </article></section></div>}
  </section>;
}

function PayslipLines({ title, lines }: { title: string; lines: PayLine[] }) {
  return <section className="payslip-lines"><h3>{title}</h3>{lines.length ? <div>{lines.map((line, index) => <article key={`${line.code}-${index}`}><span><strong>{line.description || line.code}</strong><small>{line.code}</small></span><b>{php(Number(line.amount || 0))}</b></article>)}</div> : <p>No {title.toLowerCase()} listed.</p>}</section>;
}
