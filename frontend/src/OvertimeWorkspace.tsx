import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';

type Page<T> = { content: T[] };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; status: string };
type OvertimeRequest = { id: string; employeeId: string; workDate: string; minutesRequested: number; approvedMinutes: number | null; reason: string; status: string };

export function OvertimeWorkspace({ token }: { token: string }) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [requests, setRequests] = useState<OvertimeRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [employeeId, setEmployeeId] = useState('');
  const [workDate, setWorkDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [minutes, setMinutes] = useState('60');
  const [reason, setReason] = useState('');
  const [approvedMinutes, setApprovedMinutes] = useState<Record<string, string>>({});
  const [statusFilter, setStatusFilter] = useState('ALL');

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    const response = await fetch(path, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers } });
    if (!response.ok) {
      let message = `Request failed (${response.status}).`;
      try { const body = await response.json() as { message?: string; detail?: string }; message = body.detail || body.message || message; } catch { /* status message is enough */ }
      if (response.status === 401) message = 'Your session has expired. Sign in again.';
      if (response.status === 403) message = 'This action needs HR administrator or workspace owner access and verified MFA.';
      throw new Error(message);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [employeePage, requestPage] = await Promise.all([
        request<Page<Employee>>('/api/employees?size=500&sort=employeeNo,asc'),
        request<Page<OvertimeRequest>>('/api/overtime-requests?size=500&sort=workDate,desc'),
      ]);
      const activeEmployees = employeePage.content.filter(employee => employee.status !== 'SEPARATED');
      setEmployees(activeEmployees); setRequests(requestPage.content);
      setEmployeeId(current => current || activeEmployees[0]?.id || '');
      setApprovedMinutes(current => {
        const next = { ...current };
        for (const item of requestPage.content) if (next[item.id] == null) next[item.id] = String(item.approvedMinutes ?? item.minutesRequested);
        return next;
      });
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not load overtime requests.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => { void load(); }, [load]);

  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const employeeLabel = (id: string) => { const employee = employeeById.get(id); return employee ? `${employee.firstName} ${employee.lastName}` : 'Unknown employee'; };
  const pendingCount = requests.filter(item => item.status === 'PENDING').length;
  const filteredRequests = requests.filter(item => statusFilter === 'ALL' || item.status === statusFilter);
  const totalApprovedHours = requests.filter(item => item.status === 'APPROVED').reduce((sum, item) => sum + Number(item.approvedMinutes || 0), 0) / 60;

  async function fileRequest(event: FormEvent) {
    event.preventDefault(); setBusy('file'); setError(''); setNotice('');
    try {
      await request<OvertimeRequest>('/api/overtime/requests', { method: 'POST', body: JSON.stringify({ employeeId, workDate, minutes: Number(minutes), reason: reason.trim() }) });
      setNotice(`Overtime request filed for ${employeeLabel(employeeId)}.`); setReason(''); setMinutes('60'); await load();
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not file the overtime request.'); }
    finally { setBusy(''); }
  }

  async function decide(item: OvertimeRequest, action: 'approve' | 'reject') {
    setBusy(item.id); setError(''); setNotice('');
    try {
      const body = action === 'approve' ? JSON.stringify({ approvedMinutes: Number(approvedMinutes[item.id] || item.minutesRequested) }) : undefined;
      await request(`/api/overtime/requests/${item.id}/${action}`, { method: 'POST', ...(body ? { body } : {}) });
      setNotice(`Overtime request ${action === 'approve' ? 'approved' : 'rejected'} for ${employeeLabel(item.employeeId)}.`); await load();
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not update the overtime request.'); }
    finally { setBusy(''); }
  }

  return <section className="timekeeping-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">TIMEKEEPING</p><h1>Overtime</h1><p>Review requests against detected work and approve only payable minutes.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>↻ Refresh</button></header>
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics"><article><span>Employees</span><strong>{employees.length}</strong><small>Available to file</small></article><article><span>Requests</span><strong>{requests.length}</strong><small>Loaded overtime records</small></article><article><span>Pending approval</span><strong>{pendingCount}</strong><small>Awaiting HR decision</small></article><article><span>Approved time</span><strong>{totalApprovedHours.toLocaleString(undefined, { maximumFractionDigits: 1 })}h</strong><small>Across approved requests</small></article></div>
    <form className="timekeeping-manual-form overtime-file-form" onSubmit={event => void fileRequest(event)}>
      <div><p className="eyebrow">NEW REQUEST</p><h2>File overtime</h2><p>Approved overtime flows into DTR only when the time is detected in valid attendance punches.</p></div>
      <label><span>Employee</span><select value={employeeId} onChange={event => setEmployeeId(event.currentTarget.value)} required><option value="">Select employee</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label>
      <label><span>Work date</span><input type="date" value={workDate} onChange={event => setWorkDate(event.currentTarget.value)} required /></label>
      <label><span>Minutes requested</span><input type="number" min="1" step="1" value={minutes} onChange={event => setMinutes(event.currentTarget.value)} required /></label>
      <label className="timekeeping-remarks"><span>Reason</span><input value={reason} onChange={event => setReason(event.currentTarget.value)} maxLength={500} placeholder="Work performed beyond scheduled hours" required /></label>
      <button type="submit" className="security-submit" disabled={busy === 'file' || !employeeId}>{busy === 'file' ? 'Filing…' : 'File request'}</button>
    </form>
    <div className="leave-list-toolbar"><label><span>Show</span><select value={statusFilter} onChange={event => setStatusFilter(event.currentTarget.value)}><option value="ALL">All requests</option><option value="PENDING">Pending</option><option value="APPROVED">Approved</option><option value="REJECTED">Rejected</option></select></label><span>{filteredRequests.length} request{filteredRequests.length === 1 ? '' : 's'}</span></div>
    <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>WORK DATE</th><th>REQUESTED</th><th>APPROVED</th><th>REASON</th><th>STATUS</th><th></th></tr></thead><tbody>{loading ? <tr><td colSpan={7} className="timekeeping-empty">Loading overtime requests…</td></tr> : filteredRequests.length ? filteredRequests.map(item => <tr key={item.id}><td><strong>{employeeLabel(item.employeeId)}</strong></td><td>{item.workDate}</td><td>{item.minutesRequested} min</td><td>{item.status === 'PENDING' ? <input aria-label={`Approved minutes for ${employeeLabel(item.employeeId)}`} className="overtime-approved-input" type="number" min="1" max={item.minutesRequested} value={approvedMinutes[item.id] ?? item.minutesRequested} onChange={event => setApprovedMinutes(current => ({ ...current, [item.id]: event.currentTarget.value }))} /> : `${item.approvedMinutes ?? '—'} min`}</td><td className="timekeeping-note-cell">{item.reason}</td><td><span className={`timekeeping-status ${item.status === 'PENDING' ? 'warning' : item.status === 'APPROVED' ? 'open' : 'locked'}`}>{item.status}</span></td><td><div className="timekeeping-row-actions">{item.status === 'PENDING' && <><button type="button" className="session-revoke" onClick={() => void decide(item, 'approve')} disabled={busy === item.id}>{busy === item.id ? 'Saving…' : 'Approve'}</button><button type="button" className="timekeeping-reject" onClick={() => void decide(item, 'reject')} disabled={busy === item.id}>Reject</button></>}</div></td></tr>) : <tr><td colSpan={7} className="timekeeping-empty">No overtime requests match this status.</td></tr>}</tbody></table></div>
    <p className="timekeeping-footnote">Overtime minutes are capped at the requested amount. The payroll calculation uses the smaller of the approved minutes and time detected after schedule processing.</p>
  </section>;
}
