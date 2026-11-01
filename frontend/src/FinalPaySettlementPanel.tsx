import { useEffect, useState, type FormEvent } from 'react';

type Handoff = { id: string; status: string };
type Settlement = { id: string; status: string; gross: number; deductions: number; net: number; requestedBy: string; decidedBy: string | null; settlementGate: string };
type Statement = { settlementId: string; status: string; net: number; gross: number; deductions: number; accountingReference: string; justification: string; disbursementStatus: string };
type FieldName = 'unpaidBasic' | 'accruedThirteenthMonth' | 'unusedLeaveConversion' | 'separationPay' | 'otherEarnings' | 'outstandingLoans' | 'accountableProperty' | 'withholdingTax' | 'otherAuthorizedDeductions';
const fields: { name: FieldName; label: string }[] = [
  { name: 'unpaidBasic', label: 'Unpaid basic wages' },
  { name: 'accruedThirteenthMonth', label: 'Accrued 13th-month pay' },
  { name: 'unusedLeaveConversion', label: 'Approved unused leave conversion' },
  { name: 'separationPay', label: 'HR-verified separation pay' },
  { name: 'otherEarnings', label: 'Other approved earnings' },
  { name: 'outstandingLoans', label: 'Verified loan balances' },
  { name: 'accountableProperty', label: 'Approved recoverable property amount' },
  { name: 'withholdingTax', label: 'Reviewed final withholding tax' },
  { name: 'otherAuthorizedDeductions', label: 'Other authorized deductions' }
];
const money = (amount: number) => new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP' }).format(amount);
const zeroes = (): Record<FieldName, string> => ({
  unpaidBasic: '0', accruedThirteenthMonth: '0', unusedLeaveConversion: '0',
  separationPay: '0', otherEarnings: '0', outstandingLoans: '0',
  accountableProperty: '0', withholdingTax: '0', otherAuthorizedDeductions: '0'
});

/** Uses the existing Personnel request helper to retain auth, MFA and error handling. */
export function FinalPaySettlementPanel({ employeeId, caseId, currentUserId, request }: {
  employeeId: string; caseId: string; currentUserId: string;
  request: <T>(path: string, init?: RequestInit) => Promise<T>;
}) {
  const [handoff, setHandoff] = useState<Handoff | null>(null);
  const [rows, setRows] = useState<Settlement[]>([]);
  const [values, setValues] = useState<Record<FieldName, string>>(zeroes);
  const [accountingReference, setAccountingReference] = useState('');
  const [justification, setJustification] = useState('');
  const [attested, setAttested] = useState({ leave: false, loan: false, tax: false, entitlement: false });
  const [statement, setStatement] = useState<Statement | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const base = `/api/v1/payroll/final-pay`;
  useEffect(() => {
    let active = true;
    Promise.all([
      request<Handoff[]>(`/api/v1/personnel/employees/${employeeId}/hr-cases/${caseId}/final-pay`),
      request<Settlement[]>(`${base}/employees/${employeeId}/cases/${caseId}/settlements`)
    ]).then(([handoffs, settlements]) => {
      if (active) { setHandoff(handoffs[0] || null); setRows(settlements); }
    }).catch(e => { if (active) setMessage(e instanceof Error ? e.message : 'Unable to load final pay handoff'); });
    return () => { active = false; };
  }, [employeeId, caseId, request]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!handoff) return;
    setBusy(true); setMessage('');
    try {
      const created = await request<Settlement>(`${base}/employees/${employeeId}/cases/${caseId}/handoffs/${handoff.id}/settlements`, {
        method: 'POST',
        body: JSON.stringify({
          amounts: Object.fromEntries(fields.map(field => [field.name, Number(values[field.name])])),
          accountingReference, justification,
          leaveVerified: attested.leave, loansVerified: attested.loan,
          taxVerified: attested.tax, separationEntitlementVerified: attested.entitlement
        })
      });
      setRows(current => [created, ...current]);
      setMessage('Final pay settlement recorded. A different payroll approver must review and decide.');
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Unable to submit settlement'); }
    finally { setBusy(false); }
  }

  async function decide(id: string, action: 'approve' | 'reject') {
    setBusy(true); setMessage('');
    try {
      const updated = await request<Settlement>(`${base}/settlements/${id}/${action}`, { method: 'POST' });
      setRows(current => current.map(row => row.id === id ? updated : row));
      setMessage(action === 'approve'
        ? 'Settlement approved for finance review. Disbursement is blocked pending employee mapping certification.'
        : 'Settlement rejected and retained for audit.');
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Unable to decide settlement'); }
    finally { setBusy(false); }
  }

  async function openStatement(id: string) {
    try { setStatement(await request<Statement>(`${base}/settlements/${id}/statement`)); }
    catch (e) { setMessage(e instanceof Error ? e.message : 'Statement not available'); }
  }

  return <div className="people-case-tasks">
    <h4>Payroll final-pay settlement</h4>
    <p className="people-muted">Approved Personnel separation and clearance are mandatory. Figures below require reconciled source records; approval does not issue a payslip or release funds.</p>
    {!handoff && <div className="people-form-actions"><p className="people-muted">No HR-to-payroll handoff exists for this approved separation.</p>
      <button type="button" className="session-revoke" disabled={busy} onClick={() => {
        setBusy(true); setMessage('');
        void request<Handoff>(`/api/v1/personnel/employees/${employeeId}/hr-cases/${caseId}/final-pay`,
          { method: 'POST' }).then(setHandoff)
          .catch(e => setMessage(e instanceof Error ? e.message : 'Could not create handoff'))
          .finally(() => setBusy(false));
      }}>Request final-pay handoff</button>
    </div>}
    {rows.map(row => <div className="people-record" key={row.id}>
      <span><strong>{row.status} · Net {money(row.net)}</strong><small>Gross {money(row.gross)} · Deductions {money(row.deductions)}</small>
        <small>{row.settlementGate}</small></span>
      <div className="people-form-actions">
        <button type="button" className="session-revoke" onClick={() => void openStatement(row.id)}>Review statement</button>
        {row.status === 'PENDING' && row.requestedBy !== currentUserId && <>
          <button type="button" className="security-submit" disabled={busy} onClick={() => void decide(row.id, 'approve')}>Approve</button>
          <button type="button" className="session-revoke" disabled={busy} onClick={() => void decide(row.id, 'reject')}>Reject</button>
        </>}
      </div>
    </div>)}
    {statement && <div className="people-subcard">
      <strong>Settlement review statement — {statement.status}</strong>
      <p className="people-muted">Reference: {statement.accountingReference}</p>
      <p>Gross {money(statement.gross)} · Deductions {money(statement.deductions)} · Net {money(statement.net)}</p>
      <p className="people-muted">{statement.justification}</p>
      <p className="people-muted">{statement.disbursementStatus}</p>
    </div>}
    {handoff && rows.length === 0 && <form className="people-inline-form" onSubmit={event => void submit(event)}>
      {fields.map(field => <label className="people-field" key={field.name}><span>{field.label} (PHP)</span>
        <input type="number" min="0" step="0.01" required value={values[field.name]}
          onChange={event => setValues(current => ({ ...current, [field.name]: event.target.value }))} />
      </label>)}
      <label className="people-field"><span>Accounting evidence reference</span><input required maxLength={1000}
        value={accountingReference} onChange={event => setAccountingReference(event.target.value)} /></label>
      <label className="people-field"><span>Verified settlement justification (20–2000 characters)</span>
        <textarea required minLength={20} maxLength={2000} rows={3} value={justification}
          onChange={event => setJustification(event.target.value)} /></label>
      <label><input type="checkbox" checked={attested.leave} onChange={event => setAttested(x => ({...x, leave: event.target.checked}))} /> Leave balance and conversion reviewed</label>
      <label><input type="checkbox" checked={attested.loan} onChange={event => setAttested(x => ({...x, loan: event.target.checked}))} /> Loans and property clearance reviewed</label>
      <label><input type="checkbox" checked={attested.tax} onChange={event => setAttested(x => ({...x, tax: event.target.checked}))} /> Final taxation reviewed</label>
      <label><input type="checkbox" checked={attested.entitlement} onChange={event => setAttested(x => ({...x, entitlement: event.target.checked}))} /> Legal separation-pay eligibility verified</label>
      <button type="submit" className="security-submit" disabled={busy || !Object.values(attested).every(Boolean)}>
        {busy ? 'Submitting…' : 'Submit final pay for independent approval'}
      </button>
    </form>}
    {message && <p role="status" className="people-muted">{message}</p>}
  </div>;
}
