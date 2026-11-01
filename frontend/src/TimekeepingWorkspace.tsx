import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';

type Page<T> = { content: T[]; totalElements: number };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; status: string };
type TimeLog = { id: string; employeeId: string; logTime: string; logType: 'IN' | 'OUT'; source: string; status: string; remarks: string | null };
type DtrDay = { id: string; employeeId: string; workDate: string; firstIn: string | null; lastOut: string | null; regularMinutes: number; lateMinutes: number; undertimeMinutes: number; otDetectedMinutes: number; otApprovedMinutes: number; absent: boolean; paidLeave: boolean; status: string; dayType: string };
type Correction = { id: string; employeeId: string; workDate: string; requestedIn: string | null; requestedOut: string | null; reason: string; status: string };
type WorkspaceRequest = <T>(path: string, init?: RequestInit) => Promise<T>;

const localDate = (date: Date) => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};
const monthStart = () => localDate(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
const nowLocalInput = () => {
  const date = new Date();
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  return date.toISOString().slice(0, 16);
};
const formatDateTime = (value: string | null) => value
  ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : '—';
const minutesLabel = (minutes: number) => `${Math.floor(minutes / 60)}h ${minutes % 60}m`;

export function TimekeepingWorkspace({ token }: { token: string }) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [logs, setLogs] = useState<TimeLog[]>([]);
  const [days, setDays] = useState<DtrDay[]>([]);
  const [corrections, setCorrections] = useState<Correction[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [tab, setTab] = useState<'dtr' | 'punches' | 'corrections'>('dtr');
  const [from, setFrom] = useState(monthStart);
  const [to, setTo] = useState(() => localDate(new Date()));
  const [employeeId, setEmployeeId] = useState('');
  const [manualEmployeeId, setManualEmployeeId] = useState('');
  const [manualType, setManualType] = useState<'IN' | 'OUT'>('IN');
  const [manualTime, setManualTime] = useState(nowLocalInput);
  const [manualRemarks, setManualRemarks] = useState('');
  const [correctionEmployeeId, setCorrectionEmployeeId] = useState('');
  const [correctionDate, setCorrectionDate] = useState(() => localDate(new Date()));
  const [correctionIn, setCorrectionIn] = useState('');
  const [correctionOut, setCorrectionOut] = useState('');
  const [correctionReason, setCorrectionReason] = useState('');
  const [query, setQuery] = useState('');

  const request = useCallback<WorkspaceRequest>(async <T,>(path: string, init: RequestInit = {}) => {
    const response = await fetch(path, {
      ...init,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...init.headers,
      },
    });
    if (!response.ok) throw new Error(await responseError(response));
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [employeePage, logPage, dtrPage, correctionPage] = await Promise.all([
        request<Page<Employee>>('/api/employees?size=200&sort=employeeNo,asc'),
        request<Page<TimeLog>>('/api/time-logs?size=200&sort=logTime,desc'),
        request<Page<DtrDay>>('/api/dtr-days?size=500&sort=workDate,desc'),
        request<Page<Correction>>('/api/attendance-corrections?size=200&sort=workDate,desc'),
      ]);
      setEmployees(employeePage.content);
      setLogs(logPage.content);
      setDays(dtrPage.content);
      setCorrections(correctionPage.content);
      setManualEmployeeId(current => current || employeePage.content[0]?.id || '');
      setCorrectionEmployeeId(current => current || employeePage.content[0]?.id || '');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not load timekeeping records.');
    } finally {
      setLoading(false);
    }
  }, [request]);

  useEffect(() => { void load(); }, [load]);

  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const employeeName = (id: string) => {
    const employee = employeeById.get(id);
    return employee ? `${employee.firstName} ${employee.lastName}` : 'Unknown employee';
  };
  const filteredDays = useMemo(() => days.filter(day =>
    (!from || day.workDate >= from) && (!to || day.workDate <= to) && (!employeeId || day.employeeId === employeeId)
      && (!query || `${employeeName(day.employeeId)} ${day.workDate} ${day.status}`.toLowerCase().includes(query.toLowerCase()))
  ), [days, from, to, employeeId, query, employeeById]);
  const filteredLogs = useMemo(() => logs.filter(log =>
    (!employeeId || log.employeeId === employeeId)
      && (!query || `${employeeName(log.employeeId)} ${log.logType} ${log.source} ${log.status} ${log.remarks || ''}`.toLowerCase().includes(query.toLowerCase()))
  ), [logs, employeeId, query, employeeById]);
  const pendingCorrections = corrections.filter(item => item.status === 'PENDING');
  const flaggedLogs = logs.filter(log => log.status === 'FLAGGED');

  async function processDtr(event: FormEvent) {
    event.preventDefault();
    setBusy('process'); setError(''); setNotice('');
    try {
      const result = await request<{ processedDays: number }>('/api/dtr/process', {
        method: 'POST',
        body: JSON.stringify({ from, to, employeeId: employeeId || null }),
      });
      setNotice(`DTR processing complete. ${result.processedDays} day${result.processedDays === 1 ? '' : 's'} updated; locked days were left unchanged.`);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not process DTR.');
    } finally { setBusy(''); }
  }

  async function recordManualPunch(event: FormEvent) {
    event.preventDefault();
    if (!manualEmployeeId) return;
    setBusy('manual'); setError(''); setNotice('');
    try {
      await request<TimeLog>('/api/attendance/manual', {
        method: 'POST',
        body: JSON.stringify({ employeeId: manualEmployeeId, logType: manualType, logTime: new Date(manualTime).toISOString(), remarks: manualRemarks.trim() || null }),
      });
      setNotice(`Manual ${manualType.toLowerCase()} punch recorded for ${employeeName(manualEmployeeId)}.`);
      setManualRemarks('');
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not record the punch.');
    } finally { setBusy(''); }
  }

  async function validatePunch(log: TimeLog) {
    setBusy(log.id); setError(''); setNotice('');
    try {
      await request<TimeLog>(`/api/attendance/logs/${log.id}/validate`, { method: 'POST' });
      setNotice(`Punch accepted for ${employeeName(log.employeeId)}.`);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not accept the punch.');
    } finally { setBusy(''); }
  }

  async function fileCorrection(event: FormEvent) {
    event.preventDefault();
    if (!correctionEmployeeId || (!correctionIn && !correctionOut)) return;
    setBusy('correction'); setError(''); setNotice('');
    try {
      await request<Correction>('/api/attendance/corrections', {
        method: 'POST',
        body: JSON.stringify({
          employeeId: correctionEmployeeId,
          workDate: correctionDate,
          requestedIn: correctionIn ? new Date(correctionIn).toISOString() : null,
          requestedOut: correctionOut ? new Date(correctionOut).toISOString() : null,
          reason: correctionReason.trim(),
        }),
      });
      setNotice(`Attendance correction filed for ${employeeName(correctionEmployeeId)}.`);
      setCorrectionIn(''); setCorrectionOut(''); setCorrectionReason('');
      await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not file the correction.'); }
    finally { setBusy(''); }
  }

  async function decideCorrection(correction: Correction, decision: 'approve' | 'reject') {
    setBusy(correction.id); setError(''); setNotice('');
    try {
      await request<Correction>(`/api/attendance/corrections/${correction.id}/${decision}`, { method: 'POST' });
      setNotice(`Attendance correction ${decision === 'approve' ? 'approved' : 'rejected'} for ${employeeName(correction.employeeId)}.`);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not update the correction.');
    } finally { setBusy(''); }
  }

  return <section className="timekeeping-workspace">
    <header className="timekeeping-heading">
      <div><p className="eyebrow">TIMEKEEPING</p><h1>Time &amp; attendance</h1><p>Review punches, process daily time records, and handle attendance corrections.</p></div>
      <button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>↻ Refresh</button>
    </header>
    {error && <div className="platform-alert" role="alert">{error}</div>}
    {notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics">
      <article><span>Employees</span><strong>{employees.length}</strong><small>In this workspace</small></article>
      <article><span>Attendance records</span><strong>{days.length}</strong><small>Loaded DTR days</small></article>
      <article><span>Flagged punches</span><strong>{flaggedLogs.length}</strong><small>Need validation</small></article>
      <article><span>Pending corrections</span><strong>{pendingCorrections.length}</strong><small>Awaiting HR review</small></article>
    </div>
    <nav className="timekeeping-tabs" aria-label="Timekeeping sections" role="tablist">
      <button type="button" role="tab" aria-selected={tab === 'dtr'} className={tab === 'dtr' ? 'active' : ''} onClick={() => { setTab('dtr'); setQuery(''); }}>Daily time records</button>
      <button type="button" role="tab" aria-selected={tab === 'punches'} className={tab === 'punches' ? 'active' : ''} onClick={() => { setTab('punches'); setQuery(''); }}>Punches{flaggedLogs.length > 0 && <span>{flaggedLogs.length}</span>}</button>
      <button type="button" role="tab" aria-selected={tab === 'corrections'} className={tab === 'corrections' ? 'active' : ''} onClick={() => { setTab('corrections'); setQuery(''); }}>Corrections{pendingCorrections.length > 0 && <span>{pendingCorrections.length}</span>}</button>
    </nav>

    {tab === 'dtr' && <>
      <form className="timekeeping-filters" onSubmit={processDtr}>
        <label><span>From</span><input type="date" value={from} max={to || undefined} onChange={event => setFrom(event.currentTarget.value)} required /></label>
        <label><span>To</span><input type="date" value={to} min={from || undefined} onChange={event => setTo(event.currentTarget.value)} required /></label>
        <label className="timekeeping-employee-filter"><span>Employee</span><select value={employeeId} onChange={event => setEmployeeId(event.currentTarget.value)}><option value="">All employees</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label>
        <button type="submit" className="security-submit" disabled={busy === 'process' || loading || !employees.length}>{busy === 'process' ? 'Processing…' : 'Process DTR'}</button>
      </form>
      <label className="timekeeping-search"><span aria-hidden="true">⌕</span><input type="search" value={query} onChange={event => setQuery(event.currentTarget.value)} placeholder="Search employee or status" /></label>
      <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>WORK DATE</th><th>FIRST IN</th><th>LAST OUT</th><th>REGULAR</th><th>LATE</th><th>OVERTIME</th><th>STATUS</th></tr></thead><tbody>
        {loading ? <tr><td colSpan={8} className="timekeeping-empty">Loading daily records…</td></tr> : filteredDays.length ? filteredDays.map(day => <tr key={day.id}><td><strong>{employeeName(day.employeeId)}</strong><small>{day.dayType?.replaceAll('_', ' ') || '—'}</small></td><td>{day.workDate}</td><td>{formatDateTime(day.firstIn)}</td><td>{formatDateTime(day.lastOut)}</td><td>{minutesLabel(day.regularMinutes || 0)}</td><td>{minutesLabel(day.lateMinutes || 0)}</td><td>{minutesLabel(day.otApprovedMinutes || 0)}{day.otDetectedMinutes > day.otApprovedMinutes && <small> {minutesLabel(day.otDetectedMinutes)} detected</small>}</td><td><span className={`timekeeping-status ${day.status === 'LOCKED' ? 'locked' : day.absent ? 'warning' : 'open'}`}>{day.absent ? 'ABSENT' : day.paidLeave ? 'PAID LEAVE' : day.status}</span></td></tr>) : <tr><td colSpan={8} className="timekeeping-empty">{days.length ? 'No DTR records match these filters. Process the selected range to build daily records.' : 'No DTR records yet. Select a date range and process it after schedules and punches are set up.'}</td></tr>}
      </tbody></table></div>
    </>}

    {tab === 'punches' && <>
      <form className="timekeeping-manual-form" onSubmit={event => void recordManualPunch(event)}>
        <div><p className="eyebrow">MANUAL ENTRY</p><h2>Record a punch</h2><p>HR-entered punches are labelled MANUAL and use the selected local time.</p></div>
        <label><span>Employee</span><select value={manualEmployeeId} onChange={event => setManualEmployeeId(event.currentTarget.value)} required><option value="">Select employee</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label>
        <label><span>Type</span><select value={manualType} onChange={event => setManualType(event.currentTarget.value as 'IN' | 'OUT')}><option value="IN">Clock in</option><option value="OUT">Clock out</option></select></label>
        <label><span>Date and time</span><input type="datetime-local" value={manualTime} onChange={event => setManualTime(event.currentTarget.value)} required /></label>
        <label className="timekeeping-remarks"><span>Reason / note</span><input value={manualRemarks} onChange={event => setManualRemarks(event.currentTarget.value)} maxLength={500} placeholder="Optional note for the attendance record" /></label>
        <button type="submit" className="security-submit" disabled={busy === 'manual' || !manualEmployeeId}>{busy === 'manual' ? 'Saving…' : 'Record punch'}</button>
      </form>
      <label className="timekeeping-search"><span aria-hidden="true">⌕</span><input type="search" value={query} onChange={event => setQuery(event.currentTarget.value)} placeholder="Search employee, source, or status" /></label>
      <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>TIME</th><th>TYPE</th><th>SOURCE</th><th>STATUS</th><th>NOTE</th><th></th></tr></thead><tbody>
        {loading ? <tr><td colSpan={7} className="timekeeping-empty">Loading punches…</td></tr> : filteredLogs.length ? filteredLogs.map(log => <tr key={log.id}><td><strong>{employeeName(log.employeeId)}</strong></td><td>{formatDateTime(log.logTime)}</td><td>{log.logType}</td><td>{log.source}</td><td><span className={`timekeeping-status ${log.status === 'FLAGGED' ? 'warning' : 'open'}`}>{log.status}</span></td><td className="timekeeping-note-cell">{log.remarks || '—'}</td><td>{log.status === 'FLAGGED' && <button type="button" className="session-revoke" onClick={() => void validatePunch(log)} disabled={busy === log.id}>{busy === log.id ? 'Saving…' : 'Accept punch'}</button>}</td></tr>) : <tr><td colSpan={7} className="timekeeping-empty">No punches match your search.</td></tr>}
      </tbody></table></div>
    </>}

    {tab === 'corrections' && <>
      <form className="timekeeping-manual-form correction-file-form" onSubmit={event => void fileCorrection(event)}>
        <div><p className="eyebrow">NEW REQUEST</p><h2>File attendance correction</h2><p>At least one corrected punch is required. HR review records the decision-maker.</p></div>
        <label><span>Employee</span><select value={correctionEmployeeId} onChange={event => setCorrectionEmployeeId(event.currentTarget.value)} required><option value="">Select employee</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label>
        <label><span>Work date</span><input type="date" value={correctionDate} onChange={event => setCorrectionDate(event.currentTarget.value)} required /></label>
        <label><span>Corrected clock-in (optional)</span><input type="datetime-local" value={correctionIn} onChange={event => setCorrectionIn(event.currentTarget.value)} /></label>
        <label><span>Corrected clock-out (optional)</span><input type="datetime-local" value={correctionOut} onChange={event => setCorrectionOut(event.currentTarget.value)} /></label>
        <label className="timekeeping-remarks"><span>Reason</span><input value={correctionReason} onChange={event => setCorrectionReason(event.currentTarget.value)} maxLength={500} placeholder="Explain the correction" required /></label>
        <button type="submit" className="security-submit" disabled={busy === 'correction' || !correctionEmployeeId || (!correctionIn && !correctionOut)}>{busy === 'correction' ? 'Filing…' : 'File correction'}</button>
      </form>
      <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>WORK DATE</th><th>REQUESTED IN</th><th>REQUESTED OUT</th><th>REASON</th><th>STATUS</th><th></th></tr></thead><tbody>
      {loading ? <tr><td colSpan={7} className="timekeeping-empty">Loading corrections…</td></tr> : corrections.length ? corrections.map(correction => <tr key={correction.id}><td><strong>{employeeName(correction.employeeId)}</strong></td><td>{correction.workDate}</td><td>{formatDateTime(correction.requestedIn)}</td><td>{formatDateTime(correction.requestedOut)}</td><td className="timekeeping-note-cell">{correction.reason || '—'}</td><td><span className={`timekeeping-status ${correction.status === 'PENDING' ? 'warning' : correction.status === 'APPROVED' ? 'open' : 'locked'}`}>{correction.status}</span></td><td>{correction.status === 'PENDING' && <div className="timekeeping-row-actions"><button type="button" className="session-revoke" onClick={() => void decideCorrection(correction, 'approve')} disabled={busy === correction.id}>{busy === correction.id ? 'Saving…' : 'Approve'}</button><button type="button" className="timekeeping-reject" onClick={() => void decideCorrection(correction, 'reject')} disabled={busy === correction.id}>Reject</button></div>}</td></tr>) : <tr><td colSpan={7} className="timekeeping-empty">No attendance correction requests have been filed.</td></tr>}
      </tbody></table></div>
    </>}
    <p className="timekeeping-footnote">DTR processing uses the employee’s assigned schedule, valid punches, approved leave, holidays, and overtime. Locked payroll days are skipped.</p>
  </section>;
}

async function responseError(response: Response): Promise<string> {
  let body: { message?: string; detail?: string } = {};
  try { body = await response.json() as typeof body; } catch { /* use status text */ }
  if (response.status === 401) return 'Your session has expired. Sign in again.';
  if (response.status === 403) return 'This action needs HR administrator or workspace owner access and verified MFA.';
  return body.detail || body.message || `Request failed (${response.status}).`;
}
