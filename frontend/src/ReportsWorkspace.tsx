import { useCallback, useEffect, useMemo, useState } from 'react';

type Page<T> = { content: T[] };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; status: string };
type Dtr = { employeeId: string; workDate: string; regularMinutes: number; lateMinutes: number; undertimeMinutes: number; absent: boolean; status: string };
type Leave = { employeeId: string; dateFrom: string; dateTo: string; days: number; status: string; leaveTypeId: string };
type Overtime = { employeeId: string; workDate: string; minutesRequested: number; approvedMinutes: number; status: string };
type Run = { id: string; payPeriodId: string; runType: string; status: string; employeeCount: number; totalGross: number; totalDeductions: number; totalNet: number; totalEmployerCost: number };
type Period = { id: string; startDate: string; endDate: string; payDate: string };
type ReportTab = 'workforce' | 'attendance' | 'leave' | 'overtime' | 'payroll';
const now = new Date();
const dateValue = (value: Date) => `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
const currency = (value: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP', maximumFractionDigits: 0 }).format(value || 0);
const minutes = (value: number) => `${Math.floor(value / 60)}h ${value % 60}m`;

export function ReportsWorkspace({ token, roles, onOpenSecurity }: { token: string; roles: string[]; onOpenSecurity: () => void }) {
  const canHr = roles.some(role => ['ROLE_TENANT_ADMIN', 'ROLE_HR_ADMIN'].includes(role));
  const canPayroll = roles.some(role => ['ROLE_TENANT_ADMIN', 'ROLE_PAYROLL_OFFICER'].includes(role));
  const [tab, setTab] = useState<ReportTab>('workforce');
  const [from, setFrom] = useState(() => dateValue(new Date(now.getFullYear(), 0, 1)));
  const [to, setTo] = useState(() => dateValue(now));
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [dtr, setDtr] = useState<Dtr[]>([]);
  const [leave, setLeave] = useState<Leave[]>([]);
  const [overtime, setOvertime] = useState<Overtime[]>([]);
  const [runs, setRuns] = useState<Run[]>([]);
  const [periods, setPeriods] = useState<Period[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [mfaVerified, setMfaVerified] = useState<boolean | null>(null);

  const request = useCallback(async <T,>(path: string): Promise<T> => {
    const response = await fetch(path, { headers: { Authorization: `Bearer ${token}` } });
    if (!response.ok) {
      let message = `Request failed (${response.status}).`;
      try { const body = await response.json() as { detail?: string; message?: string }; message = body.detail || body.message || message; } catch { /* use status */ }
      if (response.status === 401) message = 'Your sign-in session has expired. Sign in again.';
      if (response.status === 403) message = 'This report requires a role-authorized session with verified MFA.';
      throw new Error(message);
    }
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      if (tab === 'workforce' && (canHr || canPayroll)) {
        const page = await request<Page<Employee>>('/api/payroll/employees?size=1000&sort=employeeNo,asc'); setEmployees(page.content);
      } else if (tab === 'attendance' && canHr) {
        const page = await request<Page<Dtr>>('/api/dtr-days?size=5000&sort=workDate,desc'); setDtr(page.content);
      } else if (tab === 'leave' && canHr) {
        const page = await request<Page<Leave>>('/api/leave-requests?size=5000&sort=dateFrom,desc'); setLeave(page.content);
      } else if (tab === 'overtime' && canHr) {
        const page = await request<Page<Overtime>>('/api/overtime-requests?size=5000&sort=workDate,desc'); setOvertime(page.content);
      } else if (tab === 'payroll' && canPayroll) {
        const [runPage, periodPage] = await Promise.all([
          request<Page<Run>>('/api/payroll-runs?size=1000&sort=createdAt,desc'),
          request<Page<Period>>('/api/pay-periods?size=1000&sort=startDate,desc'),
        ]); setRuns(runPage.content); setPeriods(periodPage.content);
      }
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load this report.'); }
    finally { setLoading(false); }
  }, [canHr, canPayroll, request, tab]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    void request<{ enabled: boolean; tokenVerified: boolean }>('/api/v1/security/mfa').then(status => setMfaVerified(status.tokenVerified)).catch(() => setMfaVerified(false));
  }, [request]);

  const employeesById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const activeEmployeeCount = employees.filter(employee => !['TERMINATED', 'RETIRED', 'SEPARATED'].includes(employee.status)).length;
  const filteredDtr = dtr.filter(row => row.workDate >= from && row.workDate <= to);
  const filteredLeave = leave.filter(row => row.dateTo >= from && row.dateFrom <= to);
  const filteredOvertime = overtime.filter(row => row.workDate >= from && row.workDate <= to);
  const filteredRuns = runs.filter(run => { const period = periods.find(item => item.id === run.payPeriodId); return !!period && period.endDate >= from && period.startDate <= to; });
  const attendanceMetrics = { days: filteredDtr.length, lateMinutes: filteredDtr.reduce((sum, row) => sum + Number(row.lateMinutes || 0), 0), workedMinutes: filteredDtr.reduce((sum, row) => sum + Number(row.regularMinutes || 0), 0), absences: filteredDtr.filter(row => row.absent).length };
  const leaveMetrics = { requests: filteredLeave.length, pending: filteredLeave.filter(row => row.status === 'PENDING').length, approvedDays: filteredLeave.filter(row => row.status === 'APPROVED').reduce((sum, row) => sum + Number(row.days || 0), 0), rejected: filteredLeave.filter(row => row.status === 'REJECTED').length };
  const overtimeMetrics = { requests: filteredOvertime.length, pending: filteredOvertime.filter(row => row.status === 'PENDING').length, approvedMinutes: filteredOvertime.filter(row => row.status === 'APPROVED').reduce((sum, row) => sum + Number(row.approvedMinutes || 0), 0), rejected: filteredOvertime.filter(row => row.status === 'REJECTED').length };
  const payrollMetrics = { gross: filteredRuns.reduce((sum, row) => sum + Number(row.totalGross || 0), 0), deductions: filteredRuns.reduce((sum, row) => sum + Number(row.totalDeductions || 0), 0), net: filteredRuns.reduce((sum, row) => sum + Number(row.totalNet || 0), 0), paid: filteredRuns.filter(row => row.status === 'PAID').length };

  function exportCsv() {
    const csv = tab === 'workforce'
      ? [['Employee no.', 'Name', 'Status'], ...employees.map(item => [item.employeeNo, `${item.firstName} ${item.lastName}`, item.status])]
      : tab === 'attendance'
        ? [['Work date', 'Employee no.', 'Name', 'Regular minutes', 'Late minutes', 'Undertime minutes', 'Absent', 'Status'], ...filteredDtr.map(row => { const employee = employeesById.get(row.employeeId); return [row.workDate, employee?.employeeNo || row.employeeId, employee ? `${employee.firstName} ${employee.lastName}` : 'Employee', row.regularMinutes, row.lateMinutes, row.undertimeMinutes, row.absent, row.status]; })]
        : tab === 'leave'
          ? [['From', 'To', 'Employee no.', 'Name', 'Days', 'Status', 'Leave type ID'], ...filteredLeave.map(row => { const employee = employeesById.get(row.employeeId); return [row.dateFrom, row.dateTo, employee?.employeeNo || row.employeeId, employee ? `${employee.firstName} ${employee.lastName}` : 'Employee', row.days, row.status, row.leaveTypeId]; })]
          : tab === 'overtime'
            ? [['Date', 'Employee no.', 'Name', 'Requested minutes', 'Approved minutes', 'Status'], ...filteredOvertime.map(row => { const employee = employeesById.get(row.employeeId); return [row.workDate, employee?.employeeNo || row.employeeId, employee ? `${employee.firstName} ${employee.lastName}` : 'Employee', row.minutesRequested, row.approvedMinutes, row.status]; })]
            : [['Pay period', 'Run type', 'Status', 'Employees', 'Gross', 'Deductions', 'Net', 'Employer cost'], ...filteredRuns.map(row => { const period = periods.find(item => item.id === row.payPeriodId); return [period ? `${period.startDate} – ${period.endDate}` : row.payPeriodId, row.runType, row.status, row.employeeCount, row.totalGross, row.totalDeductions, row.totalNet, row.totalEmployerCost]; })];
    const content = csv.map(row => row.map(value => `"${String(value ?? '').replaceAll('"', '""')}"`).join(',')).join('\r\n');
    const blob = new Blob([content], { type: 'text/csv;charset=utf-8' }); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = `apex-hrm-${tab}-${from}-to-${to}.csv`; link.click(); URL.revokeObjectURL(url);
  }

  const tabs: { id: ReportTab; label: string; enabled: boolean }[] = [
    { id: 'workforce', label: 'Workforce', enabled: canHr || canPayroll },
    { id: 'attendance', label: 'Attendance', enabled: canHr },
    { id: 'leave', label: 'Leave', enabled: canHr },
    { id: 'overtime', label: 'Overtime', enabled: canHr },
    { id: 'payroll', label: 'Payroll', enabled: canPayroll },
  ];

  return <section className="timekeeping-workspace reports-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">INSIGHTS</p><h1>Reports &amp; analytics</h1><p>Review workforce, timekeeping, leave, overtime, and payroll summaries for your workspace.</p></div><div className="reports-heading-actions"><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button><button type="button" className="security-submit" onClick={exportCsv} disabled={loading}>Export CSV</button></div></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to view workspace reports</strong><small>Enable and verify MFA in account security, then sign in again for report access.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    {error && <div className="platform-alert" role="alert">{error}</div>}
    <div className="reports-range"><label>From<input type="date" value={from} max={to} onChange={event => setFrom(event.target.value)} /></label><label>To<input type="date" value={to} min={from} onChange={event => setTo(event.target.value)} /></label><span>Selected period: {from} to {to}</span></div>
    <div className="timekeeping-tabs reports-tabs" role="tablist" aria-label="Analytics report type">{tabs.filter(item => item.enabled).map(item => <button type="button" role="tab" key={item.id} aria-selected={tab === item.id} className={tab === item.id ? 'active' : ''} onClick={() => setTab(item.id)}>{item.label}</button>)}</div>
    {loading ? <section className="payroll-panel"><div className="timekeeping-empty">Loading report data…</div></section> : tab === 'workforce' ? <><div className="timekeeping-metrics payroll-metrics reports-metrics"><Metric label="TOTAL EMPLOYEES" value={employees.length} note="People in this workspace" /><Metric label="ACTIVE EMPLOYEES" value={activeEmployeeCount} note="Excludes separated records" /><Metric label="SEPARATED" value={employees.length - activeEmployeeCount} note="Inactive or separated" /><Metric label="EMPLOYEE STATUSES" value={new Set(employees.map(item => item.status)).size} note="Distinct status values" /></div><section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">WORKFORCE SUMMARY</p><h2>Headcount by status</h2></div><span>{employees.length} employees</span></div><BarBreakdown rows={countBy(employees.map(item => item.status))} total={employees.length} />{!employees.length && <div className="timekeeping-empty">No employee records found.</div>}</section></>
      : tab === 'attendance' ? <><div className="timekeeping-metrics payroll-metrics reports-metrics"><Metric label="DTR DAYS" value={attendanceMetrics.days} note="Processed records in range" /><Metric label="WORKED TIME" value={minutes(attendanceMetrics.workedMinutes)} note="Regular minutes recorded" /><Metric label="LATE TIME" value={minutes(attendanceMetrics.lateMinutes)} note="Late minutes recorded" /><Metric label="ABSENCES" value={attendanceMetrics.absences} note="DTR days marked absent" /></div><ReportTable title="Attendance by employee" rows={countBy(filteredDtr.map(row => row.employeeId))} names={employeesById} empty="No DTR days in this date range." />{filteredDtr.length > 0 && <section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">DAILY TIME RECORDS</p><h2>Attendance detail</h2></div><span>{filteredDtr.length} records</span></div><div className="timekeeping-table-wrap"><table className="timekeeping-table reports-detail-table"><thead><tr><th>DATE</th><th>EMPLOYEE</th><th>REGULAR</th><th>LATE</th><th>UNDERTIME</th><th>ABSENT</th><th>STATUS</th></tr></thead><tbody>{filteredDtr.slice(0,250).map((row,index) => <tr key={`${row.employeeId}-${row.workDate}-${index}`}><td>{row.workDate}</td><td>{employeeLabel(row.employeeId, employeesById)}</td><td>{minutes(row.regularMinutes)}</td><td>{minutes(row.lateMinutes)}</td><td>{minutes(row.undertimeMinutes)}</td><td>{row.absent ? 'Yes' : '—'}</td><td>{row.status}</td></tr>)}</tbody></table></div>{filteredDtr.length > 250 && <p className="payroll-help">Showing the first 250 rows. Export CSV for the full selected range.</p>}</section>}</>
      : tab === 'leave' ? <><div className="timekeeping-metrics payroll-metrics reports-metrics"><Metric label="LEAVE REQUESTS" value={leaveMetrics.requests} note="Overlapping selected period" /><Metric label="PENDING" value={leaveMetrics.pending} note="Awaiting decision" /><Metric label="APPROVED DAYS" value={leaveMetrics.approvedDays} note="Approved leave days in range" /><Metric label="REJECTED" value={leaveMetrics.rejected} note="Requests rejected" /></div><ReportTable title="Leave requests by status" rows={countBy(filteredLeave.map(row => row.status))} empty="No leave requests overlap this date range." /><section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">LEAVE ACTIVITY</p><h2>Leave register</h2></div><span>{filteredLeave.length} requests</span></div>{filteredLeave.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table reports-detail-table"><thead><tr><th>EMPLOYEE</th><th>FROM</th><th>TO</th><th>DAYS</th><th>STATUS</th></tr></thead><tbody>{filteredLeave.slice(0,250).map((row,index) => <tr key={`${row.employeeId}-${row.dateFrom}-${index}`}><td>{employeeLabel(row.employeeId, employeesById)}</td><td>{row.dateFrom}</td><td>{row.dateTo}</td><td>{row.days}</td><td>{row.status}</td></tr>)}</tbody></table></div> : <div className="timekeeping-empty">No leave activity in range.</div>}</section></>
      : tab === 'overtime' ? <><div className="timekeeping-metrics payroll-metrics reports-metrics"><Metric label="OVERTIME REQUESTS" value={overtimeMetrics.requests} note="In selected date range" /><Metric label="PENDING" value={overtimeMetrics.pending} note="Awaiting decision" /><Metric label="APPROVED TIME" value={minutes(overtimeMetrics.approvedMinutes)} note="Approved minutes" /><Metric label="REJECTED" value={overtimeMetrics.rejected} note="Requests rejected" /></div><ReportTable title="Overtime requests by status" rows={countBy(filteredOvertime.map(row => row.status))} empty="No overtime requests in this date range." /><section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">OVERTIME ACTIVITY</p><h2>Overtime register</h2></div><span>{filteredOvertime.length} requests</span></div>{filteredOvertime.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table reports-detail-table"><thead><tr><th>EMPLOYEE</th><th>DATE</th><th>REQUESTED</th><th>APPROVED</th><th>STATUS</th></tr></thead><tbody>{filteredOvertime.slice(0,250).map((row,index) => <tr key={`${row.employeeId}-${row.workDate}-${index}`}><td>{employeeLabel(row.employeeId, employeesById)}</td><td>{row.workDate}</td><td>{minutes(row.minutesRequested)}</td><td>{minutes(row.approvedMinutes)}</td><td>{row.status}</td></tr>)}</tbody></table></div> : <div className="timekeeping-empty">No overtime activity in range.</div>}</section></>
      : <><div className="timekeeping-metrics payroll-metrics reports-metrics"><Metric label="PAYROLL RUNS" value={filteredRuns.length} note="Periods overlapping range" /><Metric label="PAID RUNS" value={payrollMetrics.paid} note="Completed payments" /><Metric label="GROSS PAY" value={currency(payrollMetrics.gross)} note="All runs in selected range" /><Metric label="NET PAY" value={currency(payrollMetrics.net)} note={`${currency(payrollMetrics.deductions)} total deductions`} /></div><section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">PAYROLL REGISTER</p><h2>Payroll runs</h2></div><span>{filteredRuns.length} runs</span></div>{filteredRuns.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table reports-detail-table"><thead><tr><th>PERIOD</th><th>TYPE</th><th>EMPLOYEES</th><th>GROSS</th><th>DEDUCTIONS</th><th>NET</th><th>STATUS</th></tr></thead><tbody>{filteredRuns.map(run => { const period = periods.find(item => item.id === run.payPeriodId); return <tr key={run.id}><td>{period ? `${period.startDate} – ${period.endDate}` : '—'}</td><td>{run.runType.replaceAll('_', ' ')}</td><td>{run.employeeCount}</td><td>{currency(run.totalGross)}</td><td>{currency(run.totalDeductions)}</td><td><strong>{currency(run.totalNet)}</strong></td><td>{run.status}</td></tr>; })}</tbody></table></div> : <div className="timekeeping-empty">No payroll runs in this date range.</div>}</section></>}
  </section>;

  function employeeLabel(id: string, byId: Map<string, Employee>) { const employee = byId.get(id); return employee ? `${employee.lastName}, ${employee.firstName}` : 'Employee'; }
}

function Metric({ label, value, note }: { label: string; value: string | number; note: string }) { return <article><span>{label}</span><strong>{value}</strong><small>{note}</small></article>; }
function countBy(values: string[]) { const counts = new Map<string, number>(); values.forEach(value => counts.set(value || 'UNKNOWN', (counts.get(value || 'UNKNOWN') || 0) + 1)); return [...counts.entries()].sort((a,b) => b[1] - a[1]); }
function BarBreakdown({ rows, total }: { rows: [string, number][]; total: number }) {
  return <div className="reports-bars">{rows.map(([label, value]) => <div className="reports-bar-row" key={label}><span>{label.replaceAll('_', ' ')}</span><div><i style={{ width: `${total ? Math.max(4, value / total * 100) : 0}%` }} /></div><strong>{value}</strong></div>)}</div>;
}
function ReportTable({ title, rows, names, empty }: { title: string; rows: [string, number][]; names?: Map<string, Employee>; empty: string }) {
  const resolved = names ? rows.map(([id, count]) => { const employee = names.get(id); return [employee ? `${employee.lastName}, ${employee.firstName}` : 'Employee', count] as [string, number]; }).sort((a,b) => b[1]-a[1]).slice(0,12) : rows;
  return <section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">REPORT BREAKDOWN</p><h2>{title}</h2></div></div>{resolved.length ? <BarBreakdown rows={resolved} total={resolved.reduce((sum,row) => sum + row[1],0)} /> : <div className="timekeeping-empty">{empty}</div>}</section>;
}
