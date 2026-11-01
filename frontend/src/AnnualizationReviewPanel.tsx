import { useEffect, useState, type FormEvent } from 'react';

type Employee = { id: string; employeeNo: string; firstName: string; lastName: string };
type Period = { id: string; startDate: string; endDate: string; status: string };
type Page<T> = { content: T[] };
type Ledger = { regularTaxable: number; thirteenthMonth: number; otherBenefits: number; withheld: number };
type Tax = { exemptBenefits: number; taxableBenefits: number; annualTaxableIncome: number; annualTax: number; additionalWithholdingOrRefund: number };
type Reconciliation = { employeeId: string; taxYear: number; lineCount: number; ledger: Ledger; tax: Tax; certificationStatus: string };
type AnnualRequest = { id: string; employeeId: string; taxYear: number; targetPeriodId: string;
  adjustmentAmount: number; status: string; requestedBy: string; approvedBy: string | null; adjustmentId: string | null };
const php = (value: number) => new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' }).format(Number(value));

export function AnnualizationReviewPanel({ token }: { token: string }) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [periods, setPeriods] = useState<Period[]>([]);
  const [employeeId, setEmployeeId] = useState('');
  const [year, setYear] = useState(new Date().getFullYear() - 1);
  const [result, setResult] = useState<Reconciliation | null>(null);
  const [existing, setExisting] = useState<AnnualRequest | null>(null);
  const [targetPeriodId, setTargetPeriodId] = useState('');
  const [justification, setJustification] = useState('');
  const [completeYearVerified, setCompleteYearVerified] = useState(false);
  const [benefitsVerified, setBenefitsVerified] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const request = async <T,>(url: string, init: RequestInit = {}): Promise<T> => {
    const response = await fetch(url, {
      ...init,
      headers: { Authorization: `Bearer ${token}`,
        ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers }
    });
    if (!response.ok) {
      let detail = `Payroll request failed (${response.status}).`;
      try {
        const body = await response.json() as { detail?: string; message?: string };
        detail = body.detail || body.message || detail;
      } catch { /* response may be empty */ }
      throw new Error(detail);
    }
    return response.json() as Promise<T>;
  };

  useEffect(() => {
    let active = true;
    Promise.all([
      request<Page<Employee>>('/api/payroll/employees?size=1000&sort=employeeNo,asc'),
      request<Page<Period>>('/api/pay-periods?size=500&sort=startDate,desc')
    ]).then(([directory, payPeriods]) => {
      if (!active) return;
      setEmployees(directory.content);
      setPeriods(payPeriods.content);
      setEmployeeId(current => current || directory.content[0]?.id || '');
    }).catch(e => { if (active) setError(e instanceof Error ? e.message : 'Unable to load payroll reference data'); });
    return () => { active = false; };
    // Re-run only when the access token changes, not on each local form update.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const availablePeriods = periods.filter(p =>
    p.status === 'OPEN' && p.startDate > `${year}-12-31`);

  async function review(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(''); setNotice(''); setResult(null); setExisting(null);
    try {
      const [reconciled, found] = await Promise.all([
        request<Reconciliation>(`/api/payroll/tax/employees/${encodeURIComponent(employeeId)}/annualization?year=${year}`),
        request<AnnualRequest | null>(`/api/payroll/tax/adjustment-requests?employeeId=${encodeURIComponent(employeeId)}&taxYear=${year}`)
      ]);
      setResult(reconciled); setExisting(found);
      setTargetPeriodId(current => availablePeriods.some(p => p.id === current)
        ? current : availablePeriods[0]?.id || '');
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not reconcile payroll'); }
    finally { setBusy(false); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!result || existing) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const created = await request<AnnualRequest>('/api/payroll/tax/adjustment-requests', {
        method: 'POST', body: JSON.stringify({
          employeeId, taxYear: year, targetPeriodId, completeYearVerified,
          benefitsVerified, justification
        })
      });
      setExisting(created);
      setNotice('Annual withholding request submitted. A different authorized payroll administrator must approve it.');
    } catch (e) { setError(e instanceof Error ? e.message : 'Request failed'); }
    finally { setBusy(false); }
  }

  async function decide(action: 'approve' | 'reject') {
    if (!existing || existing.status !== 'PENDING') return;
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = await request<AnnualRequest>(
        `/api/payroll/tax/adjustment-requests/${existing.id}/${action}`, { method: 'POST' });
      setExisting(updated);
      setNotice(action === 'approve'
        ? 'Approved adjustment queued for the designated open pay period. No payment was issued.'
        : 'Request rejected and retained for audit review.');
    } catch (e) { setError(e instanceof Error ? e.message : 'Decision failed'); }
    finally { setBusy(false); }
  }

  return <section className="payroll-panel">
    <div className="payroll-panel-heading"><div><p className="eyebrow">BIR ANNUAL RECONCILIATION</p><h2>Year-to-date tax review</h2></div><span>CONTROLLED REVIEW</span></div>
    <p className="payroll-help">Figures come from locked or paid payroll. Submission requires a completed prior tax year, documented full-year and benefit review, and a second payroll administrator. No automatic refund or payment is issued.</p>
    <form onSubmit={event => void review(event)} className="tax-form-grid">
      <label className="payroll-field">Employee
        <select required value={employeeId} onChange={event => { setEmployeeId(event.target.value); setResult(null); setExisting(null); }}>
          <option value="" disabled>Select an employee</option>
          {employees.map(e => <option key={e.id} value={e.id}>{e.employeeNo} — {e.firstName} {e.lastName}</option>)}
        </select>
      </label>
      <label className="payroll-field">Tax year
        <input type="number" min="2023" max={new Date().getFullYear()} value={year} onChange={event => { setYear(Number(event.target.value)); setResult(null); setExisting(null); }} required />
      </label>
      <button className="security-submit" type="submit" disabled={busy || !employeeId}>{busy ? 'Working…' : 'Review annual tax'}</button>
    </form>
    {error && <p role="alert" className="form-message" data-kind="error">{error}</p>}
    {notice && <p role="status" className="form-message" data-kind="success">{notice}</p>}
    {result && <>
      <p role="status" className="payroll-help"><strong>Review required.</strong> {result.lineCount} finalized payroll lines included for tax year {result.taxYear}. Confirm every pay period and benefit is accounted for.</p>
      <div className="timekeeping-table-wrap"><table className="timekeeping-table"><tbody>
        <tr><th scope="row">Taxable regular compensation</th><td>{php(result.ledger.regularTaxable)}</td></tr>
        <tr><th scope="row">13th-month pay</th><td>{php(result.ledger.thirteenthMonth)}</td></tr>
        <tr><th scope="row">Classified other benefits</th><td>{php(result.ledger.otherBenefits)}</td></tr>
        <tr><th scope="row">Shared exempt benefits</th><td>{php(result.tax.exemptBenefits)}</td></tr>
        <tr><th scope="row">Taxable excess benefits</th><td>{php(result.tax.taxableBenefits)}</td></tr>
        <tr><th scope="row">Annual taxable income</th><td>{php(result.tax.annualTaxableIncome)}</td></tr>
        <tr><th scope="row">Computed annual tax</th><td>{php(result.tax.annualTax)}</td></tr>
        <tr><th scope="row">Tax previously withheld</th><td>{php(result.ledger.withheld)}</td></tr>
        <tr><th scope="row">Additional withholding (+) / possible refund (−)</th><td><strong>{php(result.tax.additionalWithholdingOrRefund)}</strong></td></tr>
      </tbody></table></div>
      <p className="payroll-help">{result.certificationStatus}</p>
      {existing ? <div className="payroll-panel">
        <div className="payroll-panel-heading"><div><p className="eyebrow">DUAL APPROVAL</p><h2>Annual tax adjustment: {existing.status}</h2></div></div>
        <p className="payroll-help">Request ID: {existing.id}. Amount: {php(existing.adjustmentAmount)}. A different authorized user must make the decision. Approved requests enter the configured payroll adjustment queue, not immediate disbursement.</p>
        {existing.status === 'PENDING' && <div className="tax-form-grid">
          <button type="button" className="security-submit" disabled={busy} onClick={() => void decide('approve')}>Approve request</button>
          <button type="button" className="security-submit" disabled={busy} onClick={() => void decide('reject')}>Reject request</button>
        </div>}
      </div> : year >= new Date().getFullYear()
        ? <p className="payroll-help">Current-year reconciliation is informational only. Annual withholding requests require a completed prior tax year.</p>
        : result.tax.additionalWithholdingOrRefund <= 0
          ? <p className="payroll-help">No positive withholding adjustment is available. Refunds require a separate approved workflow.</p>
          : <form onSubmit={event => void submit(event)} className="payroll-panel">
            <div className="payroll-panel-heading"><div><p className="eyebrow">DUAL APPROVAL</p><h2>Request annual withholding adjustment</h2></div></div>
            <label className="payroll-field">Open target pay period
              <select value={targetPeriodId} onChange={event => setTargetPeriodId(event.target.value)} required>
                <option value="" disabled>Select a period</option>
                {availablePeriods.map(p => <option key={p.id} value={p.id}>{p.startDate} – {p.endDate}</option>)}
              </select>
            </label>
            <label className="payroll-field">Reconciliation justification
              <textarea minLength={20} maxLength={2000} rows={3} required value={justification}
                onChange={event => setJustification(event.target.value)}
                placeholder="Document the payroll ledger, benefit classifications and authorized reviewer evidence" />
            </label>
            <label><input type="checkbox" checked={completeYearVerified} onChange={event => setCompleteYearVerified(event.target.checked)} /> I verified all pay periods and employee compensation for this tax year.</label>
            <label><input type="checkbox" checked={benefitsVerified} onChange={event => setBenefitsVerified(event.target.checked)} /> I reviewed all taxable and nontaxable benefits, including 13th-month treatment.</label>
            <button type="submit" className="security-submit" disabled={busy || !targetPeriodId || !completeYearVerified || !benefitsVerified || justification.trim().length < 20}>
              {busy ? 'Submitting…' : `Request ${php(result.tax.additionalWithholdingOrRefund)} withholding`}
            </button>
          </form>}
    </>}
  </section>;
}
