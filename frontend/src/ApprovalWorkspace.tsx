import { useCallback, useEffect, useMemo, useState } from 'react';

type Page<T> = { content: T[] };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; status: string };
type LeaveRequest = { id: string; employeeId: string; dateFrom: string; dateTo: string; days: number; reason: string | null; status: string };
type OvertimeRequest = { id: string; employeeId: string; workDate: string; minutesRequested: number; approvedMinutes: number | null; reason: string; status: string };
type Correction = { id: string; employeeId: string; workDate: string; requestedIn: string | null; requestedOut: string | null; reason: string; status: string };
type Adjustment = { id: string; employeeId: string; itemCode: string; description: string | null; category: string; amount: number; reason: string; targetPeriodId: string | null; status: string; requestedBy: string };
type PayrollRun = { id: string; payPeriodId: string; runType: string; status: string; employeeCount: number; totalNet: number; totalGross: number; remarks: string | null };
type PayPeriod = { id: string; startDate: string; endDate: string; payDate: string };
type QueueTab = 'all' | 'timekeeping' | 'payroll';
const php = (value: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP' }).format(Number(value || 0));
const day = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${value}T12:00:00`));
const shortTime = (value: string | null) => value ? new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(new Date(value)) : '—';

export function ApprovalWorkspace({ token, roles, currentUserId, onOpenSecurity }: { token: string; roles: string[]; currentUserId: string; onOpenSecurity: () => void }) {
  const canHr = roles.some(role => ['ROLE_TENANT_ADMIN', 'ROLE_HR_ADMIN'].includes(role));
  const canPayroll = roles.some(role => ['ROLE_TENANT_ADMIN', 'ROLE_PAYROLL_OFFICER'].includes(role));
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [leave, setLeave] = useState<LeaveRequest[]>([]);
  const [overtime, setOvertime] = useState<OvertimeRequest[]>([]);
  const [corrections, setCorrections] = useState<Correction[]>([]);
  const [adjustments, setAdjustments] = useState<Adjustment[]>([]);
  const [runs, setRuns] = useState<PayrollRun[]>([]);
  const [periods, setPeriods] = useState<PayPeriod[]>([]);
  const [approvedMinutes, setApprovedMinutes] = useState<Record<string, string>>({});
  const [tab, setTab] = useState<QueueTab>('all');
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
      try { const body = await response.json() as { detail?: string; message?: string }; message = body.detail || body.message || message; } catch { /* response has no JSON body */ }
      if (response.status === 401) message = 'Your sign-in session has expired. Sign in again.';
      if (response.status === 403) message = 'This decision requires the appropriate HR or payroll role and a verified MFA session.';
      throw new Error(message);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const tasks: Promise<unknown>[] = [request<Page<Employee>>('/api/payroll/employees?size=1000&sort=employeeNo,asc').then(page => setEmployees(page.content))];
      if (canHr) {
        tasks.push(
          request<Page<LeaveRequest>>('/api/leave-requests?size=1000&sort=dateFrom,asc').then(page => setLeave(page.content)),
          request<Page<OvertimeRequest>>('/api/overtime-requests?size=1000&sort=workDate,asc').then(page => { setOvertime(page.content); setApprovedMinutes(current => Object.fromEntries(page.content.filter(item => item.status === 'PENDING').map(item => [item.id, current[item.id] || String(item.approvedMinutes ?? item.minutesRequested)]))); }),
          request<Page<Correction>>('/api/attendance-corrections?size=1000&sort=workDate,asc').then(page => setCorrections(page.content)),
        );
      }
      if (canPayroll) {
        tasks.push(
          request<Page<Adjustment>>('/api/adjustments?size=1000&sort=createdAt,asc').then(page => setAdjustments(page.content)),
          request<Page<PayrollRun>>('/api/payroll-runs?size=1000&sort=createdAt,asc').then(page => setRuns(page.content)),
          request<Page<PayPeriod>>('/api/pay-periods?size=1000&sort=startDate,desc').then(page => setPeriods(page.content)),
        );
      }
      await Promise.all(tasks);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load pending approvals.'); }
    finally { setLoading(false); }
  }, [canHr, canPayroll, request]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    void request<{ enabled: boolean; tokenVerified: boolean }>('/api/v1/security/mfa').then(status => setMfaVerified(status.tokenVerified)).catch(() => setMfaVerified(false));
  }, [request]);

  const employeesById = useMemo(() => new Map(employees.map(item => [item.id, item])), [employees]);
  const periodsById = useMemo(() => new Map(periods.map(item => [item.id, item])), [periods]);
  const pendingLeave = leave.filter(item => item.status === 'PENDING');
  const pendingOvertime = overtime.filter(item => item.status === 'PENDING');
  const pendingCorrections = corrections.filter(item => item.status === 'PENDING');
  const pendingAdjustments = adjustments.filter(item => item.status === 'PENDING');
  const payrollQueue = runs.filter(item => ['COMPUTED', 'REVIEWED'].includes(item.status));
  const timekeepingCount = pendingLeave.length + pendingOvertime.length + pendingCorrections.length;
  const payrollCount = pendingAdjustments.length + payrollQueue.length;
  const employeeLabel = (id: string) => { const employee = employeesById.get(id); return employee ? `${employee.firstName} ${employee.lastName}` : 'Employee'; };
  const matches = (text: string) => !query || text.toLowerCase().includes(query.toLowerCase());
  const visible = { hr: tab !== 'payroll' && canHr, payroll: tab !== 'timekeeping' && canPayroll };
  const totalVisible = (visible.hr ? timekeepingCount : 0) + (visible.payroll ? payrollCount : 0);

  async function decide(path: string, label: string, body?: unknown) {
    setBusy(label); setError(''); setNotice('');
    try {
      await request(path, { method: 'POST', ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
      setNotice(`${label.split(':')[0]} completed.`); await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'The approval could not be completed.'); }
    finally { setBusy(''); }
  }

  const tabs: { id: QueueTab; label: string; count: number; enabled: boolean }[] = [
    { id: 'all', label: 'All approvals', count: (canHr ? timekeepingCount : 0) + (canPayroll ? payrollCount : 0), enabled: true },
    { id: 'timekeeping', label: 'Timekeeping', count: timekeepingCount, enabled: canHr },
    { id: 'payroll', label: 'Payroll', count: payrollCount, enabled: canPayroll },
  ];

  return <section className="timekeeping-workspace approvals-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">WORKFLOW</p><h1>Approval center</h1><p>Review pending requests in one place. The server validates each decision and records its audit trail.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to process approvals</strong><small>Enable and verify MFA in account security, then sign in again to get an authorized session.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics payroll-metrics approval-metrics"><article><span>TIMEKEEPING</span><strong>{timekeepingCount}</strong><small>Leave, overtime, corrections</small></article><article><span>PAYROLL</span><strong>{payrollCount}</strong><small>Adjustments, review, approval</small></article><article><span>LEAVE REQUESTS</span><strong>{pendingLeave.length}</strong><small>Awaiting HR decision</small></article><article><span>PAYROLL RUNS</span><strong>{payrollQueue.length}</strong><small>Review or approve</small></article></div>
    <div className="approval-toolbar"><div className="timekeeping-tabs" role="tablist" aria-label="Approval queue">{tabs.filter(item => item.enabled).map(item => <button type="button" role="tab" key={item.id} aria-selected={tab === item.id} className={tab === item.id ? 'active' : ''} onClick={() => setTab(item.id)}>{item.label}<span>{item.count}</span></button>)}</div><label className="timekeeping-search"><span aria-hidden="true">⌕</span><input type="search" placeholder="Search employee or request…" aria-label="Search approvals" value={query} onChange={event => setQuery(event.target.value)} /></label></div>
    {loading ? <div className="payroll-panel"><div className="timekeeping-empty">Loading approval queues…</div></div> : !totalVisible ? <div className="payroll-panel"><div className="approval-empty"><span>✓</span><h2>You're all caught up</h2><p>There are no pending approvals in the queues available to your role.</p></div></div> : <div className="approval-queues">
      {visible.hr && <section className="payroll-panel approval-queue"><div className="payroll-panel-heading"><div><p className="eyebrow">TIMEKEEPING</p><h2>Requests for review</h2></div><span>{timekeepingCount} pending</span></div>
        {pendingLeave.filter(item => matches(`${employeeLabel(item.employeeId)} ${item.dateFrom} ${item.dateTo} ${item.reason || ''} leave`)).map(item => <article className="approval-card" key={`leave-${item.id}`}><div className="approval-card-kind"><span className="approval-icon leave">L</span></div><div className="approval-card-main"><div className="approval-title-line"><h3>Leave request</h3><span className="timekeeping-status warning">PENDING</span></div><p>{employeeLabel(item.employeeId)} · {item.days} day{Number(item.days) === 1 ? '' : 's'}</p><small>{day(item.dateFrom)} – {day(item.dateTo)}{item.reason ? ` · ${item.reason}` : ''}</small></div><div className="approval-card-actions"><button type="button" className="session-revoke" onClick={() => void decide(`/api/leave/requests/${item.id}/reject`, 'Leave rejection')} disabled={!!busy || mfaVerified === false}>Reject</button><button type="button" className="security-submit" onClick={() => void decide(`/api/leave/requests/${item.id}/approve`, 'Leave approval')} disabled={!!busy || mfaVerified === false}>{busy === `Leave approval` ? 'Saving…' : 'Approve'}</button></div></article>)}
        {pendingOvertime.filter(item => matches(`${employeeLabel(item.employeeId)} ${item.workDate} ${item.reason} overtime`)).map(item => <article className="approval-card" key={`overtime-${item.id}`}><div className="approval-card-kind"><span className="approval-icon overtime">OT</span></div><div className="approval-card-main"><div className="approval-title-line"><h3>Overtime request</h3><span className="timekeeping-status warning">PENDING</span></div><p>{employeeLabel(item.employeeId)} · {day(item.workDate)}</p><small>{item.minutesRequested} minutes requested · {item.reason}</small></div><div className="approval-card-actions approval-overtime-actions"><label>Approve minutes<input type="number" min="1" max={item.minutesRequested} value={approvedMinutes[item.id] ?? item.minutesRequested} onChange={event => setApprovedMinutes(current => ({ ...current, [item.id]: event.target.value }))} /></label><div><button type="button" className="session-revoke" onClick={() => void decide(`/api/overtime/requests/${item.id}/reject`, 'Overtime rejection')} disabled={!!busy || mfaVerified === false}>Reject</button><button type="button" className="security-submit" onClick={() => void decide(`/api/overtime/requests/${item.id}/approve`, 'Overtime approval', { approvedMinutes: Number(approvedMinutes[item.id] || item.minutesRequested) })} disabled={!!busy || mfaVerified === false}>{busy === 'Overtime approval' ? 'Saving…' : 'Approve'}</button></div></div></article>)}
        {pendingCorrections.filter(item => matches(`${employeeLabel(item.employeeId)} ${item.workDate} ${item.reason} correction`)).map(item => <article className="approval-card" key={`correction-${item.id}`}><div className="approval-card-kind"><span className="approval-icon correction">DTR</span></div><div className="approval-card-main"><div className="approval-title-line"><h3>Attendance correction</h3><span className="timekeeping-status warning">PENDING</span></div><p>{employeeLabel(item.employeeId)} · {day(item.workDate)}</p><small>Requested in {shortTime(item.requestedIn)} · out {shortTime(item.requestedOut)} · {item.reason}</small></div><div className="approval-card-actions"><button type="button" className="session-revoke" onClick={() => void decide(`/api/attendance/corrections/${item.id}/reject`, 'Correction rejection')} disabled={!!busy || mfaVerified === false}>Reject</button><button type="button" className="security-submit" onClick={() => void decide(`/api/attendance/corrections/${item.id}/approve`, 'Correction approval')} disabled={!!busy || mfaVerified === false}>{busy === 'Correction approval' ? 'Saving…' : 'Approve'}</button></div></article>)}
        {!timekeepingCount && <p className="approval-no-pending">No pending timekeeping requests.</p>}
      </section>}
      {visible.payroll && <section className="payroll-panel approval-queue"><div className="payroll-panel-heading"><div><p className="eyebrow">PAYROLL</p><h2>Payroll decisions</h2></div><span>{payrollCount} pending</span></div>
        {pendingAdjustments.filter(item => matches(`${employeeLabel(item.employeeId)} ${item.itemCode} ${item.description || ''} ${item.reason} adjustment`)).map(item => <article className="approval-card" key={`adjustment-${item.id}`}><div className="approval-card-kind"><span className="approval-icon adjustment">₱</span></div><div className="approval-card-main"><div className="approval-title-line"><h3>Payroll adjustment · {item.itemCode}</h3><span className="timekeeping-status warning">PENDING</span></div><p>{employeeLabel(item.employeeId)} · {item.category} · {php(item.amount)}</p><small>{item.description || item.reason}{item.targetPeriodId ? ` · Target period ${periodsById.get(item.targetPeriodId)?.startDate || 'selected'}` : ''}</small></div><div className="approval-card-actions"><button type="button" className="session-revoke" onClick={() => void decide(`/api/payroll/adjustments/${item.id}/reject`, 'Adjustment rejection')} disabled={!!busy || mfaVerified === false || item.requestedBy === currentUserId}>Reject</button><button type="button" className="security-submit" onClick={() => void decide(`/api/payroll/adjustments/${item.id}/approve`, 'Adjustment approval')} disabled={!!busy || mfaVerified === false || item.requestedBy === currentUserId}>{busy === 'Adjustment approval' ? 'Saving…' : 'Approve'}</button></div></article>)}
        {payrollQueue.filter(item => matches(`${item.runType} ${item.status} ${periodsById.get(item.payPeriodId)?.startDate || ''} payroll`)).map(run => { const period = periodsById.get(run.payPeriodId); const action = run.status === 'COMPUTED' ? { path: `/api/payroll/runs/${run.id}/review`, label: 'Mark reviewed', busyLabel: 'Payroll review' } : { path: `/api/payroll/runs/${run.id}/approve`, label: 'Approve run', busyLabel: 'Payroll approval' }; return <article className="approval-card" key={`run-${run.id}`}><div className="approval-card-kind"><span className="approval-icon payroll">₱</span></div><div className="approval-card-main"><div className="approval-title-line"><h3>{run.runType.replaceAll('_', ' ')} payroll</h3><span className="timekeeping-status warning">{run.status}</span></div><p>{period ? `${day(period.startDate)} – ${day(period.endDate)} · Pay date ${day(period.payDate)}` : 'Pay period'} · {run.employeeCount} employees</p><small>Gross {php(run.totalGross)} · Net {php(run.totalNet)}{run.remarks ? ` · ${run.remarks}` : ''}</small></div><div className="approval-card-actions"><button type="button" className="security-submit" onClick={() => void decide(action.path, action.busyLabel)} disabled={!!busy || mfaVerified === false}>{busy === action.busyLabel ? 'Saving…' : action.label}</button></div></article>; })}
        {!payrollCount && <p className="approval-no-pending">No payroll decisions are waiting.</p>}
      </section>}
    </div>}
    <p className="timekeeping-footnote">A decision is saved through the owning workflow API. Payroll adjustments cannot be approved by their requester; HR and payroll actions remain limited by your assigned role.</p>
  </section>;
}
