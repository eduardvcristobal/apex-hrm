import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';

type Company = { id: string; legalName: string; tradeName: string | null };
type Page<T> = { content: T[] };
type PayGroup = { id: string; companyId: string; code: string; name: string; frequency: string; daysFactor: number; hoursPerDay: number; contributionSchedule: string; taxMethod: string };
type PayPeriod = { id: string; payGroupId: string; periodOfMonth: number; startDate: string; endDate: string; payDate: string; status: string };
const localDate = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
const formatDate = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${value}T12:00:00`));

export function PaySetupWorkspace({ token, onOpenSecurity }: { token: string; onOpenSecurity: () => void }) {
  const [companies, setCompanies] = useState<Company[]>([]);
  const [groups, setGroups] = useState<PayGroup[]>([]);
  const [periods, setPeriods] = useState<PayPeriod[]>([]);
  const [companyId, setCompanyId] = useState('');
  const [groupId, setGroupId] = useState('');
  const [groupCode, setGroupCode] = useState('');
  const [groupName, setGroupName] = useState('');
  const [frequency, setFrequency] = useState<'MONTHLY' | 'SEMI_MONTHLY'>('SEMI_MONTHLY');
  const [daysFactor, setDaysFactor] = useState('261');
  const [hoursPerDay, setHoursPerDay] = useState('8');
  const [contributionSchedule, setContributionSchedule] = useState('LAST_CUTOFF');
  const [periodOfMonth, setPeriodOfMonth] = useState('1');
  const [startDate, setStartDate] = useState(() => localDate(new Date(new Date().getFullYear(), new Date().getMonth(), 1)));
  const [endDate, setEndDate] = useState(() => localDate(new Date()));
  const [payDate, setPayDate] = useState(() => localDate(new Date()));
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
      if (response.status === 403) message = 'Pay group and period setup requires a workspace owner or payroll officer with verified MFA.';
      throw new Error(message);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [companyRows, groupPage, periodPage] = await Promise.all([
        request<Company[]>('/api/payroll/companies'),
        request<Page<PayGroup>>('/api/pay-groups?size=1000&sort=name,asc'),
        request<Page<PayPeriod>>('/api/pay-periods?size=2000&sort=startDate,desc'),
      ]);
      setCompanies(companyRows); setGroups(groupPage.content); setPeriods(periodPage.content);
      setCompanyId(current => current || companyRows[0]?.id || ''); setGroupId(current => current || groupPage.content[0]?.id || '');
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load payroll setup.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    void request<{ enabled: boolean; tokenVerified: boolean }>('/api/v1/security/mfa').then(status => setMfaVerified(status.tokenVerified)).catch(() => setMfaVerified(false));
  }, [request]);

  const companyById = useMemo(() => new Map(companies.map(company => [company.id, company])), [companies]);
  const groupById = useMemo(() => new Map(groups.map(group => [group.id, group])), [groups]);
  const selectedGroup = groupById.get(groupId);
  const visiblePeriods = periods.filter(period => !groupId || period.payGroupId === groupId);
  const lockedPeriods = periods.filter(period => period.status === 'LOCKED').length;

  async function addGroup(event: FormEvent) {
    event.preventDefault(); setBusy('group'); setError(''); setNotice('');
    try {
      const created = await request<PayGroup>('/api/pay-groups', { method: 'POST', body: JSON.stringify({ companyId, code: groupCode.trim().toUpperCase(), name: groupName.trim(), frequency, daysFactor: Number(daysFactor), hoursPerDay: Number(hoursPerDay), contributionSchedule, taxMethod: 'STRICT' }) });
      setGroupCode(''); setGroupName(''); setGroupId(created.id); setNotice(`Pay group ${created.name} created.`); await load(); setGroupId(created.id);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not add pay group.'); }
    finally { setBusy(''); }
  }

  async function addPeriod(event: FormEvent) {
    event.preventDefault(); if (!groupId) return;
    if (endDate < startDate) { setError('Period end must be on or after its start date.'); return; }
    setBusy('period'); setError(''); setNotice('');
    try {
      const created = await request<PayPeriod>('/api/pay-periods', { method: 'POST', body: JSON.stringify({ payGroupId: groupId, periodOfMonth: Number(periodOfMonth), startDate, endDate, payDate, status: 'OPEN' }) });
      setPeriods(current => [created, ...current].sort((a,b) => b.startDate.localeCompare(a.startDate))); setNotice(`Pay period ${formatDate(created.startDate)} – ${formatDate(created.endDate)} added.`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not add pay period.'); }
    finally { setBusy(''); }
  }

  return <section className="timekeeping-workspace pay-setup-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">PAYROLL CONFIGURATION</p><h1>Pay groups &amp; periods</h1><p>Configure company pay cycles and open periods before creating payroll runs.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to manage payroll setup</strong><small>Enable and verify MFA in account security, then sign in again to get an authorized session.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics payroll-metrics"><article><span>PAY GROUPS</span><strong>{groups.length}</strong><small>Company pay configurations</small></article><article><span>OPEN PERIODS</span><strong>{periods.filter(period => period.status === 'OPEN').length}</strong><small>Available for payroll runs</small></article><article><span>LOCKED PERIODS</span><strong>{lockedPeriods}</strong><small>Closed by an approved payroll</small></article><article><span>COMPANIES</span><strong>{companies.length}</strong><small>Available legal entities</small></article></div>
    <div className="pay-setup-grid">
      <form className="payroll-panel pay-setup-form" onSubmit={event => void addGroup(event)}><div className="payroll-panel-heading"><div><p className="eyebrow">PAYROLL STRUCTURE</p><h2>Create pay group</h2></div></div><p className="payroll-help pay-setup-help">A pay group defines the company, frequency, work-year factor, and contribution schedule used to calculate pay.</p><label className="payroll-field">Company<select value={companyId} onChange={event => setCompanyId(event.target.value)} required><option value="">Select a company…</option>{companies.map(company => <option key={company.id} value={company.id}>{company.tradeName || company.legalName}</option>)}</select></label><div className="pay-setup-two-col"><label className="payroll-field">Group code<input value={groupCode} onChange={event => setGroupCode(event.target.value.toUpperCase())} required maxLength={40} pattern="[A-Za-z0-9_-]+" placeholder="e.g. PH-MONTHLY" /></label><label className="payroll-field">Name<input value={groupName} onChange={event => setGroupName(event.target.value)} required maxLength={120} placeholder="Monthly salaried staff" /></label></div><label className="payroll-field">Frequency<select value={frequency} onChange={event => { setFrequency(event.target.value as 'MONTHLY' | 'SEMI_MONTHLY'); setPeriodOfMonth('1'); }}><option value="MONTHLY">Monthly</option><option value="SEMI_MONTHLY">Semi-monthly</option></select></label><div className="pay-setup-two-col"><label className="payroll-field">Days factor per year<input type="number" min="1" max="366" step="0.01" value={daysFactor} onChange={event => setDaysFactor(event.target.value)} required /></label><label className="payroll-field">Hours per workday<input type="number" min="1" max="24" step="0.25" value={hoursPerDay} onChange={event => setHoursPerDay(event.target.value)} required /></label></div><label className="payroll-field">Statutory contribution schedule<select value={contributionSchedule} onChange={event => setContributionSchedule(event.target.value)}><option value="LAST_CUTOFF">Last cutoff of month</option><option value="EVERY_CUTOFF">Every cutoff</option></select></label><button type="submit" className="security-submit" disabled={!!busy || loading || !companyId || mfaVerified === false}>{busy === 'group' ? 'Saving…' : '＋ Create pay group'}</button></form>
      <form className="payroll-panel pay-setup-form" onSubmit={event => void addPeriod(event)}><div className="payroll-panel-heading"><div><p className="eyebrow">PAY CALENDAR</p><h2>Add pay period</h2></div></div><p className="payroll-help pay-setup-help">Create periods in order. A regular run can lock its period, after which the period is frozen.</p><label className="payroll-field">Pay group<select value={groupId} onChange={event => { setGroupId(event.target.value); setPeriodOfMonth('1'); }} required><option value="">Select a pay group…</option>{groups.map(group => <option key={group.id} value={group.id}>{group.code} · {group.name}</option>)}</select></label><label className="payroll-field">Cutoff<select value={periodOfMonth} onChange={event => setPeriodOfMonth(event.target.value)}><option value="1">{selectedGroup?.frequency === 'MONTHLY' ? 'Monthly period' : 'First cutoff'}</option>{selectedGroup?.frequency !== 'MONTHLY' && <option value="2">Second cutoff</option>}</select></label><div className="pay-setup-two-col"><label className="payroll-field">Period start<input type="date" value={startDate} onChange={event => setStartDate(event.target.value)} required /></label><label className="payroll-field">Period end<input type="date" value={endDate} min={startDate} onChange={event => setEndDate(event.target.value)} required /></label></div><label className="payroll-field">Pay date<input type="date" value={payDate} onChange={event => setPayDate(event.target.value)} required /></label><button type="submit" className="security-submit" disabled={!!busy || loading || !groupId || mfaVerified === false}>{busy === 'period' ? 'Saving…' : '＋ Add open period'}</button><p className="payroll-help pay-setup-help">Tax withholding tables are matched to the pay group frequency. Use MONTHLY or SEMI-MONTHLY groups only.</p></form>
    </div>
    <section className="payroll-panel pay-setup-register"><div className="payroll-panel-heading"><div><p className="eyebrow">PAY CALENDAR</p><h2>Pay groups</h2></div><span>{groups.length} groups</span></div>{loading ? <div className="timekeeping-empty">Loading pay groups…</div> : groups.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table pay-setup-groups-table"><thead><tr><th>COMPANY</th><th>CODE</th><th>PAY GROUP</th><th>FREQUENCY</th><th>WORK YEAR</th><th>CONTRIBUTIONS</th></tr></thead><tbody>{groups.map(group => <tr key={group.id}><td>{companyById.get(group.companyId)?.tradeName || companyById.get(group.companyId)?.legalName || 'Company'}</td><td><strong>{group.code}</strong></td><td>{group.name}</td><td>{group.frequency.replaceAll('_', ' ')}</td><td>{group.daysFactor} days · {group.hoursPerDay}h/day</td><td>{group.contributionSchedule.replaceAll('_', ' ')}</td></tr>)}</tbody></table></div> : <div className="timekeeping-empty">No pay groups configured. Create a pay group to define your payroll frequency.</div>}</section>
    <section className="payroll-panel pay-setup-register"><div className="payroll-panel-heading"><div><p className="eyebrow">PAY CALENDAR</p><h2>Pay periods</h2></div><label className="pay-setup-filter">Filter by group<select value={groupId} onChange={event => setGroupId(event.target.value)}><option value="">All pay groups</option>{groups.map(group => <option key={group.id} value={group.id}>{group.code} · {group.name}</option>)}</select></label></div>{loading ? <div className="timekeeping-empty">Loading pay periods…</div> : visiblePeriods.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table pay-period-table"><thead><tr><th>PAY GROUP</th><th>CUTOFF</th><th>PERIOD START</th><th>PERIOD END</th><th>PAY DATE</th><th>STATUS</th></tr></thead><tbody>{visiblePeriods.map(period => <tr key={period.id}><td><strong>{groupById.get(period.payGroupId)?.name || 'Pay group'}</strong></td><td>{groupById.get(period.payGroupId)?.frequency === 'MONTHLY' ? 'Monthly' : period.periodOfMonth === 1 ? 'First cutoff' : 'Second cutoff'}</td><td>{formatDate(period.startDate)}</td><td>{formatDate(period.endDate)}</td><td>{formatDate(period.payDate)}</td><td><span className={`timekeeping-status ${period.status === 'OPEN' ? 'open' : 'locked'}`}>{period.status}</span></td></tr>)}</tbody></table></div> : <div className="timekeeping-empty">No pay periods match this pay group filter.</div>}</section>
    <p className="timekeeping-footnote">Pay periods are added individually because actual cutoff dates and pay dates vary by company. Locked periods remain visible as read-only history.</p>
  </section>;
}
