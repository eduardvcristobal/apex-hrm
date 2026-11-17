import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { WorkspaceModal } from './WorkspaceModal';

type Page<T> = { content: T[] };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; status: string };
type LeaveType = { id: string; code: string; name: string; paid: boolean; annualEntitlement: number | null; hireProrationEnabled: boolean; convertible: boolean; carryOverMax: number | null };
type LeaveRequest = { id: string; employeeId: string; leaveTypeId: string; dateFrom: string; dateTo: string; days: number; halfDay: boolean; reason: string | null; status: string };
type LeaveLedger = { employeeId: string; leaveTypeId: string; days: number; entryDate:string };
type BenefitProfile = { id:string; code:string; name:string; employee_class:string; description:string|null; active:boolean };
type BenefitEntitlement = { id:string; leave_type_id:string; leave_code:string; leave_name:string; annual_entitlement:number; grant_basis:string; paid:boolean; hire_proration_enabled:boolean; convertible:boolean; carry_over_max:number|null; effective_from:string; effective_to:string|null };
type BenefitAssignment = { id:string; employee_id:string; employee_no:string; first_name:string; last_name:string; profile_id:string; profile_code:string; profile_name:string; employee_class:string; effective_from:string; effective_to:string|null; reason:string|null };
type EventClaim = { id:string; employee_id:string; employee_no:string; first_name:string; last_name:string; leave_type_id:string; leave_code:string; leave_name:string; event_date:string; event_reference:string; requested_days:number; evidence_reference:string; reason:string|null; status:string; eligibility_verified:boolean; requested_by:string; employer_salary_differential:number; agency_reimbursement_due:number; reimbursement_status:string; reimbursement_reference:string|null };

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
  const [tab, setTab] = useState<'requests' | 'claims' | 'balances' | 'types' | 'benefits'>('requests');
  const [typeCode, setTypeCode] = useState('');
  const [typeName, setTypeName] = useState('');
  const [annualEntitlement, setAnnualEntitlement] = useState('');
  const [typePaid, setTypePaid] = useState(true);
  const [hireProrationEnabled, setHireProrationEnabled] = useState(true);
  const [convertible, setConvertible] = useState(false);
  const [carryOverMax, setCarryOverMax] = useState('');
  const [employeeId, setEmployeeId] = useState('');
  const [leaveTypeId, setLeaveTypeId] = useState('');
  const [dateFrom, setDateFrom] = useState(() => localDateValue(new Date()));
  const [dateTo, setDateTo] = useState(() => localDateValue(new Date()));
  const [days, setDays] = useState('1');
  const [halfDay, setHalfDay] = useState(false);
  const [reason, setReason] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [benefitProfiles,setBenefitProfiles]=useState<BenefitProfile[]>([]);
  const [benefitEntitlements,setBenefitEntitlements]=useState<BenefitEntitlement[]>([]);
  const [benefitAssignments,setBenefitAssignments]=useState<BenefitAssignment[]>([]);
  const [eventClaims,setEventClaims]=useState<EventClaim[]>([]);
  const [eligibilityChecks,setEligibilityChecks]=useState<Record<string,boolean>>({});
  const [claimEmployeeId,setClaimEmployeeId]=useState('');const [claimTypeId,setClaimTypeId]=useState('');const [claimEventDate,setClaimEventDate]=useState(()=>localDateValue(new Date()));const [claimReference,setClaimReference]=useState('');const [claimDays,setClaimDays]=useState('');const [claimEvidence,setClaimEvidence]=useState('');const [claimReason,setClaimReason]=useState('');
  const [benefitProfileId,setBenefitProfileId]=useState('');
  const [benefitCode,setBenefitCode]=useState('');const [benefitName,setBenefitName]=useState('');const [benefitClass,setBenefitClass]=useState('LOCAL');const [benefitDescription,setBenefitDescription]=useState('');
  const [benefitLeaveTypeId,setBenefitLeaveTypeId]=useState('');const [benefitDays,setBenefitDays]=useState('');const [benefitPaid,setBenefitPaid]=useState(true);const [benefitProrate,setBenefitProrate]=useState(true);const [benefitConvertible,setBenefitConvertible]=useState(false);const [benefitCarryMax,setBenefitCarryMax]=useState('');const [benefitEffectiveFrom,setBenefitEffectiveFrom]=useState(()=>localDateValue(new Date()));const [benefitGrantBasis,setBenefitGrantBasis]=useState('ANNUAL');
  const [assignEmployeeId,setAssignEmployeeId]=useState('');const [assignProfileId,setAssignProfileId]=useState('');const [assignDate,setAssignDate]=useState(()=>localDateValue(new Date()));const [assignReason,setAssignReason]=useState('');

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
      const [employeePage, typePage, requestPage, ledgerPage, profiles, assignments, claims] = await Promise.all([
        request<Page<Employee>>('/api/employees?size=500&sort=employeeNo,asc'),
        request<Page<LeaveType>>('/api/leave-types?size=200&sort=name,asc'),
        request<Page<LeaveRequest>>('/api/leave-requests?size=500&sort=dateFrom,desc'),
        request<Page<LeaveLedger>>('/api/leave-ledgers?size=2000&sort=entryDate,desc'),
        request<BenefitProfile[]>('/api/leave-benefit-profiles'),
        request<BenefitAssignment[]>('/api/leave-benefit-profiles/assignments'),
        request<EventClaim[]>('/api/leave-benefit-claims'),
      ]);
      setEmployees(employeePage.content.filter(employee => employee.status !== 'SEPARATED'));
      setTypes(typePage.content);
      setRequests(requestPage.content);
      setBenefitProfiles(profiles);setBenefitAssignments(assignments);
      setEventClaims(claims);
      const nextBalances = new Map<string, number>();
      for (const entry of ledgerPage.content) {
        if(entry.entryDate>localDateValue(new Date())) continue;
        const key = `${entry.employeeId}:${entry.leaveTypeId}`;
        nextBalances.set(key, (nextBalances.get(key) || 0) + Number(entry.days || 0));
      }
      setBalances(nextBalances);
      setEmployeeId(current => current || employeePage.content[0]?.id || '');
      setLeaveTypeId(current => current || typePage.content[0]?.id || '');
      setBenefitProfileId(current=>current||profiles[0]?.id||'');setAssignProfileId(current=>current||profiles[0]?.id||'');setBenefitLeaveTypeId(current=>current||typePage.content[0]?.id||'');setAssignEmployeeId(current=>current||employeePage.content[0]?.id||'');
      setClaimEmployeeId(current=>current||employeePage.content[0]?.id||'');setClaimTypeId(current=>current||typePage.content.find(type=>['ML','PL'].includes(type.code))?.id||'');
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load leave records.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => { void load(); }, [load]);
  useEffect(()=>{if(!benefitProfileId){setBenefitEntitlements([]);return}void request<BenefitEntitlement[]>(`/api/leave-benefit-profiles/${benefitProfileId}/entitlements`).then(setBenefitEntitlements).catch(issue=>setError(issue instanceof Error?issue.message:'Could not load profile entitlements.'))},[benefitProfileId,request]);

  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const typeById = useMemo(() => new Map(types.map(type => [type.id, type])), [types]);
  const currentBenefitsByEmployee = useMemo(()=>new Map(benefitAssignments.filter(a=>a.effective_from<=localDateValue(new Date())&&(!a.effective_to||a.effective_to>=localDateValue(new Date()))).map(a=>[a.employee_id,a])),[benefitAssignments]);
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

  async function createLeaveType(event: FormEvent) {
    event.preventDefault(); setBusy('type'); setError(''); setNotice('');
    try {
      const created = await request<LeaveType>('/api/leave-types', { method: 'POST', body: JSON.stringify({ code: typeCode.trim().toUpperCase(), name: typeName.trim(), paid: typePaid, annualEntitlement: annualEntitlement ? Number(annualEntitlement) : null, hireProrationEnabled, convertible, carryOverMax: carryOverMax ? Number(carryOverMax) : null }) });
      setTypes(current => [...current, created].sort((a, b) => a.name.localeCompare(b.name))); setLeaveTypeId(current => current || created.id);
      setTypeCode(''); setTypeName(''); setAnnualEntitlement(''); setHireProrationEnabled(true); setConvertible(false); setCarryOverMax('');
      setNotice(`${created.name} leave policy created.`);
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not create the leave policy.'); }
    finally { setBusy(''); }
  }

  async function toggleHireProration(type: LeaveType) {
    setBusy(type.id); setError(''); setNotice('');
    try {
      const updated = await request<LeaveType>(`/api/leave-types/${type.id}`, { method: 'PUT', body: JSON.stringify({ hireProrationEnabled: !type.hireProrationEnabled }) });
      setTypes(current => current.map(item => item.id === type.id ? updated : item));
      setNotice(`${type.name} hire-date proration ${updated.hireProrationEnabled ? 'enabled' : 'disabled'}.`);
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not update the leave policy.'); }
    finally { setBusy(''); }
  }

  async function createBenefitProfile(event:FormEvent){event.preventDefault();setBusy('benefit-profile');setError('');setNotice('');try{const created=await request<BenefitProfile>('/api/leave-benefit-profiles',{method:'POST',body:JSON.stringify({code:benefitCode,name:benefitName,employeeClass:benefitClass,description:benefitDescription})});setBenefitProfiles(rows=>[...rows,created]);setBenefitProfileId(created.id);setAssignProfileId(created.id);setBenefitCode('');setBenefitName('');setBenefitDescription('');setNotice(`${created.name} profile created. Add its leave entitlements before assigning it.`)}catch(issue){setError(issue instanceof Error?issue.message:'Could not create benefit profile.')}finally{setBusy('')}}
  async function saveBenefitEntitlement(event:FormEvent){event.preventDefault();if(!benefitProfileId)return;setBusy('benefit-entitlement');setError('');setNotice('');try{await request(`/api/leave-benefit-profiles/${benefitProfileId}/entitlements`,{method:'PUT',body:JSON.stringify({leaveTypeId:benefitLeaveTypeId,annualEntitlement:Number(benefitDays),grantBasis:benefitGrantBasis,paid:benefitPaid,hireProrationEnabled:benefitGrantBasis==='EVENT'?false:benefitProrate,convertible:benefitGrantBasis==='EVENT'?false:benefitConvertible,carryOverMax:benefitGrantBasis==='EVENT'?null:(benefitCarryMax?Number(benefitCarryMax):null),effectiveFrom:benefitEffectiveFrom,effectiveTo:null})});setBenefitEntitlements(await request<BenefitEntitlement[]>(`/api/leave-benefit-profiles/${benefitProfileId}/entitlements`));setNotice('Effective-dated leave entitlement saved to this benefit profile.')}catch(issue){setError(issue instanceof Error?issue.message:'Could not save entitlement.')}finally{setBusy('')}}
  async function assignBenefitProfile(event:FormEvent){event.preventDefault();setBusy('benefit-assignment');setError('');setNotice('');try{await request('/api/leave-benefit-profiles/assignments',{method:'POST',body:JSON.stringify({employeeId:assignEmployeeId,profileId:assignProfileId,effectiveFrom:assignDate,reason:assignReason})});setAssignReason('');setNotice('Benefit profile assigned. Effective-date prorated leave credits were added to the ledger.');await load()}catch(issue){setError(issue instanceof Error?issue.message:'Could not assign this benefit profile.')}finally{setBusy('')}}

  async function fileEventClaim(event:FormEvent){event.preventDefault();setBusy('event-claim');setError('');setNotice('');try{await request('/api/leave-benefit-claims',{method:'POST',body:JSON.stringify({employeeId:claimEmployeeId,leaveTypeId:claimTypeId,eventDate:claimEventDate,eventReference:claimReference,requestedDays:Number(claimDays),evidenceReference:claimEvidence,reason:claimReason||null})});setNotice('Event-based leave claim submitted for independent eligibility review.');setClaimReference('');setClaimDays('');setClaimEvidence('');setClaimReason('');await load()}catch(issue){setError(issue instanceof Error?issue.message:'Could not submit event-based leave claim.')}finally{setBusy('')}}
  async function decideEventClaim(item:EventClaim,decision:'APPROVED'|'REJECTED'){setBusy(item.id);setError('');setNotice('');try{await request(`/api/leave-benefit-claims/${item.id}/decision`,{method:'POST',body:JSON.stringify({decision,eligibilityVerified:decision==='APPROVED'&&!!eligibilityChecks[item.id]})});setNotice(`Event-based claim ${decision.toLowerCase()}.`);await load()}catch(issue){setError(issue instanceof Error?issue.message:'Could not decide event-based leave claim.')}finally{setBusy('')}}

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
    <div className="timekeeping-tabs" role="tablist" aria-label="Leave sections"><button type="button" role="tab" aria-selected={tab === 'requests'} className={tab === 'requests' ? 'active' : ''} onClick={() => setTab('requests')}>Requests{pendingCount > 0 && <span>{pendingCount}</span>}</button><button type="button" role="tab" aria-selected={tab === 'claims'} className={tab === 'claims' ? 'active' : ''} onClick={() => setTab('claims')}>Event claims{eventClaims.filter(c=>c.status==='PENDING').length>0&&<span>{eventClaims.filter(c=>c.status==='PENDING').length}</span>}</button><button type="button" role="tab" aria-selected={tab === 'balances'} className={tab === 'balances' ? 'active' : ''} onClick={() => setTab('balances')}>Balances</button><button type="button" role="tab" aria-selected={tab === 'types'} className={tab === 'types' ? 'active' : ''} onClick={() => setTab('types')}>Leave types</button><button type="button" role="tab" aria-selected={tab === 'benefits'} className={tab === 'benefits' ? 'active' : ''} onClick={() => setTab('benefits')}>Benefit profiles</button></div>
    {tab === 'requests' && <>
      <WorkspaceModal title="File leave request" triggerLabel="＋ File leave request"><form className="timekeeping-manual-form leave-file-form" onSubmit={event => void fileLeave(event)}>
        <div><p className="eyebrow">NEW REQUEST</p><h2>File employee leave</h2><p>Requests are pending until an authorized HR user approves or rejects them.</p></div>
        <label><span>Employee</span><select value={employeeId} onChange={event => setEmployeeId(event.currentTarget.value)} required><option value="">Select employee</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label>
        <label><span>Leave type</span><select value={leaveTypeId} onChange={event => setLeaveTypeId(event.currentTarget.value)} required><option value="">Select leave type</option>{types.map(type => <option key={type.id} value={type.id}>{type.name} · {type.code}</option>)}</select></label>
        <label><span>From</span><input type="date" value={dateFrom} onChange={event => { setDateFrom(event.currentTarget.value); if (dateTo < event.currentTarget.value) setDateTo(event.currentTarget.value); }} required /></label>
        <label><span>To</span><input type="date" value={dateTo} min={dateFrom} onChange={event => setDateTo(event.currentTarget.value)} required /></label>
        <label><span>Days</span><input type="number" min="0.5" step="0.5" value={days} onChange={event => setDays(event.currentTarget.value)} required /></label>
        <label className="leave-half-day"><input type="checkbox" checked={halfDay} onChange={event => { setHalfDay(event.currentTarget.checked); if (event.currentTarget.checked) setDays('0.5'); }} /><span>Half day</span></label>
        <label className="timekeeping-remarks"><span>Reason</span><input value={reason} onChange={event => setReason(event.currentTarget.value)} maxLength={1000} placeholder="Reason for leave" required /></label>
        <button type="submit" className="security-submit" disabled={busy === 'file' || !employeeId || !leaveTypeId || !types.length}>{busy === 'file' ? 'Filing…' : 'File leave request'}</button>
      </form></WorkspaceModal>
      <div className="leave-list-toolbar"><label><span>Show</span><select value={statusFilter} onChange={event => setStatusFilter(event.currentTarget.value)}><option value="ALL">All requests</option><option value="PENDING">Pending</option><option value="APPROVED">Approved</option><option value="REJECTED">Rejected</option><option value="CANCELLED">Cancelled</option></select></label><span>{filteredRequests.length} request{filteredRequests.length === 1 ? '' : 's'}</span></div>
      <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>LEAVE TYPE</th><th>DATES</th><th>DAYS</th><th>REASON</th><th>STATUS</th><th></th></tr></thead><tbody>{loading ? <tr><td colSpan={7} className="timekeeping-empty">Loading leave requests…</td></tr> : filteredRequests.length ? filteredRequests.map(item => <tr key={item.id}><td><strong>{employeeLabel(item.employeeId)}</strong></td><td>{typeById.get(item.leaveTypeId)?.name || 'Unknown type'}</td><td>{item.dateFrom} – {item.dateTo}{item.halfDay && <small>Half day</small>}</td><td>{Number(item.days).toLocaleString()}</td><td className="timekeeping-note-cell">{item.reason || '—'}</td><td><span className={`timekeeping-status ${item.status === 'PENDING' ? 'warning' : item.status === 'APPROVED' ? 'open' : 'locked'}`}>{item.status}</span></td><td><div className="timekeeping-row-actions">{item.status === 'PENDING' && <><button type="button" className="session-revoke" onClick={() => void decide(item, 'approve')} disabled={busy === item.id}>{busy === item.id ? 'Saving…' : 'Approve'}</button><button type="button" className="timekeeping-reject" onClick={() => void decide(item, 'reject')} disabled={busy === item.id}>Reject</button></>}{item.status === 'APPROVED' && <button type="button" className="timekeeping-reject" onClick={() => void decide(item, 'cancel')} disabled={busy === item.id}>Cancel</button>}</div></td></tr>) : <tr><td colSpan={7} className="timekeeping-empty">No leave requests match this status.</td></tr>}</tbody></table></div>
    </>}
    {tab === 'claims' && <>
      <WorkspaceModal title="Submit benefit claim" triggerLabel="＋ Submit benefit claim"><form className="timekeeping-manual-form leave-file-form" onSubmit={event=>void fileEventClaim(event)}><div><p className="eyebrow">EVENT-BASED STATUTORY LEAVE</p><h2>Submit an eligibility claim</h2><p>HR records the qualifying event and evidence. A different approver verifies eligibility before the leave days enter the employee ledger.</p></div><label><span>Employee</span><select value={claimEmployeeId} onChange={e=>setClaimEmployeeId(e.currentTarget.value)} required><option value="">Select employee</option>{employees.map(e=><option key={e.id} value={e.id}>{e.employeeNo} · {e.firstName} {e.lastName}</option>)}</select></label><label><span>Leave benefit</span><select value={claimTypeId} onChange={e=>setClaimTypeId(e.currentTarget.value)} required><option value="">Select event-based leave</option>{types.map(t=><option key={t.id} value={t.id}>{t.name} · {t.code}</option>)}</select></label><label><span>Event date</span><input type="date" value={claimEventDate} onChange={e=>setClaimEventDate(e.currentTarget.value)} required /></label><label><span>Event reference</span><input value={claimReference} onChange={e=>setClaimReference(e.currentTarget.value)} maxLength={120} required placeholder="Unique claim / event reference" /></label><label><span>Days requested</span><input type="number" min="0.25" step="0.25" value={claimDays} onChange={e=>setClaimDays(e.currentTarget.value)} required /></label><label><span>Evidence reference</span><input value={claimEvidence} onChange={e=>setClaimEvidence(e.currentTarget.value)} maxLength={255} required placeholder="Document ID or secure document reference" /></label><label className="timekeeping-remarks"><span>Reason / notes</span><input value={claimReason} onChange={e=>setClaimReason(e.currentTarget.value)} maxLength={1000} /></label><button type="submit" className="security-submit" disabled={busy==='event-claim'}>{busy==='event-claim'?'Submitting…':'Submit event claim'}</button></form></WorkspaceModal>
      <section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">CLAIM REVIEW</p><h2>Statutory leave event claims</h2><p>Approved days are credited once to the employee’s leave ledger. Eligibility verification is recorded with the approval.</p></div></div><div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE / BENEFIT</th><th>EVENT</th><th>DAYS</th><th>EVIDENCE</th><th>STATUS</th><th>PAY / REIMBURSEMENT</th><th>DECISION</th></tr></thead><tbody>{eventClaims.map(c=><tr key={c.id}><td><strong>{c.employee_no} · {c.first_name} {c.last_name}</strong><small>{c.leave_name} · {c.leave_code}</small></td><td>{c.event_date}<small>{c.event_reference}</small></td><td>{c.requested_days}</td><td>{c.evidence_reference}</td><td><span className={`timekeeping-status ${c.status==='PENDING'?'warning':c.status==='APPROVED'?'open':'locked'}`}>{c.status}</span></td><td>{c.status==='APPROVED'?`Salary differential ${Number(c.employer_salary_differential).toFixed(2)} · Agency ${Number(c.agency_reimbursement_due).toFixed(2)} (${c.reimbursement_status})`:'—'}</td><td>{c.status==='PENDING'?<><label className="leave-half-day"><input type="checkbox" checked={!!eligibilityChecks[c.id]} onChange={e=>setEligibilityChecks(prev=>({...prev,[c.id]:e.currentTarget.checked}))}/><span>Eligibility verified</span></label><div className="timekeeping-row-actions"><button type="button" className="security-submit" disabled={!eligibilityChecks[c.id]||busy===c.id} onClick={()=>void decideEventClaim(c,'APPROVED')}>Approve</button><button type="button" className="timekeeping-reject" disabled={busy===c.id} onClick={()=>void decideEventClaim(c,'REJECTED')}>Reject</button></div></>:'—'}</td></tr>)}{!eventClaims.length&&<tr><td colSpan={7} className="timekeeping-empty">No event-based leave claims have been filed.</td></tr>}</tbody></table></div></section>
    </>}
    {tab === 'balances' && <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th>{types.map(type => <th key={type.id}>{type.name.toUpperCase()}<small>{type.annualEntitlement == null ? 'LEDGER BALANCE' : 'PROFILE MAY OVERRIDE ANNUAL DAYS'}</small></th>)}</tr></thead><tbody>{loading ? <tr><td colSpan={Math.max(types.length + 1, 2)} className="timekeeping-empty">Loading leave balances…</td></tr> : employees.length && types.length ? employees.map(employee => {const profile=currentBenefitsByEmployee.get(employee.id);return <tr key={employee.id}><td><strong>{employee.firstName} {employee.lastName}</strong><small>{employee.employeeNo} · {profile?`${profile.profile_name} (${profile.employee_class})`:'Default leave policy'}</small></td>{types.map(type => <td key={type.id}>{(balances.get(`${employee.id}:${type.id}`) || 0).toLocaleString()} days</td>)}</tr>}) : <tr><td colSpan={Math.max(types.length + 1, 2)} className="timekeeping-empty">Add employees and leave types to see balances.</td></tr>}</tbody></table></div>}
    {tab === 'types' && <><WorkspaceModal title="Create leave policy" triggerLabel="＋ Create leave policy"><form className="timekeeping-manual-form leave-file-form" onSubmit={event => void createLeaveType(event)}><div><p className="eyebrow">LEAVE BENEFITS</p><h2>Create leave policy</h2><p>Set each employee’s annual allowance and whether the first hire-year entitlement is prorated to their hire date.</p></div><label><span>Policy code</span><input value={typeCode} onChange={event => setTypeCode(event.currentTarget.value.toUpperCase())} required maxLength={40} pattern="[A-Za-z0-9_-]+" placeholder="ANNUAL" /></label><label><span>Policy name</span><input value={typeName} onChange={event => setTypeName(event.currentTarget.value)} required maxLength={120} placeholder="Annual leave" /></label><label><span>Annual entitlement (days)</span><input type="number" min="0" step="0.25" value={annualEntitlement} onChange={event => setAnnualEntitlement(event.currentTarget.value)} placeholder="Leave blank for ledger-managed balance" /></label><label className="leave-half-day"><input type="checkbox" checked={typePaid} onChange={event => setTypePaid(event.currentTarget.checked)} /><span>Paid leave</span></label><label className="leave-half-day"><input type="checkbox" checked={hireProrationEnabled} onChange={event => setHireProrationEnabled(event.currentTarget.checked)} /><span>Prorate hire-year balance</span></label><label className="leave-half-day"><input type="checkbox" checked={convertible} onChange={event => setConvertible(event.currentTarget.checked)} /><span>Convert unused balance on final pay</span></label>{convertible && <label><span>Maximum carry-over / conversion days (optional)</span><input type="number" min="0" step="0.25" value={carryOverMax} onChange={event => setCarryOverMax(event.currentTarget.value)} placeholder="No limit" /></label>}<button type="submit" className="security-submit" disabled={busy === 'type' || loading}>{busy === 'type' ? 'Saving…' : 'Create leave policy'}</button></form></WorkspaceModal><div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>POLICY</th><th>ANNUAL ENTITLEMENT</th><th>PAID</th><th>HIRE-YEAR CREDIT</th><th></th></tr></thead><tbody>{types.map(type => <tr key={type.id}><td><strong>{type.name}</strong><small>{type.code}{type.convertible ? ` · Final pay conversion${type.carryOverMax == null ? '' : ` capped at ${type.carryOverMax} days`}` : ''}</small></td><td>{type.annualEntitlement == null ? 'Ledger managed' : `${type.annualEntitlement} days`}</td><td>{type.paid ? 'Yes' : 'No'}</td><td>{type.annualEntitlement == null ? 'Not applicable' : type.hireProrationEnabled ? 'Prorated from hire date' : 'Not automatic'}</td><td>{type.annualEntitlement != null && <button type="button" className="session-revoke" onClick={() => void toggleHireProration(type)} disabled={busy === type.id}>{busy === type.id ? 'Saving…' : type.hireProrationEnabled ? 'Turn off proration' : 'Enable proration'}</button>}</td></tr>)}{!types.length && <tr><td colSpan={5} className="timekeeping-empty">No leave policies configured.</td></tr>}</tbody></table></div></>}
    {tab === 'benefits' && <>
      <WorkspaceModal title="Create leave benefit profile" triggerLabel="＋ Create leave benefit profile"><form className="timekeeping-manual-form leave-file-form" onSubmit={event=>void createBenefitProfile(event)}><div><p className="eyebrow">EMPLOYEE BENEFIT CLASS</p><h2>Create a leave benefit profile</h2><p>Keep benefit eligibility separate from employment type. Examples: Local, Expatriate, Executive.</p></div><label><span>Profile code</span><input value={benefitCode} onChange={e=>setBenefitCode(e.currentTarget.value.toUpperCase())} maxLength={40} pattern="[A-Za-z0-9_-]{2,40}" required placeholder="LOCAL" /></label><label><span>Profile name</span><input value={benefitName} onChange={e=>setBenefitName(e.currentTarget.value)} maxLength={120} required placeholder="Local employee benefits" /></label><label><span>Employee class</span><select value={benefitClass} onChange={e=>setBenefitClass(e.currentTarget.value)}><option>LOCAL</option><option>EXPATRIATE</option><option>GENERAL</option><option>CUSTOM</option></select></label><label><span>Description</span><input value={benefitDescription} onChange={e=>setBenefitDescription(e.currentTarget.value)} maxLength={1000} placeholder="Eligibility notes" /></label><button type="submit" className="security-submit" disabled={busy==='benefit-profile'}>{busy==='benefit-profile'?'Saving…':'Create profile'}</button></form></WorkspaceModal>
      <section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">PROFILE RULES</p><h2>Configure leave entitlements</h2></div></div><div className="tax-form-grid"><label className="payroll-field">Benefit profile<select value={benefitProfileId} onChange={e=>setBenefitProfileId(e.currentTarget.value)} required><option value="">Select profile</option>{benefitProfiles.map(p=><option key={p.id} value={p.id}>{p.name} · {p.employee_class}</option>)}</select></label></div>{benefitProfileId&&<WorkspaceModal title="Edit leave entitlement" triggerLabel="＋ Edit leave entitlement"><form className="tax-form-grid" onSubmit={event=>void saveBenefitEntitlement(event)}><label className="payroll-field">Leave type<select value={benefitLeaveTypeId} onChange={e=>setBenefitLeaveTypeId(e.currentTarget.value)} required><option value="">Select leave type</option>{types.map(t=><option key={t.id} value={t.id}>{t.name}</option>)}</select></label><label className="payroll-field">Grant basis<select value={benefitGrantBasis} onChange={e=>{const basis=e.currentTarget.value;setBenefitGrantBasis(basis);if(basis==='EVENT'){setBenefitProrate(false);setBenefitConvertible(false);setBenefitCarryMax('')}}}><option value="ANNUAL">Annual recurring</option><option value="EVENT">One-time event</option></select></label><label className="payroll-field">Maximum days per {benefitGrantBasis==='EVENT'?'event':'year'}<input type="number" min="0" step="0.25" value={benefitDays} onChange={e=>setBenefitDays(e.currentTarget.value)} required /></label><label className="payroll-field">Effective from<input type="date" value={benefitEffectiveFrom} onChange={e=>setBenefitEffectiveFrom(e.currentTarget.value)} required /></label><label className="payroll-field">Carryover cap<input type="number" disabled={benefitGrantBasis==='EVENT'} min="0" step="0.25" value={benefitCarryMax} onChange={e=>setBenefitCarryMax(e.currentTarget.value)} placeholder="No cap" /></label><label className="leave-half-day"><input type="checkbox" checked={benefitPaid} onChange={e=>setBenefitPaid(e.currentTarget.checked)}/><span>Paid leave</span></label><label className="leave-half-day"><input type="checkbox" checked={benefitProrate} disabled={benefitGrantBasis==='EVENT'} onChange={e=>setBenefitProrate(e.currentTarget.checked)}/><span>Prorate assignment year</span></label><label className="leave-half-day"><input type="checkbox" checked={benefitConvertible} disabled={benefitGrantBasis==='EVENT'} onChange={e=>setBenefitConvertible(e.currentTarget.checked)}/><span>Convert unused balance at final pay</span></label><button className="security-submit" disabled={busy==='benefit-entitlement'||!benefitLeaveTypeId}>{busy==='benefit-entitlement'?'Saving…':'Save entitlement'}</button></form></WorkspaceModal>}{benefitEntitlements.length>0&&<div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>LEAVE TYPE</th><th>GRANT BASIS / LIMIT</th><th>PAID</th><th>PRORATION</th><th>FINAL PAY</th><th>EFFECTIVE</th></tr></thead><tbody>{benefitEntitlements.map(e=><tr key={e.id}><td>{e.leave_name}</td><td>{e.grant_basis==='EVENT'?`${e.annual_entitlement} days per event`:`${e.annual_entitlement} days per year`}</td><td>{e.paid?'Yes':'No'}</td><td>{e.hire_proration_enabled?'Prorated':'Full grant'}</td><td>{e.convertible?`Convertible${e.carry_over_max==null?'':` · cap ${e.carry_over_max}`}`:'Not convertible'}</td><td>{e.effective_from}{e.effective_to?` – ${e.effective_to}`:' onward'}</td></tr>)}</tbody></table></div>}</section>
      <WorkspaceModal title="Assign leave profile" triggerLabel="＋ Assign leave profile"><form className="timekeeping-manual-form leave-file-form" onSubmit={event=>void assignBenefitProfile(event)}><div><p className="eyebrow">EMPLOYEE ASSIGNMENT</p><h2>Assign a benefit profile</h2><p>Changing profile closes the prior assignment and starts the selected rules on the effective date.</p></div><label><span>Employee</span><select value={assignEmployeeId} onChange={e=>setAssignEmployeeId(e.currentTarget.value)} required><option value="">Select employee</option>{employees.map(e=><option key={e.id} value={e.id}>{e.employeeNo} · {e.firstName} {e.lastName}</option>)}</select></label><label><span>Benefit profile</span><select value={assignProfileId} onChange={e=>setAssignProfileId(e.currentTarget.value)} required><option value="">Select profile</option>{benefitProfiles.map(p=><option key={p.id} value={p.id}>{p.name} · {p.employee_class}</option>)}</select></label><label><span>Effective from</span><input type="date" value={assignDate} onChange={e=>setAssignDate(e.currentTarget.value)} required /></label><label><span>Reason</span><input value={assignReason} onChange={e=>setAssignReason(e.currentTarget.value)} maxLength={1000} placeholder="New hire or benefit change" /></label><button type="submit" className="security-submit" disabled={busy==='benefit-assignment'||!benefitProfiles.length||!assignEmployeeId}>{busy==='benefit-assignment'?'Assigning…':'Assign profile'}</button></form></WorkspaceModal>
      <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>PROFILE</th><th>CLASS</th><th>EFFECTIVE</th><th>TO</th><th>REASON</th></tr></thead><tbody>{benefitAssignments.map(a=><tr key={a.id}><td>{a.employee_no} · {a.first_name} {a.last_name}</td><td>{a.profile_name}</td><td>{a.employee_class}</td><td>{a.effective_from}</td><td>{a.effective_to||'Current'}</td><td>{a.reason||'—'}</td></tr>)}{!benefitAssignments.length&&<tr><td colSpan={6} className="timekeeping-empty">No employee benefit profiles assigned.</td></tr>}</tbody></table></div>
    </>}
    <p className="timekeeping-footnote">Balances are calculated by summing ledger entries. Approving a paid leave request posts its usage to the ledger; cancellation posts a reversal.</p>
  </section>;
}
