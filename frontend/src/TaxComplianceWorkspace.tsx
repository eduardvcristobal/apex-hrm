import { useCallback, useEffect, useState, type FormEvent } from 'react';

type Page<T> = { content: T[] };
type StatutoryRule = { id: string; agency: string; effectiveFrom: string; effectiveTo: string | null; employeeRate: number; employerRate: number; minBase: number; maxBase: number; mscStep: number | null; ecLow: number | null; ecHigh: number | null; ecThreshold: number | null; mpfThreshold: number | null; lowIncomeThreshold: number | null; lowIncomeEmployeeRate: number | null };
type TaxBracket = { id: string; effectiveFrom: string; periodType: string; lowerBound: number; upperBound: number | null; baseTax: number; rate: number };
type WageOrder = { id: string; regionCode: string; sector: string; dailyRate: number; effectiveFrom: string; effectiveTo: string | null; reference: string | null };
type PremiumRule = { id: string; dayType: string; workedMultiplier: number; otMultiplier: number; unworkedMultiplier: number; baseIncludedMonthly: boolean; nightDiffRate: number; effectiveFrom: string };
type Tab = 'contributions' | 'tax' | 'wages' | 'premiums';

const localToday = () => { const value = new Date(); return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`; };
const day = (value: string | null) => value ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${value}T12:00:00`)) : 'No end date';
const percent = (value: number | null) => value === null ? '—' : `${(Number(value) * 100).toFixed(2)}%`;
const php = (value: number | null) => value === null ? '—' : new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP' }).format(Number(value));
const amount = (value: number | null) => value === null ? '—' : Number(value).toLocaleString(undefined, { maximumFractionDigits: 2 });

export function TaxComplianceWorkspace({ token, onOpenSecurity }: { token: string; onOpenSecurity: () => void }) {
  const [contributions, setContributions] = useState<StatutoryRule[]>([]);
  const [brackets, setBrackets] = useState<TaxBracket[]>([]);
  const [wageOrders, setWageOrders] = useState<WageOrder[]>([]);
  const [premiums, setPremiums] = useState<PremiumRule[]>([]);
  const [tab, setTab] = useState<Tab>('contributions');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [mfaVerified, setMfaVerified] = useState<boolean | null>(null);
  const [effectiveFrom, setEffectiveFrom] = useState(localToday);
  const [agency, setAgency] = useState('SSS');
  const [employeeRate, setEmployeeRate] = useState('');
  const [employerRate, setEmployerRate] = useState('');
  const [minBase, setMinBase] = useState('');
  const [maxBase, setMaxBase] = useState('');
  const [mscStep, setMscStep] = useState('');
  const [ecLow, setEcLow] = useState('');
  const [ecHigh, setEcHigh] = useState('');
  const [ecThreshold, setEcThreshold] = useState('');
  const [mpfThreshold, setMpfThreshold] = useState('');
  const [lowIncomeThreshold, setLowIncomeThreshold] = useState('1500');
  const [lowIncomeEmployeeRate, setLowIncomeEmployeeRate] = useState('0.01');
  const [periodType, setPeriodType] = useState('SEMI_MONTHLY');
  const [lowerBound, setLowerBound] = useState('');
  const [upperBound, setUpperBound] = useState('');
  const [baseTax, setBaseTax] = useState('');
  const [rate, setRate] = useState('');
  const [regionCode, setRegionCode] = useState('NCR');
  const [dailyRate, setDailyRate] = useState('');
  const [effectiveTo, setEffectiveTo] = useState('');
  const [reference, setReference] = useState('');
  const [dayType, setDayType] = useState('ORDINARY');
  const [workedMultiplier, setWorkedMultiplier] = useState('');
  const [otMultiplier, setOtMultiplier] = useState('');
  const [unworkedMultiplier, setUnworkedMultiplier] = useState('');
  const [baseIncludedMonthly, setBaseIncludedMonthly] = useState(true);
  const [nightDiffRate, setNightDiffRate] = useState('');

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    const response = await fetch(path, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers } });
    if (!response.ok) {
      let message = `Request failed (${response.status}).`;
      try { const body = await response.json() as { message?: string; detail?: string }; message = body.detail || body.message || message; } catch { /* response has no JSON body */ }
      if (response.status === 401) message = 'Your sign-in session has expired. Sign in again.';
      if (response.status === 403) message = 'Changing global payroll reference data requires a workspace owner or payroll officer with verified MFA.';
      throw new Error(message);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [contributionPage, bracketPage, wagePage, premiumPage] = await Promise.all([
        request<Page<StatutoryRule>>('/api/reference/statutory-contribution-rules?size=1000&sort=effectiveFrom,desc'),
        request<Page<TaxBracket>>('/api/reference/withholding-tax-brackets?size=2000&sort=effectiveFrom,desc'),
        request<Page<WageOrder>>('/api/reference/wage-orders?size=1000&sort=effectiveFrom,desc'),
        request<Page<PremiumRule>>('/api/reference/premium-rules?size=1000&sort=effectiveFrom,desc'),
      ]);
      setContributions(contributionPage.content); setBrackets(bracketPage.content); setWageOrders(wagePage.content); setPremiums(premiumPage.content);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load payroll reference data.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    void request<{ enabled: boolean; tokenVerified: boolean }>('/api/v1/security/mfa').then(status => setMfaVerified(status.tokenVerified)).catch(() => setMfaVerified(false));
  }, [request]);

  async function create(event: FormEvent, path: string, body: object, label: string) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('');
    try {
      await request(path, { method: 'POST', body: JSON.stringify(body) });
      setNotice(`${label} added. Existing reference rows are retained for historical payroll calculations.`);
      await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : `Could not add ${label.toLowerCase()}.`); }
    finally { setBusy(false); }
  }

  function numeric(value: string) { return value.trim() === '' ? null : Number(value); }
  const activeContributions = new Set(contributions.filter(row => row.effectiveFrom <= localToday() && (!row.effectiveTo || row.effectiveTo >= localToday())).map(row => row.agency));
  const latestBracketDate = brackets.reduce((latest, bracket) => bracket.effectiveFrom > latest ? bracket.effectiveFrom : latest, '');

  return <section className="timekeeping-workspace tax-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">PAYROLL CONFIGURATION</p><h1>Tax &amp; compliance</h1><p>Manage effective-dated statutory contributions, withholding tax, wage orders, and premium rules used in payroll calculations.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to maintain payroll rules</strong><small>Enable and verify MFA in account security, then sign in again to get an authorized session.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics payroll-metrics tax-metrics"><article><span>CONTRIBUTION AGENCIES</span><strong>{activeContributions.size} / 3</strong><small>SSS · PhilHealth · Pag-IBIG</small></article><article><span>WITHHOLDING BRACKETS</span><strong>{brackets.length}</strong><small>{latestBracketDate ? `Latest effective date ${day(latestBracketDate)}` : 'No tax table available'}</small></article><article><span>WAGE ORDERS</span><strong>{wageOrders.length}</strong><small>Regional minimum wage reference</small></article><article><span>PREMIUM RULES</span><strong>{premiums.length}</strong><small>Holiday, rest day, and overtime</small></article></div>
    <div className="timekeeping-tabs tax-tabs" role="tablist" aria-label="Tax and compliance reference data"><button type="button" role="tab" aria-selected={tab === 'contributions'} className={tab === 'contributions' ? 'active' : ''} onClick={() => setTab('contributions')}>Statutory contributions <span>{contributions.length}</span></button><button type="button" role="tab" aria-selected={tab === 'tax'} className={tab === 'tax' ? 'active' : ''} onClick={() => setTab('tax')}>Withholding tax <span>{brackets.length}</span></button><button type="button" role="tab" aria-selected={tab === 'wages'} className={tab === 'wages' ? 'active' : ''} onClick={() => setTab('wages')}>Wage orders <span>{wageOrders.length}</span></button><button type="button" role="tab" aria-selected={tab === 'premiums'} className={tab === 'premiums' ? 'active' : ''} onClick={() => setTab('premiums')}>Premium rules <span>{premiums.length}</span></button></div>
    {tab === 'contributions' ? <div className="tax-content-grid"><form className="payroll-panel tax-form" onSubmit={event => void create(event, '/api/reference/statutory-contribution-rules', { agency, effectiveFrom, employeeRate: Number(employeeRate), employerRate: Number(employerRate), minBase: numeric(minBase), maxBase: numeric(maxBase), mscStep: numeric(mscStep), ecLow: numeric(ecLow), ecHigh: numeric(ecHigh), ecThreshold: numeric(ecThreshold), mpfThreshold: numeric(mpfThreshold), lowIncomeThreshold: agency === 'PAGIBIG' ? numeric(lowIncomeThreshold) : null, lowIncomeEmployeeRate: agency === 'PAGIBIG' ? numeric(lowIncomeEmployeeRate) : null }, `${agency} contribution rule`)}><PanelHeading eyebrow="STATUTORY RATE" title="Add contribution rule" /><label className="payroll-field">Agency<select value={agency} onChange={event => { setAgency(event.target.value); setEmployeeRate(''); setEmployerRate(''); setMinBase(''); setMaxBase(''); setMscStep(''); setEcLow(''); setEcHigh(''); setEcThreshold(''); setMpfThreshold(''); setLowIncomeThreshold('1500'); setLowIncomeEmployeeRate('0.01'); }}><option value="SSS">SSS</option><option value="PHILHEALTH">PhilHealth</option><option value="PAGIBIG">Pag-IBIG</option></select></label><label className="payroll-field">Effective from<input type="date" value={effectiveFrom} onChange={event => setEffectiveFrom(event.target.value)} required /></label><div className="tax-form-grid"><NumericField label="Employee rate (decimal)" value={employeeRate} set={setEmployeeRate} min="0" max="1" step="0.0001" required /><NumericField label="Employer rate (decimal)" value={employerRate} set={setEmployerRate} min="0" max="1" step="0.0001" required /><NumericField label="Minimum base (PHP)" value={minBase} set={setMinBase} min="0" required /><NumericField label="Maximum base (PHP)" value={maxBase} set={setMaxBase} min="0" required /></div>{agency === 'SSS' && <div className="tax-form-grid"><NumericField label="MSC step" value={mscStep} set={setMscStep} min="0" required /><NumericField label="EC low" value={ecLow} set={setEcLow} min="0" required /><NumericField label="EC high" value={ecHigh} set={setEcHigh} min="0" required /><NumericField label="EC threshold" value={ecThreshold} set={setEcThreshold} min="0" required /><NumericField label="MPF threshold" value={mpfThreshold} set={setMpfThreshold} min="0" required /></div>}{agency === 'PAGIBIG' && <div className="tax-form-grid"><NumericField label="Low-income ceiling (PHP)" value={lowIncomeThreshold} set={setLowIncomeThreshold} min="0" required /><NumericField label="Low-income employee rate (decimal)" value={lowIncomeEmployeeRate} set={setLowIncomeEmployeeRate} min="0" max="1" step="0.0001" required /></div>}<button type="submit" className="security-submit" disabled={busy || loading || mfaVerified === false}>{busy ? 'Saving…' : '＋ Add effective-dated rule'}</button><p className="payroll-help">Rates are decimals (for example, 0.05 is 5%). New rules are selected by effective date during payroll calculation.</p></form><section className="payroll-panel tax-register"><PanelHeading eyebrow="GLOBAL RULE TABLE" title="Contribution history" count={`${contributions.length} rules`} />{loading ? <div className="timekeeping-empty">Loading rules…</div> : contributions.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table tax-table"><thead><tr><th>AGENCY</th><th>EFFECTIVE</th><th>EMPLOYEE</th><th>EMPLOYER</th><th>BASE RANGE</th><th>END</th></tr></thead><tbody>{contributions.map(row => <tr key={row.id}><td><strong>{row.agency}</strong></td><td>{day(row.effectiveFrom)}</td><td>{percent(row.employeeRate)}</td><td>{percent(row.employerRate)}</td><td>{php(row.minBase)} – {php(row.maxBase)}</td><td>{day(row.effectiveTo)}</td></tr>)}</tbody></table></div> : <div className="timekeeping-empty">No statutory contribution rules are configured.</div>}</section></div>
      : tab === 'tax' ? <div className="tax-content-grid"><form className="payroll-panel tax-form" onSubmit={event => void create(event, '/api/reference/withholding-tax-brackets', { effectiveFrom, periodType, lowerBound: Number(lowerBound), upperBound: numeric(upperBound), baseTax: Number(baseTax), rate: Number(rate) }, `${periodType} tax bracket`)}><PanelHeading eyebrow="WITHHOLDING TABLE" title="Add tax bracket" /><label className="payroll-field">Pay frequency<select value={periodType} onChange={event => setPeriodType(event.target.value)}><option value="MONTHLY">Monthly</option><option value="SEMI_MONTHLY">Semi-monthly</option></select></label><label className="payroll-field">Table effective from<input type="date" value={effectiveFrom} onChange={event => setEffectiveFrom(event.target.value)} required /></label><div className="tax-form-grid"><NumericField label="Lower taxable income" value={lowerBound} set={setLowerBound} min="0" step="0.01" required /><NumericField label="Upper taxable income (blank = no cap)" value={upperBound} set={setUpperBound} min="0" step="0.01" /><NumericField label="Base tax (PHP)" value={baseTax} set={setBaseTax} min="0" step="0.01" required /><NumericField label="Marginal rate (decimal)" value={rate} set={setRate} min="0" max="1" step="0.0001" required /></div><button type="submit" className="security-submit" disabled={busy || loading || mfaVerified === false}>{busy ? 'Saving…' : '＋ Add bracket'}</button><p className="payroll-help">Add every band for a frequency with the same effective date. Calculation selects the latest effective table for the pay group frequency.</p></form><section className="payroll-panel tax-register"><PanelHeading eyebrow="BIR WITHHOLDING REFERENCE" title="Tax brackets" count={`${brackets.length} bands`} />{loading ? <div className="timekeeping-empty">Loading brackets…</div> : brackets.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table tax-table"><thead><tr><th>FREQUENCY</th><th>EFFECTIVE</th><th>LOWER BAND</th><th>UPPER BAND</th><th>BASE TAX</th><th>RATE</th></tr></thead><tbody>{brackets.map(row => <tr key={row.id}><td><strong>{row.periodType.replaceAll('_', ' ')}</strong></td><td>{day(row.effectiveFrom)}</td><td>{php(row.lowerBound)}</td><td>{row.upperBound === null ? 'No limit' : php(row.upperBound)}</td><td>{php(row.baseTax)}</td><td>{percent(row.rate)}</td></tr>)}</tbody></table></div> : <div className="timekeeping-empty">No withholding tax brackets are configured.</div>}</section></div>
      : tab === 'wages' ? <div className="tax-content-grid"><form className="payroll-panel tax-form" onSubmit={event => void create(event, '/api/reference/wage-orders', { regionCode: regionCode.trim().toUpperCase(), sector: 'NON_AGRI', dailyRate: Number(dailyRate), effectiveFrom, effectiveTo: effectiveTo || null, reference: reference.trim() || null }, `${regionCode} wage order`)}><PanelHeading eyebrow="MINIMUM WAGE REFERENCE" title="Add wage order" /><div className="tax-form-grid"><label className="payroll-field">Region code<input value={regionCode} onChange={event => setRegionCode(event.target.value.toUpperCase())} required maxLength={30} placeholder="e.g. NCR" /></label><NumericField label="Daily minimum wage (PHP)" value={dailyRate} set={setDailyRate} min="0.01" step="0.01" required /><label className="payroll-field">Reference<input value={reference} onChange={event => setReference(event.target.value)} maxLength={100} placeholder="Wage order number" /></label><label className="payroll-field">Effective from<input type="date" value={effectiveFrom} onChange={event => setEffectiveFrom(event.target.value)} required /></label><label className="payroll-field">Effective to (optional)<input type="date" value={effectiveTo} min={effectiveFrom} onChange={event => setEffectiveTo(event.target.value)} /></label></div><button type="submit" className="security-submit" disabled={busy || loading || mfaVerified === false}>{busy ? 'Saving…' : '＋ Add wage order'}</button><p className="payroll-help">Payroll currently checks NON_AGRI wage-order rows against each employee’s work-location region.</p></form><section className="payroll-panel tax-register"><PanelHeading eyebrow="REGIONAL WAGE HISTORY" title="Wage orders" count={`${wageOrders.length} records`} />{loading ? <div className="timekeeping-empty">Loading wage orders…</div> : wageOrders.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table tax-table"><thead><tr><th>REGION</th><th>SECTOR</th><th>DAILY RATE</th><th>EFFECTIVE</th><th>END</th><th>REFERENCE</th></tr></thead><tbody>{wageOrders.map(row => <tr key={row.id}><td><strong>{row.regionCode}</strong></td><td>{row.sector.replaceAll('_', ' ')}</td><td>{php(row.dailyRate)}</td><td>{day(row.effectiveFrom)}</td><td>{day(row.effectiveTo)}</td><td>{row.reference || '—'}</td></tr>)}</tbody></table></div> : <div className="timekeeping-empty">No wage orders are configured.</div>}</section></div>
      : <div className="tax-content-grid"><form className="payroll-panel tax-form" onSubmit={event => void create(event, '/api/reference/premium-rules', { dayType, workedMultiplier: Number(workedMultiplier), otMultiplier: Number(otMultiplier), unworkedMultiplier: Number(unworkedMultiplier), baseIncludedMonthly, nightDiffRate: Number(nightDiffRate), effectiveFrom }, `${dayType.replaceAll('_', ' ')} premium rule`)}><PanelHeading eyebrow="PAY PREMIUM MATRIX" title="Add premium rule" /><label className="payroll-field">Day type<select value={dayType} onChange={event => setDayType(event.target.value)}>{['ORDINARY','REST_DAY','SPECIAL_HOLIDAY','SPECIAL_HOLIDAY_REST_DAY','REGULAR_HOLIDAY','REGULAR_HOLIDAY_REST_DAY'].map(value => <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}</select></label><label className="payroll-field">Effective from<input type="date" value={effectiveFrom} onChange={event => setEffectiveFrom(event.target.value)} required /></label><div className="tax-form-grid"><NumericField label="Worked multiplier" value={workedMultiplier} set={setWorkedMultiplier} min="0" step="0.01" required /><NumericField label="Overtime multiplier" value={otMultiplier} set={setOtMultiplier} min="0" step="0.01" required /><NumericField label="Unworked multiplier" value={unworkedMultiplier} set={setUnworkedMultiplier} min="0" step="0.01" required /><NumericField label="Night differential rate" value={nightDiffRate} set={setNightDiffRate} min="0" max="1" step="0.0001" required /></div><label className="benefits-taxable"><input type="checkbox" checked={baseIncludedMonthly} onChange={event => setBaseIncludedMonthly(event.target.checked)} /> Monthly base already includes this day</label><button type="submit" className="security-submit" disabled={busy || loading || mfaVerified === false}>{busy ? 'Saving…' : '＋ Add premium rule'}</button><p className="payroll-help">Rules are effective-dated and apply when a payroll run computes attendance premiums for the selected day type.</p></form><section className="payroll-panel tax-register"><PanelHeading eyebrow="PAY PREMIUM MATRIX" title="Premium rules" count={`${premiums.length} records`} />{loading ? <div className="timekeeping-empty">Loading premium rules…</div> : premiums.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table tax-table"><thead><tr><th>DAY TYPE</th><th>EFFECTIVE</th><th>WORKED</th><th>OVERTIME</th><th>UNWORKED</th><th>NIGHT DIFF.</th><th>MONTHLY BASE</th></tr></thead><tbody>{premiums.map(row => <tr key={row.id}><td><strong>{row.dayType.replaceAll('_', ' ')}</strong></td><td>{day(row.effectiveFrom)}</td><td>{amount(row.workedMultiplier)}×</td><td>{amount(row.otMultiplier)}×</td><td>{amount(row.unworkedMultiplier)}×</td><td>{percent(row.nightDiffRate)}</td><td>{row.baseIncludedMonthly ? 'Included' : 'Not included'}</td></tr>)}</tbody></table></div> : <div className="timekeeping-empty">No premium rules are configured.</div>}</section></div>}
    <p className="timekeeping-footnote">These are global reference rules used when payroll is computed. Add new effective-dated rows to preserve previous payroll calculations. Review configured rule values before processing payroll.</p>
  </section>;
}

function PanelHeading({ eyebrow, title, count }: { eyebrow: string; title: string; count?: string }) {
  return <div className="payroll-panel-heading"><div><p className="eyebrow">{eyebrow}</p><h2>{title}</h2></div>{count && <span>{count}</span>}</div>;
}

function NumericField({ label, value, set, min, max, step = '0.01', required = false }: { label: string; value: string; set: (value: string) => void; min?: string; max?: string; step?: string; required?: boolean }) {
  return <label className="payroll-field">{label}<input type="number" inputMode="decimal" value={value} onChange={event => set(event.target.value)} min={min} max={max} step={step} required={required} /></label>;
}
