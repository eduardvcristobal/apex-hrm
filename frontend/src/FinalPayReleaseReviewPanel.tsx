import { useEffect, useState, type FormEvent } from 'react';

type LegacyEmployee = { id: string; employeeNo: string; firstName: string; lastName: string };
type Identity = { id: string; personnelEmployeeId: string; payrollEmployeeId: string;
  status: string; requestedBy: string; evidenceReference: string };
type Bank = { id: string; bankCode: string; maskedAccount: string };
type Authorization = { id: string; settlementId: string; amount: number; status: string;
  preparedBy: string; bankTransmissionStatus: string };
type Evidence = { id: string; paymentAuthorizationId: string; bankCode: string; bankReference: string;
  bankValueDate: string; reportedAmount: number; status: string; recordedBy: string;
  reviewedBy: string | null; verificationType: string };
type Reconciliation = { controlStatus: string; internallyConsistent: boolean;
  bankConfirmed: boolean; disbursementEnabled: boolean; explanation: string };
type SourceLedgerReview = { payrollStatus: string; loanStatus: string; leaveStatus: string;
    certificationStatus: string; disbursementEnabled: boolean; lockedPayrollLines: number;
    paidThirteenthMonth: number; payrollWithholding: number; outstandingActiveLoans: number;
    convertibleLeaveDays: number; declaredThirteenthMonth: number;
    declaredOutstandingLoans: number; declaredLeaveConversion: number; requiredActions: string[];
    finalizedDtrDays: number; openDtrDays: number; absentDtrDays: number;
    lateMinutes: number; undertimeMinutes: number; approvedOvertimeMinutes: number;
    lockedBasicPay: number; simpleThirteenthAccrualCandidate: number;
    residualThirteenthCandidate: number; dtrStatus: string;
    thirteenthAccrualStatus: string; withholdingStatus: string };
type SourceSnapshot = { id:string; sourceSha256:string; cutoffGapDays:number;
  compensationGapDays:number; unfinalizedDtrDays:number; completenessStatus:string;
  payoutCertified:boolean; disbursementEnabled:boolean; capturedAt:string };
type SourceDecision = { id:string; snapshotId:string; status:string; requestedBy:string;
    decidedBy:string|null; payoutCertified:boolean; disbursementEnabled:boolean };
type SourceFreshness = { reviewStatus:string; current:boolean; latest:boolean;
    capturedSha256:string; currentSha256:string; payoutCertified:boolean };
type Page<T> = { content: T[] };
const peso = (amount: number) => new Intl.NumberFormat('en-PH',
  { style: 'currency', currency: 'PHP' }).format(amount);

export function FinalPayReleaseReviewPanel({ personnelEmployeeId, employeeNumber, settlementId,
  currentUserId, request }: {
    personnelEmployeeId: string; employeeNumber: string; settlementId: string; currentUserId: string;
    request: <T>(path: string, init?: RequestInit) => Promise<T>;
  }) {
  const [people, setPeople] = useState<LegacyEmployee[]>([]);
  const [payrollEmployeeId, setPayrollEmployeeId] = useState('');
  const [evidence, setEvidence] = useState('');
  const [link, setLink] = useState<Identity | null>(null);
  const [accounts, setAccounts] = useState<Bank[]>([]);
  const [bankId, setBankId] = useState('');
  const [reconciliationReference, setReconciliationReference] = useState('');
  const [authorization, setAuthorization] = useState<Authorization | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [evidenceRow, setEvidenceRow] = useState<Evidence | null>(null);
  const [bankCode, setBankCode] = useState('');
  const [bankReference, setBankReference] = useState('');
  const [bankValueDate, setBankValueDate] = useState('');
  const [evidenceDocumentReference, setEvidenceDocumentReference] = useState('');
  const [reportedAmount, setReportedAmount] = useState('');
  const [reconciliation, setReconciliation] = useState<Reconciliation | null>(null);
  const [sourceLedger, setSourceLedger] = useState<SourceLedgerReview | null>(null);
  const [snapshots, setSnapshots] = useState<SourceSnapshot[]>([]);
  const [sourceDecisions, setSourceDecisions] = useState<SourceDecision[]>([]);
  const [sourceFreshness, setSourceFreshness] = useState<SourceFreshness | null>(null);
  const [sourceEvidenceRef, setSourceEvidenceRef] = useState('');
  const [busy, setBusy] = useState(false);
  const identityPath = '/api/v1/payroll/identity-links';
  const paymentPath = '/api/v1/payroll/final-pay/payment-authorizations';

  useEffect(() => {
    let active = true;
    Promise.all([
      request<Page<LegacyEmployee>>('/api/payroll/employees?size=1000&sort=employeeNo,asc'),
      request<Identity[]>(`${identityPath}?personnelEmployeeId=${personnelEmployeeId}`),
      request<Authorization[]>(`${paymentPath}?settlementId=${settlementId}`)
    ]).then(([employees, links, authorizations]) => {
      if (!active) return;
      setPeople(employees.content);
      setPayrollEmployeeId(employees.content.find(p => p.employeeNo === employeeNumber)?.id || '');
      setLink(links[0] || null);
      setAuthorization(authorizations[0] || null);
    }).catch(e => { if(active)setError(e instanceof Error ? e.message : 'Unable to load final-pay authorization'); });
    return () => {active = false;};
    // Stable identifiers, not the parent view's request-function identity, govern refreshes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [personnelEmployeeId, employeeNumber, settlementId]);

  useEffect(() => {
    if (link?.status !== 'APPROVED') return;
    let active = true;
    request<Bank[]>(`${paymentPath}/eligible-accounts?personnelEmployeeId=${personnelEmployeeId}`)
      .then(rows => {if(active){setAccounts(rows);setBankId(rows[0]?.id || '');}})
      .catch(e => {if(active)setError(e instanceof Error ? e.message : 'Unable to load masked bank accounts');});
    return () => {active = false;};
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [link?.status, personnelEmployeeId]);

  useEffect(() => {
    if (authorization?.status !== 'AUTHORIZED') return;
    let active = true;
    request<Evidence[]>(`/api/v1/payroll/final-pay/bank-evidence?paymentAuthorizationId=${authorization.id}`)
      .then(rows => {if(active){setEvidenceRow(rows[0] || null);setReportedAmount(String(authorization.amount));}})
      .catch(e => {if(active)setError(e instanceof Error ? e.message : 'Unable to review external bank evidence');});
    return () => {active=false;};
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authorization?.id, authorization?.status]);

  useEffect(() => {
    let active = true;
    request<SourceLedgerReview>(`/api/v1/payroll/final-pay/source-ledger/settlements/${settlementId}`)
      .then(value => {if (active) setSourceLedger(value);})
      .catch(() => {if (active) setSourceLedger(null);});
    return () => {active = false;};
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settlementId, link?.status]);

  useEffect(() => {
    let active = true;
    request<Reconciliation>(`/api/v1/payroll/final-pay/reconciliation/settlements/${settlementId}`)
      .then(value => { if(active) setReconciliation(value); })
      .catch(() => { if(active) setReconciliation(null); });
    return () => { active=false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settlementId, link?.status, authorization?.status, evidenceRow?.status]);

  useEffect(() => {
    let active=true;
    request<SourceSnapshot[]>(`/api/v1/payroll/final-pay/source-snapshots/settlements/${settlementId}`)
      .then(rows=>{if(active)setSnapshots(rows);})
      .catch(()=>{if(active)setSnapshots([]);});
    return ()=>{active=false;};
    // eslint-disable-next-line react-hooks/exhaustive-deps
  },[settlementId,link?.status]);

  useEffect(()=>{
    let active=true;
    request<SourceDecision[]>(`/api/v1/payroll/final-pay/source-reviews?settlementId=${settlementId}`)
      .then(rows=>{if(active)setSourceDecisions(rows);})
      .catch(()=>{if(active)setSourceDecisions([]);});
    return ()=>{active=false;};
    // eslint-disable-next-line react-hooks/exhaustive-deps
  },[settlementId,snapshots.length,link?.status]);

  useEffect(()=>{
    let active=true;
    if(snapshots.length===0){setSourceFreshness(null);return;}
    request<SourceFreshness>(
      `/api/v1/payroll/final-pay/source-reviews/snapshots/${snapshots[0].id}/freshness`)
      .then(result=>{if(active)setSourceFreshness(result);})
      .catch(()=>{if(active)setSourceFreshness(null);});
    return ()=>{active=false;};
    // eslint-disable-next-line react-hooks/exhaustive-deps
  },[snapshots[0]?.id,settlementId]);

  async function submitSourceReview(){
    const latest=snapshots[0];
    if(!latest)return;
    await execute(async()=>{
      const created=await request<SourceDecision>('/api/v1/payroll/final-pay/source-reviews',{
        method:'POST',body:JSON.stringify({snapshotId:latest.id,evidenceReference:sourceEvidenceRef})
      });
      setSourceDecisions(rows=>[created,...rows]);
      setNotice('Source completeness review requested. A different officer must decide; no payout is authorized.');
    });
  }
  async function decideSourceReview(id:string,decision:'approve'|'reject'){
    await execute(async()=>{
      const updated=await request<SourceDecision>(
        `/api/v1/payroll/final-pay/source-reviews/${id}/${decision}`,{method:'POST'});
      setSourceDecisions(rows=>rows.map(item=>item.id===id?updated:item));
      setNotice('Independent source-only decision recorded. Final-pay and bank approval are separate.');
    });
  }

  async function captureSourceSnapshot(){
    await execute(async()=>{
      const saved=await request<SourceSnapshot>(
        `/api/v1/payroll/final-pay/source-snapshots/settlements/${settlementId}`,
        {method:'POST'});
      setSnapshots(rows=>[saved,...rows]);
      setNotice('Source evidence captured immutably. This is not final-pay or bank certification.');
    });
  }
  async function execute(operation: () => Promise<void>) {
    setBusy(true);setError('');setNotice('');
    try{await operation();}
    catch(e){setError(e instanceof Error ? e.message : 'Action failed');}
    finally{setBusy(false);}
  }

  const matched = people.find(e => e.id === payrollEmployeeId);
  async function linkIdentity(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await execute(async () => {
      const created = await request<Identity>(identityPath,{
        method:'POST',body:JSON.stringify({personnelEmployeeId,payrollEmployeeId,evidenceReference:evidence})
      });
      setLink(created);
      setNotice('Identity link requested. A different payroll officer must verify the employee records.');
    });
  }
  async function decideLink(action: 'approve'|'reject') {
    if (!link)return;
    await execute(async()=>{
      const updated = await request<Identity>(`${identityPath}/${link.id}/${action}`,{method:'POST'});
      setLink(updated);
      setNotice('Identity review recorded.');
    });
  }
  async function prepare(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await execute(async()=>{
      const result = await request<Authorization>(paymentPath,{
        method:'POST',body:JSON.stringify({
          settlementId,bankAccountId:bankId,sourceReconciliationReference:reconciliationReference
        })
      });
      setAuthorization(result);
      setNotice('Payment instruction prepared; it has not been sent to a bank.');
    });
  }
  async function decidePayment(action:'authorize'|'cancel') {
    if (!authorization)return;
    await execute(async()=>{
      const result=await request<Authorization>(`${paymentPath}/${authorization.id}/${action}`,{method:'POST'});
      setAuthorization(result);
      setNotice('Authorization decision recorded. There is no automatic fund transfer or bank confirmation.');
    });
  }

  async function recordBankEvidence(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!authorization) return;
    await execute(async () => {
      const row=await request<Evidence>('/api/v1/payroll/final-pay/bank-evidence',{
        method:'POST',body:JSON.stringify({
          paymentAuthorizationId: authorization.id, bankCode, bankReference,
          bankValueDate, evidenceDocumentReference, reportedAmount: Number(reportedAmount)
        })
      });
      setEvidenceRow(row);
      setNotice('External bank evidence was recorded, not independently bank-verified.');
    });
  }
  async function reviewBankEvidence(action:'review'|'reject') {
    if (!evidenceRow)return;
    await execute(async()=>{
      const row=await request<Evidence>(`/api/v1/payroll/final-pay/bank-evidence/${evidenceRow.id}/${action}`,
        {method:'POST'});
      setEvidenceRow(row);
      setNotice('Manual evidence decision recorded. No automatic transfer or bank confirmation exists.');
    });
  }

  return <div className="people-subcard">
    <div className="people-card-heading"><div><p className="eyebrow">PAYMENT RELEASE CONTROL</p>
      <h4>Verify employee identity and prepare bank authorization</h4></div></div>
    <p className="people-muted">This workflow prepares an instruction only. Independent approval is required; no transfer, official payslip or bank reconciliation is generated.</p>
    {!link && <form className="people-inline-form" onSubmit={event=>void linkIdentity(event)}>
      <label className="people-field"><span>Legacy payroll employee</span>
        <select value={payrollEmployeeId} onChange={event=>setPayrollEmployeeId(event.target.value)} required>
          <option value="" disabled>Select matching employee</option>
          {people.map(person=><option key={person.id} value={person.id}>
            {person.employeeNo} · {person.firstName} {person.lastName}</option>)}
        </select>
      </label>
      {matched && <p className="people-muted">Selected payroll record: {matched.employeeNo} · {matched.firstName} {matched.lastName}. The server also verifies company, legal names, and hire date.</p>}
      <label className="people-field"><span>Independent identity evidence reference</span>
        <input value={evidence} onChange={event=>setEvidence(event.target.value)}
          minLength={20} maxLength={1000} required placeholder="Verified employee masterfile and HR case reference" />
      </label>
      <button type="submit" className="security-submit" disabled={busy || evidence.trim().length<20 || !payrollEmployeeId}>
        Request employee identity verification
      </button>
    </form>}
    {link && <div className="people-record"><span><strong>Employee identity: {link.status}</strong>
      <small>Payroll record: {link.payrollEmployeeId}</small></span>
      {link.status==='PENDING' && link.requestedBy!==currentUserId && <div className="people-form-actions">
        <button type="button" className="security-submit" disabled={busy} onClick={()=>void decideLink('approve')}>Verify identity</button>
        <button type="button" className="session-revoke" disabled={busy} onClick={()=>void decideLink('reject')}>Reject link</button>
      </div>}
    </div>}
    {link?.status === 'APPROVED' && !authorization && <form className="people-inline-form" onSubmit={event=>void prepare(event)}>
      <label className="people-field"><span>Verified primary bank account</span>
        <select value={bankId} onChange={event=>setBankId(event.target.value)} required>
          <option value="" disabled>Select primary bank account</option>
          {accounts.map(account=><option key={account.id} value={account.id}>
            {account.bankCode} · {account.maskedAccount}</option>)}
        </select>
      </label>
      <label className="people-field"><span>Bank and financial reconciliation evidence</span>
        <input required minLength={20} maxLength={1000} value={reconciliationReference}
          onChange={event=>setReconciliationReference(event.target.value)}
          placeholder="Signed bank validation and final-payment evidence reference" />
      </label>
      <button type="submit" className="security-submit" disabled={busy || !bankId || reconciliationReference.trim().length<20}>
        Prepare payment instruction (no transfer)
      </button>
    </form>}
    {authorization && <div className="people-record"><span>
      <strong>Payment authorization: {authorization.status} · {peso(authorization.amount)}</strong>
      <small>{authorization.bankTransmissionStatus}</small></span>
      {authorization.status==='PREPARED' && authorization.preparedBy!==currentUserId && <div className="people-form-actions">
        <button type="button" className="security-submit" disabled={busy} onClick={()=>void decidePayment('authorize')}>Authorize instruction</button>
        <button type="button" className="session-revoke" disabled={busy} onClick={()=>void decidePayment('cancel')}>Cancel instruction</button>
      </div>}
    </div>}
    {authorization?.status === 'AUTHORIZED' && !evidenceRow && <form className="people-inline-form"
      onSubmit={event => void recordBankEvidence(event)}>
      <h4>Record external bank evidence (manual review only)</h4>
      <label className="people-field"><span>Bank code</span><input required maxLength={50} value={bankCode}
        onChange={event=>setBankCode(event.target.value)} /></label>
      <label className="people-field"><span>External bank reference (unique)</span>
        <input required minLength={6} maxLength={160} value={bankReference}
          onChange={event=>setBankReference(event.target.value)} /></label>
      <label className="people-field"><span>Bank value date</span>
        <input required type="date" max={new Date().toISOString().slice(0,10)} value={bankValueDate}
          onChange={event=>setBankValueDate(event.target.value)} /></label>
      <label className="people-field"><span>Supporting evidence reference</span>
        <input required minLength={20} maxLength={1000} value={evidenceDocumentReference}
          onChange={event=>setEvidenceDocumentReference(event.target.value)} /></label>
      <label className="people-field"><span>Reported amount (PHP)</span>
        <input required type="number" min="0.01" step="0.01" value={reportedAmount}
          onChange={event=>setReportedAmount(event.target.value)} /></label>
      <button className="security-submit" disabled={busy || !bankReference || evidenceDocumentReference.trim().length<20}
        type="submit">Record reported external payment evidence</button>
    </form>}
    {evidenceRow && <div className="people-record"><span><strong>Bank evidence: {evidenceRow.status}</strong>
      <small>{evidenceRow.bankCode} · Reference {evidenceRow.bankReference} · {peso(evidenceRow.reportedAmount)}</small>
      <small>{evidenceRow.verificationType}</small></span>
      {evidenceRow.status === 'SUBMITTED' && evidenceRow.recordedBy !== currentUserId && <div className="people-form-actions">
        <button type="button" className="security-submit" disabled={busy}
          onClick={()=>void reviewBankEvidence('review')}>Independently review evidence</button>
        <button type="button" className="session-revoke" disabled={busy}
          onClick={()=>void reviewBankEvidence('reject')}>Reject evidence</button>
      </div>}
    </div>}
    <section className="people-subcard" aria-label="Final-pay source-ledger review">
      <h4>Source-ledger reconciliation</h4>
      <p className="people-muted"><strong>{sourceLedger?.certificationStatus || 'Awaiting source-ledger review'}</strong> · No automatic financial certification</p>
      {sourceLedger && <>
        <p className="people-muted">Locked payroll lines: {sourceLedger.lockedPayrollLines} · Previous 13th-month paid: {peso(sourceLedger.paidThirteenthMonth)} · Recorded withholding: {peso(sourceLedger.payrollWithholding)}</p>
        <p className="people-muted">Active loan balance: {peso(sourceLedger.outstandingActiveLoans)} · Settlement loan deduction: {peso(sourceLedger.declaredOutstandingLoans)}</p>
        <p className="people-muted">Convertible leave: {sourceLedger.convertibleLeaveDays} days · Claimed leave conversion: {peso(sourceLedger.declaredLeaveConversion)}</p>
        <p className="people-muted">Payroll: {sourceLedger.payrollStatus.replaceAll('_',' ')} · Loan: {sourceLedger.loanStatus.replaceAll('_',' ')} · Leave: {sourceLedger.leaveStatus.replaceAll('_',' ')}</p>
        <p className="people-muted">DTR: {sourceLedger.dtrStatus.replaceAll('_',' ')} · Finalized days: {sourceLedger.finalizedDtrDays} · Open days: {sourceLedger.openDtrDays} · Absences: {sourceLedger.absentDtrDays}</p>
        <p className="people-muted">Late: {sourceLedger.lateMinutes} min · Undertime: {sourceLedger.undertimeMinutes} min · Approved overtime: {sourceLedger.approvedOvertimeMinutes} min</p>
        <p className="people-muted">Locked BASIC: {peso(sourceLedger.lockedBasicPay)} · 13th-month accrual diagnostic: {peso(sourceLedger.simpleThirteenthAccrualCandidate)} · Unpaid diagnostic: {peso(sourceLedger.residualThirteenthCandidate)}</p>
        <p className="people-muted">13th-month: {sourceLedger.thirteenthAccrualStatus.replaceAll('_',' ')} · Withholding: {sourceLedger.withholdingStatus.replaceAll('_',' ')}</p>
        <p className="people-muted">These values are source-ledger diagnostics only; incomplete cutoffs, applicable labor rules, DTR adjustments and annualized BIR withholding still require certification.</p>
        <ul>{sourceLedger.requiredActions.map(action => <li key={action}>{action}</li>)}</ul>
      </>}
    </section>
    <section className="people-subcard" aria-label="Final-pay immutable source snapshots">
      <h4>Immutable source snapshot and cutoff completeness</h4>
      <p className="people-muted">Captures a SHA-256 fingerprint of available payroll, DTR, compensation, loan and leave evidence. Capture is append-only; no snapshot authorizes payment.</p>
      <button type="button" className="security-submit" disabled={busy || link?.status!=='APPROVED'}
        onClick={()=>void captureSourceSnapshot()}>Capture financial source snapshot</button>
      {snapshots.length===0 && <p className="people-muted">No source snapshots captured.</p>}
      {sourceFreshness && <p className="people-muted" role="status">
        Current source fingerprint: <strong>{sourceFreshness.reviewStatus.replaceAll('_',' ')}</strong>
        {' · '}Matching current records: {sourceFreshness.current?'Yes':'No'}
        {' · '}Latest capture: {sourceFreshness.latest?'Yes':'No'}
      </p>}
      {snapshots.length>0 && <div className="people-inline-form">
        <label className="people-field"><span>Independent source-review evidence reference</span>
          <input value={sourceEvidenceRef} onChange={event=>setSourceEvidenceRef(event.target.value)}
            minLength={20} maxLength={1000} placeholder="Checked payroll periods, DTR and compensation source records"/>
        </label>
        <button type="button" className="security-submit"
          disabled={busy || sourceEvidenceRef.trim().length<20 || !sourceFreshness?.current || !sourceFreshness?.latest ||
            sourceDecisions.some(item=>item.snapshotId===snapshots[0].id)}
          onClick={()=>void submitSourceReview()}>Request independent source review</button>
      </div>}
      {sourceDecisions.slice(0,5).map(item=><div className="people-record" key={item.id}><span>
        <strong>Source review: {item.status.replaceAll('_',' ')}</strong>
        <small>Source only · Financial certification: No · Disbursement enabled: No</small>
        {item.status==='PENDING' && <small>Awaiting independent officer; incomplete or stale snapshots cannot be approved.</small>}
      </span>{item.status==='PENDING' && item.requestedBy!==currentUserId && <div className="people-form-actions">
        <button type="button" className="security-submit" disabled={busy || sourceFreshness?.reviewStatus!=='READY_FOR_INDEPENDENT_REVIEW'}
          onClick={()=>void decideSourceReview(item.id,'approve')}>Review source completeness</button>
        <button type="button" className="session-revoke" disabled={busy}
          onClick={()=>void decideSourceReview(item.id,'reject')}>Reject source evidence</button>
      </div>}</div>)}
      {snapshots.slice(0,5).map(item=><div className="people-record" key={item.id}><span>
        <strong>{item.completenessStatus.replaceAll('_',' ')}</strong>
        <small>Missing payroll cutoff days: {item.cutoffGapDays} · Missing compensation days: {item.compensationGapDays} · Open DTR: {item.unfinalizedDtrDays}</small>
        <small>SHA-256: {item.sourceSha256}</small>
        <small>Recorded {item.capturedAt} · Financial certification: No · Bank payout: Disabled</small>
      </span></div>)}
    </section>
    <section className="people-subcard">
      <h4>Final-pay financial certification preflight</h4>
      <p className="people-muted"><strong>{reconciliation?.controlStatus.replaceAll('_', ' ') || 'Loading certification controls'}</strong></p>
      <p className="people-muted">{reconciliation?.explanation || 'Financial evidence is being assessed.'}</p>
      <p className="people-muted">
        Internal consistency: {reconciliation?.internallyConsistent ? 'Reviewed' : 'Pending'}
        {' · '}Bank independently confirmed: {reconciliation?.bankConfirmed ? 'Yes' : 'No'}
        {' · '}Disbursement enabled: {reconciliation?.disbursementEnabled ? 'Yes' : 'No'}
      </p>
      <p className="people-muted">Manual bank evidence is not proof of payment. Actual transfer, posted settlement and bank reconciliation remain disabled.</p>
    </section>
    {error && <p role="alert" className="people-muted">{error}</p>}
    {notice && <p role="status" className="people-muted">{notice}</p>}
  </div>;
}
