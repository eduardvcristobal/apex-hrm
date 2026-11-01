import { useEffect, useState, type FormEvent } from 'react';

type Employee = { id: string; employeeNo: string; firstName: string; lastName: string };
type Page<T> = { content: T[] };
type Ledger = { regularTaxable: number; thirteenthMonth: number; otherBenefits: number; withheld: number };
type Tax = { exemptBenefits: number; taxableBenefits: number; annualTaxableIncome: number; annualTax: number; additionalWithholdingOrRefund: number };
type Reconciliation = { employeeId: string; taxYear: number; lineCount: number; ledger: Ledger; tax: Tax; certificationStatus: string };
const php = (value: number) => new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' }).format(value);

export function AnnualizationReviewPanel({ token }: { token: string }) {
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [employeeId, setEmployeeId] = useState('');
  const [year, setYear] = useState(new Date().getFullYear());
  const [result, setResult] = useState<Reconciliation | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    fetch('/api/payroll/employees?size=1000&sort=employeeNo,asc', {
      headers: { Authorization: `Bearer ${token}` }
    }).then(async response => {
      if (!response.ok) throw new Error('Employee directory is unavailable or access is denied.');
      return response.json() as Promise<Page<Employee>>;
    }).then(data => {
      if (active) { setEmployees(data.content); setEmployeeId(current => current || data.content[0]?.id || ''); }
    }).catch(e => { if (active) setError(e instanceof Error ? e.message : 'Unable to load employees'); });
    return () => { active = false; };
  }, [token]);

  async function review(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(''); setResult(null);
    try {
      const response = await fetch(`/api/payroll/tax/employees/${encodeURIComponent(employeeId)}/annualization?year=${year}`, {
        headers: { Authorization: `Bearer ${token}` }
      });
      if (!response.ok) {
        let detail = `Annualization review failed (${response.status}).`;
        try { const body = await response.json() as { detail?: string; message?: string };
          detail = body.detail || body.message || detail; } catch { /* ignore unknown error body */ }
        throw new Error(detail);
      }
      setResult(await response.json() as Reconciliation);
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not reconcile payroll'); }
    finally { setBusy(false); }
  }

  return <section className="payroll-panel">
    <div className="payroll-panel-heading"><div><p className="eyebrow">BIR ANNUAL RECONCILIATION</p><h2>Year-to-date tax review</h2></div><span>READ ONLY</span></div>
    <p className="payroll-help">Figures are derived from locked or paid payroll entries. The report is not a BIR filing, approved refund, or automatic deduction. Verify all benefit codes and completeness of the tax year.</p>
    <form onSubmit={event => void review(event)} className="tax-form-grid">
      <label className="payroll-field">Employee
        <select required value={employeeId} onChange={event => { setEmployeeId(event.target.value); setResult(null); }}>
          <option value="" disabled>Select an employee</option>
          {employees.map(e => <option key={e.id} value={e.id}>{e.employeeNo} — {e.firstName} {e.lastName}</option>)}
        </select>
      </label>
      <label className="payroll-field">Tax year
        <input type="number" min="2023" max={new Date().getFullYear()} value={year} onChange={event => { setYear(Number(event.target.value)); setResult(null); }} required />
      </label>
      <button className="security-submit" type="submit" disabled={busy || !employeeId}>{busy ? 'Reconciling…' : 'Review annual tax'}</button>
    </form>
    {error && <p role="alert" className="form-message" data-kind="error">{error}</p>}
    {result && <>
      <p role="status" className="payroll-help"><strong>Review required.</strong> {result.lineCount} finalized payroll lines included for tax year {result.taxYear}. Confirm all pay periods are present.</p>
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
    </>}
  </section>;
}
