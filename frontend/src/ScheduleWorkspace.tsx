import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';

type Page<T> = { content: T[] };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; status: string };
type WorkSchedule = { id: string; code: string; name: string; startTime: string; endTime: string; breakMinutes: number; graceMinutes: number; restDays: string };
type EmployeeSchedule = { id: string; employeeId: string; scheduleId: string; effectiveFrom: string; effectiveTo: string | null };
type Holiday = { id: string; holidayDate: string; name: string; holidayType: string; workLocationId: string | null };
const holidayKinds = ['REGULAR', 'SPECIAL_NON_WORKING', 'SPECIAL_WORKING'];

export function ScheduleWorkspace({ token }: { token: string }) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [schedules, setSchedules] = useState<WorkSchedule[]>([]);
  const [assignments, setAssignments] = useState<EmployeeSchedule[]>([]);
  const [holidays, setHolidays] = useState<Holiday[]>([]);
  const [tab, setTab] = useState<'schedules' | 'assignments' | 'holidays'>('schedules');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [code, setCode] = useState('DAY');
  const [name, setName] = useState('Day shift');
  const [startTime, setStartTime] = useState('09:00');
  const [endTime, setEndTime] = useState('18:00');
  const [breakMinutes, setBreakMinutes] = useState('60');
  const [graceMinutes, setGraceMinutes] = useState('10');
  const [restDays, setRestDays] = useState('SAT,SUN');
  const [assignmentEmployeeId, setAssignmentEmployeeId] = useState('');
  const [assignmentScheduleId, setAssignmentScheduleId] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState(() => new Date().toISOString().slice(0, 10));
  const [holidayDate, setHolidayDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [holidayName, setHolidayName] = useState('');
  const [holidayType, setHolidayType] = useState('REGULAR');

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
      const [employeePage, schedulePage, assignmentPage, holidayPage] = await Promise.all([
        request<Page<Employee>>('/api/employees?size=500&sort=employeeNo,asc'),
        request<Page<WorkSchedule>>('/api/work-schedules?size=500&sort=name,asc'),
        request<Page<EmployeeSchedule>>('/api/employee-schedules?size=1000&sort=effectiveFrom,desc'),
        request<Page<Holiday>>('/api/holidays?size=500&sort=holidayDate,asc'),
      ]);
      const activeEmployees = employeePage.content.filter(employee => employee.status !== 'SEPARATED');
      setEmployees(activeEmployees); setSchedules(schedulePage.content); setAssignments(assignmentPage.content); setHolidays(holidayPage.content);
      setAssignmentEmployeeId(current => current || activeEmployees[0]?.id || '');
      setAssignmentScheduleId(current => current || schedulePage.content[0]?.id || '');
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not load schedule setup.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => { void load(); }, [load]);
  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const scheduleById = useMemo(() => new Map(schedules.map(schedule => [schedule.id, schedule])), [schedules]);
  const employeeLabel = (id: string) => { const employee = employeeById.get(id); return employee ? `${employee.firstName} ${employee.lastName}` : 'Unknown employee'; };
  const activeAssignments = assignments.filter(assignment => !assignment.effectiveTo || assignment.effectiveTo >= new Date().toISOString().slice(0, 10)).length;

  async function createSchedule(event: FormEvent) {
    event.preventDefault(); setBusy('schedule'); setError(''); setNotice('');
    try {
      const created = await request<WorkSchedule>('/api/work-schedules', { method: 'POST', body: JSON.stringify({ code: code.trim().toUpperCase(), name: name.trim(), startTime: `${startTime}:00`, endTime: `${endTime}:00`, breakMinutes: Number(breakMinutes), graceMinutes: Number(graceMinutes), restDays: restDays.split(',').map(day => day.trim().toUpperCase()).filter(Boolean).join(',') }) });
      setSchedules(current => [...current, created].sort((a, b) => a.name.localeCompare(b.name)));
      setAssignmentScheduleId(current => current || created.id);
      setNotice(`Work schedule ${created.name} created.`);
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not create the work schedule.'); }
    finally { setBusy(''); }
  }

  async function assignSchedule(event: FormEvent) {
    event.preventDefault(); setBusy('assignment'); setError(''); setNotice('');
    try {
      const created = await request<EmployeeSchedule>('/api/employee-schedules', { method: 'POST', body: JSON.stringify({ employeeId: assignmentEmployeeId, scheduleId: assignmentScheduleId, effectiveFrom, effectiveTo: null }) });
      setAssignments(current => [created, ...current]); setNotice(`${scheduleById.get(assignmentScheduleId)?.name || 'Schedule'} assigned to ${employeeLabel(assignmentEmployeeId)}.`);
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not assign this schedule.'); }
    finally { setBusy(''); }
  }

  async function endAssignment(item: EmployeeSchedule) {
    const endDate = window.prompt(`End ${employeeLabel(item.employeeId)}’s ${scheduleById.get(item.scheduleId)?.name || 'schedule'} assignment on which date?`, new Date().toISOString().slice(0, 10));
    if (!endDate) return;
    setBusy(item.id); setError(''); setNotice('');
    try {
      const updated = await request<EmployeeSchedule>(`/api/employee-schedules/${item.id}`, { method: 'PUT', body: JSON.stringify({ effectiveTo: endDate }) });
      setAssignments(current => current.map(assignment => assignment.id === item.id ? updated : assignment)); setNotice('Schedule assignment ended.');
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not end the schedule assignment.'); }
    finally { setBusy(''); }
  }

  async function createHoliday(event: FormEvent) {
    event.preventDefault(); setBusy('holiday'); setError(''); setNotice('');
    try {
      const created = await request<Holiday>('/api/holidays', { method: 'POST', body: JSON.stringify({ holidayDate, name: holidayName.trim(), holidayType, workLocationId: null }) });
      setHolidays(current => [...current, created].sort((a, b) => a.holidayDate.localeCompare(b.holidayDate)));
      setHolidayName(''); setNotice(`${created.name} added to the holiday calendar.`);
    } catch (issue) { setError(issue instanceof Error ? issue.message : 'Could not add the holiday.'); }
    finally { setBusy(''); }
  }

  return <section className="timekeeping-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">TIMEKEEPING</p><h1>Schedules &amp; shifts</h1><p>Configure work schedules, assign employees by effective date, and maintain holidays used in DTR and payroll.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>↻ Refresh</button></header>
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics"><article><span>Work schedules</span><strong>{schedules.length}</strong><small>Configured shifts</small></article><article><span>Assignments</span><strong>{activeAssignments}</strong><small>Current or future assignments</small></article><article><span>Employees</span><strong>{employees.length}</strong><small>Eligible for assignment</small></article><article><span>Holidays</span><strong>{holidays.length}</strong><small>Calendar records loaded</small></article></div>
    <div className="timekeeping-tabs" role="tablist" aria-label="Schedule setup"><button type="button" role="tab" aria-selected={tab === 'schedules'} className={tab === 'schedules' ? 'active' : ''} onClick={() => setTab('schedules')}>Work schedules</button><button type="button" role="tab" aria-selected={tab === 'assignments'} className={tab === 'assignments' ? 'active' : ''} onClick={() => setTab('assignments')}>Employee assignments</button><button type="button" role="tab" aria-selected={tab === 'holidays'} className={tab === 'holidays' ? 'active' : ''} onClick={() => setTab('holidays')}>Holiday calendar</button></div>
    {tab === 'schedules' && <>
      <form className="timekeeping-manual-form schedule-create-form" onSubmit={event => void createSchedule(event)}><div><p className="eyebrow">SHIFT SETUP</p><h2>Create work schedule</h2><p>Times use the workspace timezone configured for HRM.</p></div>
        <label><span>Code</span><input value={code} onChange={event => setCode(event.currentTarget.value.toUpperCase())} maxLength={40} pattern="[A-Za-z0-9_-]+" required /></label><label><span>Name</span><input value={name} onChange={event => setName(event.currentTarget.value)} maxLength={160} required /></label>
        <label><span>Start time</span><input type="time" value={startTime} onChange={event => setStartTime(event.currentTarget.value)} required /></label><label><span>End time</span><input type="time" value={endTime} onChange={event => setEndTime(event.currentTarget.value)} required /></label>
        <label><span>Break (minutes)</span><input type="number" min="0" max="600" value={breakMinutes} onChange={event => setBreakMinutes(event.currentTarget.value)} required /></label><label><span>Grace (minutes)</span><input type="number" min="0" max="240" value={graceMinutes} onChange={event => setGraceMinutes(event.currentTarget.value)} required /></label>
        <label className="timekeeping-remarks"><span>Rest days (comma separated)</span><input value={restDays} onChange={event => setRestDays(event.currentTarget.value.toUpperCase())} placeholder="SAT,SUN" /></label><button type="submit" className="security-submit" disabled={busy === 'schedule'}>{busy === 'schedule' ? 'Saving…' : 'Create schedule'}</button>
      </form>
      <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>CODE</th><th>SCHEDULE</th><th>HOURS</th><th>BREAK</th><th>GRACE</th><th>REST DAYS</th></tr></thead><tbody>{loading ? <tr><td colSpan={6} className="timekeeping-empty">Loading work schedules…</td></tr> : schedules.length ? schedules.map(item => <tr key={item.id}><td>{item.code}</td><td><strong>{item.name}</strong></td><td>{item.startTime.slice(0, 5)}–{item.endTime.slice(0, 5)}</td><td>{item.breakMinutes} min</td><td>{item.graceMinutes} min</td><td>{item.restDays?.split(',').join(', ') || '—'}</td></tr>) : <tr><td colSpan={6} className="timekeeping-empty">No work schedules yet. Create one before assigning staff.</td></tr>}</tbody></table></div>
    </>}
    {tab === 'assignments' && <>
      <form className="timekeeping-manual-form" onSubmit={event => void assignSchedule(event)}><div><p className="eyebrow">EMPLOYEE SCHEDULE</p><h2>Assign a shift</h2><p>Assignments take effect on the selected date and drive DTR processing.</p></div><label><span>Employee</span><select value={assignmentEmployeeId} onChange={event => setAssignmentEmployeeId(event.currentTarget.value)} required><option value="">Select employee</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label><label><span>Work schedule</span><select value={assignmentScheduleId} onChange={event => setAssignmentScheduleId(event.currentTarget.value)} required><option value="">Select schedule</option>{schedules.map(schedule => <option key={schedule.id} value={schedule.id}>{schedule.code} · {schedule.name}</option>)}</select></label><label><span>Effective from</span><input type="date" value={effectiveFrom} onChange={event => setEffectiveFrom(event.currentTarget.value)} required /></label><button type="submit" className="security-submit" disabled={busy === 'assignment' || !schedules.length}>{busy === 'assignment' ? 'Assigning…' : 'Assign schedule'}</button></form>
      <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>SCHEDULE</th><th>EFFECTIVE FROM</th><th>EFFECTIVE TO</th><th>STATUS</th><th></th></tr></thead><tbody>{loading ? <tr><td colSpan={6} className="timekeeping-empty">Loading assignments…</td></tr> : assignments.length ? assignments.map(item => <tr key={item.id}><td><strong>{employeeLabel(item.employeeId)}</strong></td><td>{scheduleById.get(item.scheduleId)?.name || 'Unknown schedule'}</td><td>{item.effectiveFrom}</td><td>{item.effectiveTo || 'Ongoing'}</td><td><span className={`timekeeping-status ${!item.effectiveTo || item.effectiveTo >= new Date().toISOString().slice(0, 10) ? 'open' : 'locked'}`}>{!item.effectiveTo || item.effectiveTo >= new Date().toISOString().slice(0, 10) ? 'ACTIVE' : 'ENDED'}</span></td><td>{!item.effectiveTo && <button type="button" className="timekeeping-reject" onClick={() => void endAssignment(item)} disabled={busy === item.id}>{busy === item.id ? 'Saving…' : 'End assignment'}</button>}</td></tr>) : <tr><td colSpan={6} className="timekeeping-empty">No employee schedules assigned yet.</td></tr>}</tbody></table></div>
    </>}
    {tab === 'holidays' && <>
      <form className="timekeeping-manual-form" onSubmit={event => void createHoliday(event)}><div><p className="eyebrow">HOLIDAY CALENDAR</p><h2>Add a holiday</h2><p>Holiday types affect DTR day classification and premium pay.</p></div><label><span>Date</span><input type="date" value={holidayDate} onChange={event => setHolidayDate(event.currentTarget.value)} required /></label><label><span>Name</span><input value={holidayName} onChange={event => setHolidayName(event.currentTarget.value)} maxLength={160} required /></label><label><span>Type</span><select value={holidayType} onChange={event => setHolidayType(event.currentTarget.value)}>{holidayKinds.map(kind => <option value={kind} key={kind}>{kind.replaceAll('_', ' ')}</option>)}</select></label><button type="submit" className="security-submit" disabled={busy === 'holiday'}>{busy === 'holiday' ? 'Saving…' : 'Add holiday'}</button></form>
      <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>DATE</th><th>HOLIDAY</th><th>TYPE</th></tr></thead><tbody>{loading ? <tr><td colSpan={3} className="timekeeping-empty">Loading holiday calendar…</td></tr> : holidays.length ? holidays.map(item => <tr key={item.id}><td>{item.holidayDate}</td><td><strong>{item.name}</strong></td><td>{item.holidayType.replaceAll('_', ' ')}</td></tr>) : <tr><td colSpan={3} className="timekeeping-empty">No holidays configured. Add this year’s official holiday dates.</td></tr>}</tbody></table></div>
    </>}
    <p className="timekeeping-footnote">A work schedule defines shift hours, breaks, grace periods, and rest days. Keep holiday records current before processing DTR or payroll.</p>
  </section>;
}
