import { useCallback, useEffect, useMemo, useState } from 'react';

type Page<T> = { content: T[] };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; status: string };
type Adjustment = { id: string; employeeId: string; itemCode: string; description: string | null; category: string; amount: number; reason: string; targetPeriodId: string | null; status: string; requestedBy: string };
type PayrollRun = { id: string; payPeriodId: string; runType: string; status: string; employeeCount: number; totalNet: number; totalGross: number; remarks: string | null };
type PayPeriod = { id: string; startDate: string; endDate: string; payDate: string };
type QueueTab = 'all' | 'timekeeping' | 'payroll' | 'manager' | 'requests';
type ManagerRow = { task_id: string; instance_id: string; process_code: string; business_id: string; summary: string; requester_email: string; first_name: string; last_name: string; label: string; action_type: string; step_no: number };
type MyRequest = { id:string; process_code:string; business_entity:string; business_id:string; summary:string; status:string; current_step:number; created_at:string; completed_at:string|null };
const php = (value: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP' }).format(Number(value || 0));
const day = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${value}T12:00:00`));

export function ApprovalWorkspace({ token, roles, currentUserId, onOpenSecurity }: { token: string; roles: string[]; currentUserId: string; onOpenSecurity: () => void }) {
  const canHr = roles.some(role => ['ROLE_TENANT_ADMIN', 'ROLE_HR_ADMIN'].includes(role));
  const canPayroll = roles.some(role => ['ROLE_TENANT_ADMIN', 'ROLE_PAYROLL_OFFICER'].includes(role));
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [adjustments, setAdjustments] = useState<Adjustment[]>([]);
  const [runs, setRuns] = useState<PayrollRun[]>([]);
  const [periods, setPeriods] = useState<PayPeriod[]>([]);
  const [tab, setTab] = useState<QueueTab>('all');
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [mfaVerified, setMfaVerified] = useState<boolean | null>(null);
  const [managerQueue, setManagerQueue] = useState<ManagerRow[]>([]);
  const [myRequests, setMyRequests] = useState<MyRequest[]>([]);
  const [historyById, setHistoryById] = useState<Record<string, Array<Record<string, unknown>>>>({});

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
      const tasks: Promise<unknown>[] = [];
      if (canHr || canPayroll) tasks.push(request<Page<Employee>>('/api/payroll/employees?size=1000&sort=employeeNo,asc').then(page => setEmployees(page.content)));
      tasks.push(request<ManagerRow[]>('/api/v1/approvals/inbox').then(setManagerQueue));
      tasks.push(request<MyRequest[]>('/api/v1/approvals/my-requests').then(setMyRequests));
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
  const pendingAdjustments = adjustments.filter(item => item.status === 'PENDING');
  const payrollQueue = runs.filter(item => ['COMPUTED', 'REVIEWED'].includes(item.status));
  const timekeepingCount = managerQueue.filter(item => ['LEAVE', 'OVERTIME', 'ATTENDANCE_CORRECTION'].includes(item.process_code)).length;
  const payrollCount = pendingAdjustments.length + payrollQueue.length;
  const managerCount = managerQueue.length;
  const myRequestCount = myRequests.length;
  const employeeLabel = (id: string) => { const employee = employeesById.get(id); return employee ? `${employee.firstName} ${employee.lastName}` : 'Employee'; };
  const matches = (text: string) => !query || text.toLowerCase().includes(query.toLowerCase());
  const visible = { hr: tab !== 'payroll' && tab !== 'manager' && tab !== 'requests' && canHr, payroll: tab !== 'timekeeping' && tab !== 'manager' && tab !== 'requests' && canPayroll, manager: tab === 'all' || tab === 'manager', requests: tab === 'all' || tab === 'requests' };
  const totalVisible = (visible.hr ? timekeepingCount : 0) + (visible.payroll ? payrollCount : 0) + (visible.manager ? managerCount : 0) + (visible.requests ? myRequestCount : 0);

  async function decide(path: string, label: string, body?: unknown) {
    setBusy(label); setError(''); setNotice('');
    try {
      await request(path, { method: 'POST', ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
      setNotice(`${label.split(':')[0]} completed.`); await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'The approval could not be completed.'); }
    finally { setBusy(''); }
  }

  function decideWorkflowTask(taskId: string, decision: 'APPROVE'|'REJECT') {
    const comments = window.prompt(`${decision === 'APPROVE' ? 'Approval' : 'Rejection'} comments (optional):`);
    if (comments === null) return;
    void decide(`/api/v1/approvals/tasks/${taskId}/decision`, 'Workflow decision', { decision, comments });
  }

  const tabs: { id: QueueTab; label: string; count: number; enabled: boolean }[] = [
    { id: 'all', label: 'All approvals', count: (canHr ? timekeepingCount : 0) + (canPayroll ? payrollCount : 0) + managerCount + myRequestCount, enabled: true },
    { id: 'timekeeping', label: 'Timekeeping', count: timekeepingCount, enabled: canHr },
    { id: 'payroll', label: 'Payroll', count: payrollCount, enabled: canPayroll },
    { id: 'manager', label: 'My tasks', count: managerCount, enabled: true },
    { id: 'requests', label: 'My requests', count: myRequestCount, enabled: true },
  ];

  return <section className="timekeeping-workspace approvals-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">WORKFLOW</p><h1>Approval center</h1><p>Review pending requests in one place. The server validates each decision and records its audit trail.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to process approvals</strong><small>Enable and verify MFA in account security, then sign in again to get an authorized session.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics payroll-metrics approval-metrics"><article><span>TIMEKEEPING</span><strong>{timekeepingCount}</strong><small>Assigned workflow tasks</small></article><article><span>PAYROLL</span><strong>{payrollCount}</strong><small>Adjustments, review, approval</small></article><article><span>MY TASKS</span><strong>{managerCount}</strong><small>Awaiting your decision</small></article><article><span>MY REQUESTS</span><strong>{myRequestCount}</strong><small>Submitted workflows</small></article></div>
    <div className="approval-toolbar"><div className="timekeeping-tabs" role="tablist" aria-label="Approval queue">{tabs.filter(item => item.enabled).map(item => <button type="button" role="tab" key={item.id} aria-selected={tab === item.id} className={tab === item.id ? 'active' : ''} onClick={() => setTab(item.id)}>{item.label}<span>{item.count}</span></button>)}</div><label className="timekeeping-search"><span aria-hidden="true">⌕</span><input type="search" placeholder="Search employee or request…" aria-label="Search approvals" value={query} onChange={event => setQuery(event.target.value)} /></label></div>
    {loading ? <div className="payroll-panel"><div className="timekeeping-empty">Loading approval queues…</div></div> : !totalVisible ? <div className="payroll-panel"><div className="approval-empty"><span>✓</span><h2>You're all caught up</h2><p>There are no pending approvals in the queues available to your role.</p></div></div> : <div className="approval-queues">
      {visible.manager && <section className="payroll-panel approval-queue"><div className="payroll-panel-heading"><div><p className="eyebrow">MY TASKS</p><h2>Requests awaiting your decision</h2></div><span>{managerCount} pending</span></div>
        {managerQueue.filter(item=>matches(`${item.first_name} ${item.last_name} ${item.requester_email} ${item.process_code} ${item.summary} ${item.label}`)).map(item=><article className="approval-card" key={`task-${item.task_id}`}><div className="approval-card-kind"><span className="approval-icon leave">{item.process_code==='LEAVE'?'L':item.process_code==='OVERTIME'?'OT':item.process_code==='HEADCOUNT'?'HC':'DTR'}</span></div><div className="approval-card-main"><div className="approval-title-line"><h3>{item.label}</h3><span className="timekeeping-status warning">{item.action_type}</span></div><p>{item.first_name} {item.last_name} · {item.requester_email} · {item.process_code.replaceAll('_',' ')}</p><small>{item.summary} · Step {item.step_no}</small></div><div className="approval-card-actions"><button className="session-revoke" type="button" onClick={()=>decideWorkflowTask(item.task_id,'REJECT')} disabled={!!busy||mfaVerified===false}>Reject</button><button className="security-submit" type="button" onClick={()=>decideWorkflowTask(item.task_id,'APPROVE')} disabled={!!busy||mfaVerified===false}>Approve</button></div></article>)}
        {!managerCount&&<p className="approval-no-pending">No assigned workflow tasks are waiting for your decision.</p>}
      </section>}
      {visible.requests && <section className="payroll-panel approval-queue"><div className="payroll-panel-heading"><div><p className="eyebrow">SUBMITTED BY YOU</p><h2>My requests</h2></div><span>{myRequestCount} requests</span></div>
        {myRequests.filter(item=>matches(`${item.process_code} ${item.summary} ${item.status}`)).map(item=><article className="approval-card" key={`my-${item.id}`}><div className="approval-card-kind"><span className="approval-icon leave">{item.process_code==='LEAVE'?'L':item.process_code==='OVERTIME'?'OT':item.process_code==='HEADCOUNT'?'HC':'DTR'}</span></div><div className="approval-card-main"><div className="approval-title-line"><h3>{item.process_code.replaceAll('_',' ')}</h3><span className="timekeeping-status warning">{item.status}</span></div><p>{item.summary} · {new Date(item.created_at).toLocaleDateString()}</p><small>{item.status==='PENDING'?`Waiting at step ${item.current_step}`:item.completed_at?`Completed ${new Date(item.completed_at).toLocaleString()}`:'Workflow needs administrator attention'}</small>{historyById[item.id]&&<ol className="approval-history">{historyById[item.id].map((event,index)=><li key={String(event.id||index)}><strong>{String(event.event_type||'Event').replaceAll('_',' ')}</strong><small>{String(event.actor_email||'System')} · {new Date(String(event.created_at)).toLocaleString()}{event.details&&typeof event.details==='object'&&'comments' in event.details?` · ${String((event.details as Record<string,unknown>).comments)}`:''}</small></li>)}</ol>}</div><div className="approval-card-actions"><button type="button" className="session-revoke" onClick={()=>{if(historyById[item.id])setHistoryById(current=>{const next={...current};delete next[item.id];return next});else void request<Array<Record<string,unknown>>>(`/api/v1/approvals/requests/${item.id}/history`).then(events=>setHistoryById(current=>({...current,[item.id]:events}))).catch(reason=>setError(reason instanceof Error?reason.message:'Could not load request history.'));}}>{historyById[item.id]?'Hide history':'View progress'}</button></div></article>)}
        {!myRequestCount&&<p className="approval-no-pending">You haven’t submitted workflow requests yet.</p>}
      </section>}
      {visible.hr && <section className="payroll-panel approval-queue"><div className="payroll-panel-heading"><div><p className="eyebrow">TIMEKEEPING</p><h2>Requests in configured workflows</h2></div><span>{timekeepingCount} pending</span></div>
        <p className="approval-no-pending">Leave, overtime, and attendance correction approvals are assigned by the configured workflow. Process them under “My tasks” so only the current assigned approver can decide.</p>
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
