import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';

type Page<T> = { content: T[] };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; status: string };
type LeaveType = { id: string; code: string; name: string; paid: boolean; annualEntitlement: number | null };
type LeaveRequest = { id: string; employeeId: string; leaveTypeId: string; dateFrom: string; dateTo: string; days: number; halfDay: boolean; reason: string | null; status: string };
type LeaveLedger = { employeeId: string; leaveTypeId: string; days: number };

function localDateValue(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function LeaveWorkspace({ token }: { token: string }) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [types, setTypes] = useState<LeaveType[]>([]);
  const [requests, setRequests] = useState<LeaveRequest[]>([]);
  const [balances, setBalances] = useState<Map<string, number>>(new Map());
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [tab, setTab] = useState<'requests' | 'balances'>('requests');
  const [employeeId, setEmployeeId] = useState('');
  const [leaveTypeId, setLeaveTypeId] = useState('');
  const [dateFrom, setDateFrom] = useState(() => localDateValue(new Date()));
  const [dateTo, setDateTo] = useState(() => localDateValue(new Date()));
  const [days, setDays] = useState('1');
  const [halfDay, setHalfDay] = useState(false);
  const [reason, setReason] = useState('');
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
      const [employeePage, typePage, requestPage, ledgerPage] = await Promise.all([
        request<Page<Employee>>('/api/employees?size=500&sort=employeeNo,asc'),
        request<Page<LeaveType>>('/api/leave-types?size=200&sort=name,asc'),
        request<Page<LeaveRequest>>('/api/leave-requests?size=500&sort=dateFrom,desc'),
        request<Page<LeaveLedger>>('/api/leave-ledgers?size=2000&sort=entryDate,desc'),
      ]);
      setEmployees(employeePage.content.filter(employee => employee.status !== 'SEPARATED'));
      setTypes(typePage.content);
      setRequests(requestPage.content);
      const nextBalances = new Map<string, number>();
      for (const entry of ledgerPage.content) {
        const key = `${entry.employeeId}:${entry.leaveTypeId}`;
        nextBalances.set(key, (nextBalances.get(key) || 0) + Number(entry.days || 0));
      }
      setBalances(nextBalances);
      setEmployeeId(current => current || employeePage.content[0]?.id || '');
      setLeaveTypeId(current => current || typePage.content[0]?.id || '');
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load leave records.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => { void load(); }, [load]);

  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const typeById = useMemo(() => new Map(types.map(type => [type.id, type])), [types]);
  const employeeLabel = (id: string) => { const item = employeeById.get(id); return item ? `${item.firstName} ${item.lastName}` : 'Unknown employee'; };
  const filteredRequests = requests.filter(item => statusFilter === 'ALL' || item.status === statusFilter);
  const pendingCount = requests.filter(item => item.status === 'PENDING').length;
  const activeEmployees = employees.filter(employee => employee.status === 'ACTIVE' || employee.status === 'ON_LEAVE').length;

  async function fileLeave(event: FormEvent) {
    event.preventDefault(); setBusy('file'); setError(''); setNotice('');
    try {
      await request<LeaveRequest>('/api/leave/requests', { method: 'POST', body: JSON.stringify({ employeeId, leaveTypeId, dateFrom, dateTo, days: Number(days), halfDay, reason: reason.trim() }) });
      setNotice(`Leave request filed for ${employeeLabel(employeeId)}.`);
      setReason(''); setDays('1'); setHalfDay(false);
      await load();
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not file the leave request.'); }
    finally { setBusy(''); }
  }

  async function decide(item: LeaveRequest, action: 'approve' | 'reject' | 'cancel') {
    setBusy(item.id); setError(''); setNotice('');
    try {
      await request(`/api/leave/requests/${item.id}/${action}`, { method: 'POST' });
      setNotice(`Leave request ${action === 'approve' ? 'approved' : action === 'reject' ? 'rejected' : 'cancelled'}.`);
      await load();
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not update the leave request.'); }
    finally { setBusy(''); }
  }

  return <section className="timekeeping-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">TIMEKEEPING</p><h1>Leave management</h1><p>File leave, review requests, and track balances from the append-only leave ledger.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>↻ Refresh</button></header>
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics"><article><span>Employees</span><strong>{activeEmployees}</strong><small>Active or on leave</small></article><article><span>Leave types</span><strong>{types.length}</strong><small>Configured for workspace</small></article><article><span>Requests</span><strong>{requests.length}</strong><small>Loaded requests</small></article><article><span>Pending approval</span><strong>{pendingCount}</strong><small>Awaiting HR decision</small></article></div>
    <div className="timekeeping-tabs" role="tablist" aria-label="Leave sections"><button type="button" role="tab" aria-selected={tab === 'requests'} className={tab === 'requests' ? 'active' : ''} onClick={() => setTab('requests')}>Requests{pendingCount > 0 && <span>{pendingCount}</span>}</button><button type="button" role="tab" aria-selected={tab === 'balances'} className={tab === 'balances' ? 'active' : ''} onClick={() => setTab('balances')}>Balances</button></div>
    {tab === 'requests' && <>
      <form className="timekeeping-manual-form leave-file-form" onSubmit={event => void fileLeave(event)}>
        <div><p className="eyebrow">NEW REQUEST</p><h2>File employee leave</h2><p>Requests are pending until an authorized HR user approves or rejects them.</p></div>
        <label><span>Employee</span><select value={employeeId} onChange={event => setEmployeeId(event.currentTarget.value)} required><option value="">Select employee</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label>
        <label><span>Leave type</span><select value={leaveTypeId} onChange={event => setLeaveTypeId(event.currentTarget.value)} required><option value="">Select leave type</option>{types.map(type => <option key={type.id} value={type.id}>{type.name} · {type.code}</option>)}</select></label>
        <label><span>From</span><input type="date" value={dateFrom} onChange={event => { setDateFrom(event.currentTarget.value); if (dateTo < event.currentTarget.value) setDateTo(event.currentTarget.value); }} required /></label>
        <label><span>To</span><input type="date" value={dateTo} min={dateFrom} onChange={event => setDateTo(event.currentTarget.value)} required /></label>
        <label><span>Days</span><input type="number" min="0.5" step="0.5" value={days} onChange={event => setDays(event.currentTarget.value)} required /></label>
        <label className="leave-half-day"><input type="checkbox" checked={halfDay} onChange={event => { setHalfDay(event.currentTarget.checked); if (event.currentTarget.checked) setDays('0.5'); }} /><span>Half day</span></label>
        <label className="timekeeping-remarks"><span>Reason</span><input value={reason} onChange={event => setReason(event.currentTarget.value)} maxLength={1000} placeholder="Reason for leave" required /></label>
        <button type="submit" className="security-submit" disabled={busy === 'file' || !employeeId || !leaveTypeId || !types.length}>{busy === 'file' ? 'Filing…' : 'File leave request'}</button>
      </form>
      <div className="leave-list-toolbar"><label><span>Show</span><select value={statusFilter} onChange={event => setStatusFilter(event.currentTarget.value)}><option value="ALL">All requests</option><option value="PENDING">Pending</option><option value="APPROVED">Approved</option><option value="REJECTED">Rejected</option><option value="CANCELLED">Cancelled</option></select></label><span>{filteredRequests.length} request{filteredRequests.length === 1 ? '' : 's'}</span></div>
      <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>LEAVE TYPE</th><th>DATES</th><th>DAYS</th><th>REASON</th><th>STATUS</th><th></th></tr></thead><tbody>{loading ? <tr><td colSpan={7} className="timekeeping-empty">Loading leave requests…</td></tr> : filteredRequests.length ? filteredRequests.map(item => <tr key={item.id}><td><strong>{employeeLabel(item.employeeId)}</strong></td><td>{typeById.get(item.leaveTypeId)?.name || 'Unknown type'}</td><td>{item.dateFrom} – {item.dateTo}{item.halfDay && <small>Half day</small>}</td><td>{Number(item.days).toLocaleString()}</td><td className="timekeeping-note-cell">{item.reason || '—'}</td><td><span className={`timekeeping-status ${item.status === 'PENDING' ? 'warning' : item.status === 'APPROVED' ? 'open' : 'locked'}`}>{item.status}</span></td><td><div className="timekeeping-row-actions">{item.status === 'PENDING' && <><button type="button" className="session-revoke" onClick={() => void decide(item, 'approve')} disabled={busy === item.id}>{busy === item.id ? 'Saving…' : 'Approve'}</button><button type="button" className="timekeeping-reject" onClick={() => void decide(item, 'reject')} disabled={busy === item.id}>Reject</button></>}{item.status === 'APPROVED' && <button type="button" className="timekeeping-reject" onClick={() => void decide(item, 'cancel')} disabled={busy === item.id}>Cancel</button>}</div></td></tr>) : <tr><td colSpan={7} className="timekeeping-empty">No leave requests match this status.</td></tr>}</tbody></table></div>
    </>}
    {tab === 'balances' && <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th>{types.map(type => <th key={type.id}>{type.name.toUpperCase()}<small>{type.annualEntitlement == null ? 'LEDGER BALANCE' : `${type.annualEntitlement} DAYS / YEAR`}</small></th>)}</tr></thead><tbody>{loading ? <tr><td colSpan={Math.max(types.length + 1, 2)} className="timekeeping-empty">Loading leave balances…</td></tr> : employees.length && types.length ? employees.map(employee => <tr key={employee.id}><td><strong>{employee.firstName} {employee.lastName}</strong><small>{employee.employeeNo}</small></td>{types.map(type => <td key={type.id}>{(balances.get(`${employee.id}:${type.id}`) || 0).toLocaleString()} days</td>)}</tr>) : <tr><td colSpan={Math.max(types.length + 1, 2)} className="timekeeping-empty">Add employees and leave types to see balances.</td></tr>}</tbody></table></div>}
    <p className="timekeeping-footnote">Balances are calculated by summing ledger entries. Approving a paid leave request posts its usage to the ledger; cancellation posts a reversal.</p>
  </section>;
}
