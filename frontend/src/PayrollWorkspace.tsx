import { useCallback, useEffect, useMemo, useState } from 'react';

type Page<T> = { content: T[] };
type PayPeriod = { id: string; payGroupId: string; periodOfMonth: number; startDate: string; endDate: string; payDate: string; status: string };
type PayGroup = { id: string; companyId: string; code: string; name: string; frequency: string };
type Run = { id: string; payPeriodId: string; payGroupId: string; runType: string; status: string; computedAt: string | null; approvedAt: string | null; lockedAt: string | null; paidAt: string | null; remarks: string | null; employeeCount: number; totalGross: number; totalDeductions: number; totalNet: number; totalEmployerCost: number };
type PayrollEmployee = { id: string; runId: string; employeeId: string; gross: number; taxableIncome: number; regularTaxableIncome: number; supplementaryTaxableIncome: number; totalDeductions: number; netPay: number; employerCost: number; warnings: string | null };
type Employee = { id: string; employeeNo: string; firstName: string; lastName: string; separationDate: string | null };
type PayrollLine = { id: string; payrollEmployeeId: string; employeeId: string; itemCode: string; description: string; category: string; quantity: number | null; rate: number | null; amount: number; taxable: boolean };
type BankTemplate = { id: string; bankCode: string; name: string };
type BankFile = { id: string; runId: string; bankTemplateId: string; fileName: string; recoverySourceFileId: string | null; recordCount: number; totalAmount: number };
type BankCreditResult = { id: string; employeeNo: string; amount: number; creditStatus: 'ACCEPTED' | 'REJECTED'; bankReference: string };
type BankSettlement = { id: string; runId: string; bankFileId: string; bankReference: string; acknowledgedAmount: number; evidenceReference: string; status: 'RECORDED' | 'CONFIRMED' | 'REJECTED' | 'REVERSED'; recordedBy: string; confirmedBy: string | null; exceptionReason: string | null; exceptionEvidenceReference: string | null };
type SettlementSummary = { expected: number; confirmed: number; outstanding: number; status: string; acknowledgments: BankSettlement[] };

const money = (amount: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP' }).format(Number(amount || 0));
const date = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${value}T12:00:00`));
const stages = ['DRAFT', 'COMPUTED', 'REVIEWED', 'APPROVED', 'LOCKED', 'PAID'];

export function PayrollWorkspace({ token, onOpenSecurity }: { token: string; onOpenSecurity: () => void }) {
  const [periods, setPeriods] = useState<PayPeriod[]>([]);
  const [groups, setGroups] = useState<PayGroup[]>([]);
  const [runs, setRuns] = useState<Run[]>([]);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [selected, setSelected] = useState<Run | null>(null);
  const [runEmployees, setRunEmployees] = useState<PayrollEmployee[]>([]);
  const [lines, setLines] = useState<PayrollLine[]>([]);
  const [bankTemplates, setBankTemplates] = useState<BankTemplate[]>([]);
  const [bankFiles, setBankFiles] = useState<BankFile[]>([]);
  const [bankOutcomes, setBankOutcomes] = useState<Record<string, BankCreditResult[]>>({});
  const [settlementSummary, setSettlementSummary] = useState<SettlementSummary | null>(null);
  const [bankTemplateId, setBankTemplateId] = useState('');
  const [bankReference, setBankReference] = useState('');
  const [evidenceReference, setEvidenceReference] = useState('');
  const [bankResponseCsv, setBankResponseCsv] = useState('');
  const [exceptionReason, setExceptionReason] = useState('');
  const [exceptionEvidence, setExceptionEvidence] = useState('');
  const [periodId, setPeriodId] = useState('');
  const [finalPayEmployeeId, setFinalPayEmployeeId] = useState('');
  const [runType, setRunType] = useState('REGULAR');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [mfaVerified, setMfaVerified] = useState<boolean | null>(null);
  const [certificateYear, setCertificateYear] = useState(new Date().getFullYear());
  type CertificateRow = { employeeId: string; employeeNo: string; employeeName: string; employeeTin: string | null; employeeAddress: string | null; birthDate: string | null; employerName: string | null; employerTin: string | null; employerAddress: string | null; employerRdo: string | null; priorEmployerName: string | null; priorEmployerCompensation: number; priorEmployerTaxableCompensation: number; priorEmployerNonTaxableCompensation: number; priorEmployerTaxWithheld: number; grossCompensation: number; nonTaxableCompensation: number; taxableCompensation: number; withholdingTax: number; missingFields: string[]; readyForReview: boolean };
  const [certificateRows, setCertificateRows] = useState<CertificateRow[]>([]);
  const [certificateLoading, setCertificateLoading] = useState(false);
  const [certificateEmployeeId, setCertificateEmployeeId] = useState('');

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    const response = await fetch(path, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers } });
    if (!response.ok) {
      let message = `Request failed (${response.status}).`;
      try { const body = await response.json() as { message?: string; detail?: string }; message = body.detail || body.message || message; } catch { /* response has no JSON body */ }
      if (response.status === 401) message = 'Your sign-in session has expired. Please sign in again.';
      if (response.status === 403) message = 'Payroll access requires a workspace owner or payroll officer account and an MFA-verified session.';
      throw new Error(message);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }, [token]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [periodPage, groupPage, runPage, employeePage, templatePage] = await Promise.all([
        request<Page<PayPeriod>>('/api/pay-periods?size=500&sort=startDate,desc'),
        request<Page<PayGroup>>('/api/pay-groups?size=200&sort=name,asc'),
        request<Page<Run>>('/api/payroll-runs?size=500&sort=createdAt,desc'),
        request<Page<Employee>>('/api/payroll/employees?size=1000&sort=employeeNo,asc'),
        request<Page<BankTemplate>>('/api/bank-templates?size=200&sort=bankCode,asc'),
      ]);
      setPeriods(periodPage.content); setGroups(groupPage.content); setRuns(runPage.content); setEmployees(employeePage.content);
      setBankTemplates(templatePage.content);
      setBankTemplateId(current => current || templatePage.content[0]?.id || '');
      setPeriodId(current => current || periodPage.content.find(period => period.status === 'OPEN')?.id || '');
      setSelected(current => current ? runPage.content.find(run => run.id === current.id) || null : null);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load payroll.'); }
    finally { setLoading(false); }
  }, [request]);

  useEffect(() => {
    void request<{ enabled: boolean; tokenVerified: boolean }>('/api/v1/security/mfa').then(status => setMfaVerified(status.tokenVerified)).catch(() => setMfaVerified(false));
  }, [request]);
  useEffect(() => { void load(); }, [load]);

  const groupById = useMemo(() => new Map(groups.map(group => [group.id, group])), [groups]);
  const periodById = useMemo(() => new Map(periods.map(period => [period.id, period])), [periods]);
  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees]);
  const selectedPeriod = selected ? periodById.get(selected.payPeriodId) : null;
  const openPeriods = periods.filter(period => period.status === 'OPEN');
  const separatedEmployees = employees.filter(employee => employee.separationDate);

  async function selectRun(run: Run) {
    setSelected(run); setError('');
    setBankResponseCsv(''); setBankReference(''); setEvidenceReference('');
    setExceptionReason(''); setExceptionEvidence('');
    try {
      const [employeeRows, lineRows, files, summary] = await Promise.all([
        request<PayrollEmployee[]>(`/api/payroll/runs/${run.id}/employees`),
        request<PayrollLine[]>(`/api/payroll/runs/${run.id}/lines`),
        request<BankFile[]>(`/api/payroll/runs/${run.id}/bank-files`),
        request<SettlementSummary>(`/api/payroll/runs/${run.id}/settlements`),
      ]);
      const outcomes = await Promise.all(files.map(file => request<BankCreditResult[]>(`/api/payroll/bank-results/${file.id}`)));
      setRunEmployees(employeeRows); setLines(lineRows);
      setBankFiles(files); setSettlementSummary(summary);
      setBankOutcomes(Object.fromEntries(files.map((file, index) => [file.id, outcomes[index]])));
    } catch (reason) { setRunEmployees([]); setLines([]); setBankFiles([]); setBankOutcomes({}); setSettlementSummary(null); setError(reason instanceof Error ? reason.message : 'Could not load payroll run details.'); }
  }

  async function createRun() {
    if (!periodId) return;
    setBusy('create'); setError(''); setNotice('');
    try {
      const run = await request<Run>('/api/payroll/runs', { method: 'POST', body: JSON.stringify({ payPeriodId: periodId, runType }) });
      setNotice('Payroll run created. Compute it to build employee pay and statutory deductions.');
      await load(); await selectRun(run);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not create payroll run.'); }
    finally { setBusy(''); }
  }

  async function loadCertificate() {
    setCertificateLoading(true); setError('');
    try {
      const rows = await request<typeof certificateRows>(`/api/payroll/reports/annual-tax-certificate?year=${certificateYear}`);
      setCertificateRows(rows);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load annual tax certificate data.'); }
    finally { setCertificateLoading(false); }
  }

  async function downloadCertificate() {
    setBusy('certificate'); setError('');
    try {
      const response = await fetch(`/api/payroll/reports/annual-tax-certificate.csv?year=${certificateYear}${certificateEmployeeId ? `&employeeId=${certificateEmployeeId}` : ''}`, { headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error(`Could not export certificate data (${response.status}).`);
      const blob = await response.blob(); const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `annual-tax-certificate-${certificateYear}.csv`; anchor.click(); URL.revokeObjectURL(url);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not export annual tax certificate data.'); }
    finally { setBusy(''); }
  }

  async function printCertificate() {
    if (!certificateEmployeeId) { setError('Choose an employee before preparing a printable annual certificate.'); return; }
    setBusy('certificate-print'); setError('');
    try {
      const row = await request<(typeof certificateRows)[number]>(`/api/payroll/reports/annual-tax-certificate?year=${certificateYear}&employeeId=${certificateEmployeeId}`);
      const popup = window.open('', '_blank', 'noopener,noreferrer');
      if (!popup) throw new Error('Allow pop-ups to open the printable annual certificate.');
      const escape = (value: unknown) => String(value ?? '—').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]!));
      popup.document.write(`<!doctype html><html><head><title>Annual compensation certificate ${certificateYear}</title><style>body{font:14px Arial,sans-serif;color:#111;margin:32px}h1{font-size:20px}h2{font-size:15px;border-bottom:1px solid #555;padding-bottom:5px;margin-top:22px}table{width:100%;border-collapse:collapse}td,th{border:1px solid #bbb;text-align:left;padding:7px}.amount{text-align:right}.notice{background:#fff3cd;padding:10px}@media print{button{display:none}}</style></head><body><h1>Annual compensation and tax withholding summary · ${certificateYear}</h1><p class="notice">Preparation summary only. This is not an official BIR Form 2316 and must be reconciled, reviewed, and transferred to the current official form before signing or filing.</p><h2>Employee</h2><table><tr><th>Name</th><td>${escape(row.employeeName)}</td><th>TIN</th><td>${escape(row.employeeTin)}</td></tr><tr><th>Address</th><td colspan="3">${escape(row.employeeAddress)}</td></tr><tr><th>Birth date</th><td>${escape(row.birthDate)}</td><th>Employee number</th><td>${escape(row.employeeNo)}</td></tr></table><h2>Employer</h2><table><tr><th>Name</th><td>${escape(row.employerName)}</td><th>TIN</th><td>${escape(row.employerTin)}</td></tr><tr><th>Address</th><td>${escape(row.employerAddress)}</td><th>RDO</th><td>${escape(row.employerRdo)}</td></tr></table><h2>Annual totals</h2><table><tr><th>Gross compensation</th><td class="amount">${money(row.grossCompensation)}</td></tr><tr><th>Non-taxable compensation</th><td class="amount">${money(row.nonTaxableCompensation)}</td></tr><tr><th>Taxable compensation</th><td class="amount">${money(row.taxableCompensation)}</td></tr><tr><th>Tax withheld</th><td class="amount">${money(row.withholdingTax)}</td></tr></table>${row.priorEmployerName ? `<h2>Prior employer (reconciled data)</h2><p>${escape(row.priorEmployerName)} · Gross ${money(row.priorEmployerCompensation)} · Taxable ${money(row.priorEmployerTaxableCompensation)} · Non-taxable ${money(row.priorEmployerNonTaxableCompensation)} · Tax withheld ${money(row.priorEmployerTaxWithheld)}</p>` : ''}<h2>Review checklist</h2><p>${row.readyForReview ? 'Required profile fields and payroll data are present for review.' : `<strong>Incomplete:</strong> ${row.missingFields.map(escape).join(', ')}`}</p><p>Employer representative: ____________________ &nbsp; Date: __________</p><p>Employee acknowledgment: ____________________ &nbsp; Date: __________</p><button onclick="window.print()">Print</button></body></html>`);
      popup.document.close();
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not prepare the printable annual certificate.'); }
    finally { setBusy(''); }
  }

  async function createFinalPayRun() {
    if (!periodId || !finalPayEmployeeId) return;
    setBusy('final-pay'); setError(''); setNotice('');
    try {
      const run = await request<Run>('/api/payroll/final-pay', {
        method: 'POST', body: JSON.stringify({ payPeriodId: periodId, employeeId: finalPayEmployeeId }),
      });
      setNotice('Final-pay run created. Compute it after the regular period containing the separation date is locked.');
      await load(); await selectRun(run);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not create final-pay run.'); }
    finally { setBusy(''); }
  }

  async function transition(run: Run, action: string, confirmation?: string) {
    if (confirmation && !window.confirm(confirmation)) return;
    setBusy(action); setError(''); setNotice('');
    try {
      const updated = await request<Run>(`/api/payroll/runs/${run.id}/${action}`, { method: 'POST' });
      setNotice(`Run moved to ${updated.status}.`);
      await load(); await selectRun(updated);
    } catch (reason) { setError(reason instanceof Error ? reason.message : `Could not ${action} this payroll run.`); }
    finally { setBusy(''); }
  }

  async function createBankFile() {
    if (!selected || !bankTemplateId) return;
    setBusy('bank-file'); setError(''); setNotice('');
    try {
      const result = await request<{ file: BankFile; skippedEmployees: string[] }>(
        `/api/payroll/runs/${selected.id}/bank-file`,
        { method: 'POST', body: JSON.stringify({ bankTemplateId }) });
      await downloadBankFile(result.file.id, result.file.fileName);
      setNotice(`Bank export created with ${result.file.recordCount} employees for ${money(result.file.totalAmount)}. It still requires external bank processing and independent reconciliation.`);
      await selectRun(selected);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not create bank export.'); }
    finally { setBusy(''); }
  }

  async function downloadBankFile(id: string, fileName?: string) {
    const response = await fetch(`/api/payroll/bank-files/${id}/download`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw new Error(`Could not download bank export (${response.status}).`);
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url; link.download = fileName || bankFiles.find(file => file.id === id)?.fileName || 'payroll-bank-export.csv';
    link.click(); URL.revokeObjectURL(url);
  }

  async function recordSettlement(file: BankFile, acceptedAmount: number) {
    setBusy(`record-${file.id}`); setError(''); setNotice('');
    try {
      await request<BankSettlement>(`/api/payroll/runs/${file.runId}/settlements`, {
        method: 'POST', body: JSON.stringify({ bankFileId: file.id, acknowledgedAmount: acceptedAmount, bankReference, evidenceReference }),
      });
      setNotice('Bank acknowledgment recorded. A different authorized payroll user must confirm it.');
      if (selected) await selectRun(selected);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not record settlement.'); }
    finally { setBusy(''); }
  }

  async function importBankResults(file: BankFile) {
    setBusy(`import-${file.id}`); setError(''); setNotice('');
    try {
      const summary = await request<{ accepted: number; rejected: number; acceptedAmount: number }>(
        `/api/payroll/bank-results/${file.id}/import`, {
          method: 'POST', body: JSON.stringify({ csv: bankResponseCsv, evidenceReference }),
        });
      setNotice(`Imported bank results: ${summary.accepted} accepted, ${summary.rejected} rejected. Record settlement for the accepted amount only.`);
      setBankResponseCsv('');
      if (selected) await selectRun(selected);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not import bank results.'); }
    finally { setBusy(''); }
  }

  async function recoverRejected(file: BankFile) {
    setBusy(`recover-${file.id}`); setError(''); setNotice('');
    try {
      const result = await request<{ file: BankFile; skippedEmployees: string[] }>(
        `/api/payroll/bank-results/${file.id}/recover`, { method: 'POST' });
      await downloadBankFile(result.file.id, result.file.fileName);
      setNotice(`Rejected-only recovery export created with ${result.file.recordCount} employees. Accepted credits were excluded.`);
      if (selected) await selectRun(selected);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not create rejected-credit recovery.'); }
    finally { setBusy(''); }
  }

  async function decideSettlement(item: BankSettlement, action: 'confirm' | 'reject' | 'reverse') {
    setBusy(`${action}-${item.id}`); setError(''); setNotice('');
    try {
      await request<BankSettlement>(`/api/payroll/settlements/${item.id}/${action}`, {
        method: 'POST',
        ...(action === 'confirm' ? {} : { body: JSON.stringify({ reason: exceptionReason, evidenceReference: exceptionEvidence }) }),
      });
      setNotice(action === 'confirm' ? 'Settlement independently confirmed.' : `Settlement ${action} recorded with its evidence trail.`);
      if (selected) await selectRun(selected);
    } catch (reason) { setError(reason instanceof Error ? reason.message : `Could not ${action} settlement.`); }
    finally { setBusy(''); }
  }

  async function downloadRemittance(agency: 'SSS' | 'PHILHEALTH' | 'PAGIBIG') {
    if (!selected) return;
    setBusy(`remittance-${agency}`); setError(''); setNotice('');
    try {
      const response = await fetch(`/api/payroll/runs/${selected.id}/remittance-review?agency=${agency}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!response.ok) throw new Error(`Could not download review register (${response.status}).`);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url; link.download = `${agency.toLowerCase()}-review.csv`; link.click();
      URL.revokeObjectURL(url);
      setNotice(`${agency} internal review register downloaded. It is not an agency submission file.`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not download remittance review.'); }
    finally { setBusy(''); }
  }

  const lifecycleAction: Record<string, { action: string; label: string; confirm?: string }> = {
    DRAFT: { action: 'compute', label: 'Compute payroll' },
    COMPUTED: { action: 'review', label: 'Mark reviewed' },
    REVIEWED: { action: 'approve', label: 'Approve run', confirm: 'Approve this payroll run? This records you as the approver.' },
    APPROVED: { action: 'lock', label: 'Lock & issue payslips', confirm: 'Lock this payroll run? Payslips will be issued and the period’s DTR and pay period will be frozen.' },
    LOCKED: { action: 'pay', label: 'Mark paid', confirm: 'Confirm that this payroll run has been paid?' },
  };
  const action = selected ? lifecycleAction[selected.status] : undefined;

  return <section className="timekeeping-workspace payroll-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">PAYROLL</p><h1>Payroll runs</h1><p>Create payroll for an open pay period, review the calculation, and complete the controlled approval lifecycle.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to use payroll</strong><small>Payroll actions require MFA. Enable and verify an authenticator in account security, then sign in again so this session receives payroll access.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    {error && <div className="platform-alert" role="alert">{error}</div>}{notice && <p className="platform-notice" role="status">{notice}</p>}
    <div className="timekeeping-metrics payroll-metrics"><article><span>PAY PERIODS</span><strong>{periods.length}</strong><small>{openPeriods.length} open</small></article><article><span>PAYROLL RUNS</span><strong>{runs.length}</strong><small>{runs.filter(run => !['PAID'].includes(run.status)).length} in progress</small></article><article><span>PENDING APPROVAL</span><strong>{runs.filter(run => run.status === 'REVIEWED').length}</strong><small>Reviewed runs</small></article><article><span>PAID RUNS</span><strong>{runs.filter(run => run.status === 'PAID').length}</strong><small>Completed</small></article></div>
    <section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">YEAR-END TAX REPORTING</p><h2>2316 preparation register</h2><p>Reconciles locked and paid regular payroll lines and flags missing employee or employer information.</p></div></div><div className="tax-form-grid"><label className="payroll-field">Tax year<input type="number" min="2000" max={new Date().getFullYear() + 1} value={certificateYear} onChange={event => setCertificateYear(Number(event.target.value))} /></label><label className="payroll-field">Employee (for print)<select value={certificateEmployeeId} onChange={event => setCertificateEmployeeId(event.target.value)}><option value="">All employees</option>{employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label><button type="button" className="security-submit" onClick={() => void loadCertificate()} disabled={certificateLoading || mfaVerified === false}>{certificateLoading ? 'Loading…' : 'Load preparation register'}</button><button type="button" className="security-submit" onClick={() => void downloadCertificate()} disabled={busy === 'certificate' || mfaVerified === false}>{busy === 'certificate' ? 'Exporting…' : 'Export CSV for review'}</button><button type="button" className="security-submit" onClick={() => void printCertificate()} disabled={busy === 'certificate-print' || mfaVerified === false || !certificateEmployeeId}>{busy === 'certificate-print' ? 'Preparing…' : 'Print annual summary'}</button></div>{certificateRows.length > 0 && <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>GROSS</th><th>NON-TAXABLE</th><th>TAXABLE</th><th>WITHHELD</th><th>READINESS</th></tr></thead><tbody>{certificateRows.map(row => <tr key={row.employeeId}><td><strong>{row.employeeName}</strong><small>{row.employeeNo} · TIN {row.employeeTin || 'missing'}</small></td><td>{money(row.grossCompensation)}</td><td>{money(row.nonTaxableCompensation)}</td><td>{money(row.taxableCompensation)}</td><td>{money(row.withholdingTax)}</td><td>{row.readyForReview ? 'Ready for review' : row.missingFields.join(', ')}</td></tr>)}</tbody></table></div>}<p className="timekeeping-footnote">The printable annual summary is an internal review aid, not a generated or signed BIR Form 2316. The official form includes statutory exemptions, contributions, de minimis benefits, previous-employer certificate figures, withholding agent information, and required signatures; reconcile these on the current BIR form before use.</p></section>
    <div className="payroll-layout">
      <div className="payroll-create-stack">
      <section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">PAY CYCLE</p><h2>Start a payroll run</h2></div></div>
        <p className="payroll-help">Payroll runs use approved attendance, employee compensation, pay group rules, contributions, taxes, and approved adjustments.</p>
        <label className="payroll-field">Open pay period<select value={periodId} onChange={event => setPeriodId(event.target.value)}><option value="">Select a pay period…</option>{openPeriods.map(period => <option key={period.id} value={period.id}>{date(period.startDate)} – {date(period.endDate)} · Pay {date(period.payDate)} · {groupById.get(period.payGroupId)?.name || 'Pay group'}</option>)}</select></label>
        <label className="payroll-field">Run type<select value={runType} onChange={event => setRunType(event.target.value)}><option value="REGULAR">Regular</option><option value="OFF_CYCLE">Off-cycle</option><option value="THIRTEENTH_MONTH">13th month</option><option value="YEAR_END_ADJUSTMENT">Year-end tax adjustment</option></select></label>
        <button type="button" className="security-submit" onClick={() => void createRun()} disabled={!!busy || loading || !periodId || mfaVerified === false}>{busy === 'create' ? 'Creating…' : '＋ Create payroll run'}</button>
        {!periods.length && !loading && <p className="payroll-empty-note">No pay periods are configured yet. Payroll setup requires a pay group and pay periods.</p>}
      </section>
      <section className="payroll-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">EMPLOYEE EXIT</p><h2>Start final pay</h2></div></div>
        <p className="payroll-help">Select a separated employee and an open pay period in their pay group. The last regular pay period must be locked before computation.</p>
        <label className="payroll-field">Separated employee<select value={finalPayEmployeeId} onChange={event => setFinalPayEmployeeId(event.target.value)}><option value="">Select an employee…</option>{separatedEmployees.map(employee => <option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName} · Separated {date(employee.separationDate!)}</option>)}</select></label>
        <button type="button" className="security-submit" onClick={() => void createFinalPayRun()} disabled={!!busy || loading || !periodId || !finalPayEmployeeId || mfaVerified === false}>{busy === 'final-pay' ? 'Creating…' : '＋ Create final-pay run'}</button>
        {!separatedEmployees.length && !loading && <p className="payroll-empty-note">No employees have a separation date recorded.</p>}
      </section>
      </div>
      <section className="payroll-panel payroll-runs-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">PAYROLL REGISTER</p><h2>Recent runs</h2></div><span>{runs.length} run{runs.length === 1 ? '' : 's'}</span></div>
        {loading ? <div className="timekeeping-empty">Loading payroll records…</div> : runs.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table payroll-table"><thead><tr><th>PAY PERIOD</th><th>PAY GROUP</th><th>TYPE</th><th>STATUS</th><th>EMPLOYEES</th><th>NET PAY</th></tr></thead><tbody>{runs.map(run => { const period = periodById.get(run.payPeriodId); return <tr key={run.id} className={selected?.id === run.id ? 'payroll-selected-row' : ''}><td><button type="button" className="payroll-run-link" onClick={() => void selectRun(run)}>{period ? `${date(period.startDate)} – ${date(period.endDate)}` : 'Period details unavailable'}<small>Pay date {period ? date(period.payDate) : '—'}</small></button></td><td>{groupById.get(run.payGroupId)?.name || '—'}</td><td>{run.runType.replaceAll('_', ' ')}</td><td><span className={`timekeeping-status ${run.status === 'PAID' ? 'open' : run.status === 'DRAFT' ? 'warning' : run.status === 'LOCKED' ? 'locked' : 'open'}`}>{run.status}</span></td><td>{run.employeeCount}</td><td><strong>{money(run.totalNet)}</strong></td></tr>; })}</tbody></table></div> : <div className="timekeeping-empty">No payroll runs yet. Select an open pay period to get started.</div>}
      </section>
    </div>
    {selected && <section className="payroll-panel payroll-detail-panel"><div className="payroll-panel-heading"><div><p className="eyebrow">RUN DETAILS</p><h2>{selected.runType.replaceAll('_', ' ')} payroll</h2><p>{selectedPeriod ? `${date(selectedPeriod.startDate)} – ${date(selectedPeriod.endDate)} · Pay date ${date(selectedPeriod.payDate)}` : 'Pay period'}</p></div><div className="payroll-detail-actions">{action && <button type="button" className="security-submit" onClick={() => void transition(selected, action.action, action.confirm)} disabled={!!busy || mfaVerified === false || (action.action === 'pay' && (!settlementSummary || settlementSummary.expected <= 0 || settlementSummary.confirmed !== settlementSummary.expected))}>{busy === action.action ? 'Saving…' : action.label}</button>}{['SSS', 'PHILHEALTH', 'PAGIBIG'].map(agency => <button key={agency} type="button" className="security-submit" onClick={() => void downloadRemittance(agency as 'SSS' | 'PHILHEALTH' | 'PAGIBIG')} disabled={!!busy || mfaVerified === false}>{busy === `remittance-${agency}` ? 'Downloading…' : `${agency} review CSV`}</button>)}</div></div>
      {action?.action === 'pay' && settlementSummary && (settlementSummary.expected <= 0 || settlementSummary.confirmed !== settlementSummary.expected) && <p className="payroll-help">Mark as paid is available after confirmed bank acknowledgments cover the full payroll net amount.</p>}
      {selected.remarks && <div className="payroll-warning">{selected.remarks}</div>}
      <div className="payroll-lifecycle">{stages.map((stage, index) => <div key={stage} className={stages.indexOf(selected.status) >= index ? 'complete' : ''}><i>{stages.indexOf(selected.status) >= index ? '✓' : index + 1}</i><span>{stage.replaceAll('_', ' ')}</span></div>)}</div>
      <section className="payroll-panel">
        <div className="payroll-panel-heading"><div><p className="eyebrow">BANK SETTLEMENT</p><h2>Payment reconciliation</h2></div><span>{settlementSummary?.status || 'UNSETTLED'}</span></div>
        <p className="payroll-help">Bank files are payment instructions, not proof of payment. Record a bank acknowledgment only after checking the external bank evidence; another payroll administrator must confirm it before this run can be marked paid.</p>
        <div className="timekeeping-metrics payroll-totals"><article><span>EXPECTED NET</span><strong>{money(settlementSummary?.expected || selected.totalNet)}</strong></article><article><span>CONFIRMED</span><strong>{money(settlementSummary?.confirmed || 0)}</strong></article><article><span>OUTSTANDING</span><strong>{money(settlementSummary?.outstanding ?? selected.totalNet)}</strong></article></div>
        {['APPROVED', 'LOCKED'].includes(selected.status) && <div className="tax-form-grid">
          <label className="payroll-field">Bank template<select value={bankTemplateId} onChange={event => setBankTemplateId(event.target.value)}><option value="">Select a bank template</option>{bankTemplates.map(template => <option key={template.id} value={template.id}>{template.bankCode} — {template.name}</option>)}</select></label>
          <button type="button" className="security-submit" onClick={() => void createBankFile()} disabled={!!busy || !bankTemplateId || mfaVerified === false || bankFiles.some(file => (bankOutcomes[file.id]?.length || 0) > 0 || !settlementSummary?.acknowledgments.some(item => item.bankFileId === file.id && item.status === 'REJECTED'))}>{busy === 'bank-file' ? 'Creating export…' : 'Create & download bank export'}</button>
        </div>}
        {bankFiles.map(file => {
          const acknowledgment = settlementSummary?.acknowledgments.find(item => item.bankFileId === file.id);
          const outcomes = bankOutcomes[file.id] || [];
          const acceptedAmount = outcomes.filter(item => item.creditStatus === 'ACCEPTED').reduce((sum, item) => sum + item.amount, 0);
          const rejectedCount = outcomes.filter(item => item.creditStatus === 'REJECTED').length;
          return <article className="payroll-panel" key={file.id}>
            <div className="payroll-panel-heading"><div><p className="eyebrow">{file.recordCount} EMPLOYEES · {money(file.totalAmount)}</p><h3>{file.fileName}</h3></div><button type="button" className="security-submit" onClick={() => void downloadBankFile(file.id)} disabled={!!busy}>Download export</button></div>
            {selected.status === 'LOCKED' && !outcomes.length && <div className="tax-form-grid">
              <label className="payroll-field">Canonical bank result CSV<textarea value={bankResponseCsv} onChange={event => setBankResponseCsv(event.target.value)} rows={5} placeholder={'EMPLOYEE_NO,AMOUNT,STATUS,REFERENCE\nEMP-001,12500.00,ACCEPTED,BANK-REF-001'} /></label>
              <label className="payroll-field">Bank statement evidence reference<input value={evidenceReference} onChange={event => setEvidenceReference(event.target.value)} maxLength={255} placeholder="Statement or transaction record reference" /></label>
              <button type="button" className="security-submit" onClick={() => void importBankResults(file)} disabled={!!busy || !bankResponseCsv.trim() || !evidenceReference.trim() || mfaVerified === false}>{busy === `import-${file.id}` ? 'Importing…' : 'Import bank response'}</button>
            </div>}
            {outcomes.length > 0 && <><p className="payroll-help">Imported outcomes: {outcomes.filter(item => item.creditStatus === 'ACCEPTED').length} accepted ({money(acceptedAmount)}); {rejectedCount} rejected. These results are operator-entered evidence and are not cryptographically verified by APEX-HRM.</p>
              <div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE NO.</th><th>AMOUNT</th><th>RESULT</th><th>BANK REFERENCE</th></tr></thead><tbody>{outcomes.map(item => <tr key={item.id}><td>{item.employeeNo}</td><td>{money(item.amount)}</td><td>{item.creditStatus}</td><td>{item.bankReference}</td></tr>)}</tbody></table></div>
            </>}
            {!acknowledgment && selected.status === 'LOCKED' && acceptedAmount > 0 && <div className="tax-form-grid">
              <label className="payroll-field">Bank acknowledgment/reference<input value={bankReference} onChange={event => setBankReference(event.target.value)} maxLength={120} required /></label>
              <button type="button" className="security-submit" onClick={() => void recordSettlement(file, acceptedAmount)} disabled={!!busy || !bankReference.trim() || !evidenceReference.trim() || mfaVerified === false}>{busy === `record-${file.id}` ? 'Recording…' : `Record accepted credits (${money(acceptedAmount)})`}</button>
            </div>}
            {rejectedCount > 0 && selected.status === 'LOCKED' && <button type="button" className="security-submit" onClick={() => void recoverRejected(file)} disabled={!!busy || mfaVerified === false}>{busy === `recover-${file.id}` ? 'Preparing recovery…' : `Create rejected-only recovery (${rejectedCount})`}</button>}
            {acknowledgment && <><p className="payroll-help">Status: <strong>{acknowledgment.status}</strong> · Amount: {money(acknowledgment.acknowledgedAmount)} · Bank reference: {acknowledgment.bankReference} · Evidence: {acknowledgment.evidenceReference}</p>
              {acknowledgment.exceptionReason && <p className="payroll-warning">{acknowledgment.exceptionReason} · Evidence: {acknowledgment.exceptionEvidenceReference}</p>}
              <div className="tax-form-grid">
                {(acknowledgment.status === 'RECORDED' || acknowledgment.status === 'CONFIRMED') && <><label className="payroll-field">Exception reason<input value={exceptionReason} onChange={event => setExceptionReason(event.target.value)} maxLength={2000} /></label><label className="payroll-field">Exception evidence reference<input value={exceptionEvidence} onChange={event => setExceptionEvidence(event.target.value)} maxLength={255} /></label></>}
                {acknowledgment.status === 'RECORDED' && <><button type="button" className="security-submit" onClick={() => void decideSettlement(acknowledgment, 'confirm')} disabled={!!busy || mfaVerified === false}>{busy === `confirm-${acknowledgment.id}` ? 'Confirming…' : 'Confirm as independent reviewer'}</button><button type="button" className="security-submit" onClick={() => void decideSettlement(acknowledgment, 'reject')} disabled={!!busy || !exceptionReason.trim() || !exceptionEvidence.trim() || mfaVerified === false}>Reject acknowledgment</button></>}
                {acknowledgment.status === 'CONFIRMED' && <button type="button" className="security-submit" onClick={() => void decideSettlement(acknowledgment, 'reverse')} disabled={!!busy || !exceptionReason.trim() || !exceptionEvidence.trim() || mfaVerified === false}>Reverse with evidence</button>}
              </div>
            </>}
          </article>;
        })}
        {!bankFiles.length && <div className="timekeeping-empty">Create a bank export after payroll approval to start reconciliation.</div>}
      </section>
      <div className="timekeeping-metrics payroll-totals"><article><span>GROSS PAY</span><strong>{money(selected.totalGross)}</strong></article><article><span>DEDUCTIONS</span><strong>{money(selected.totalDeductions)}</strong></article><article><span>NET PAY</span><strong>{money(selected.totalNet)}</strong></article><article><span>EMPLOYER COST</span><strong>{money(selected.totalEmployerCost)}</strong></article></div>
      <div className="payroll-panel-heading payroll-register-heading"><div><p className="eyebrow">EMPLOYEE REGISTER</p><h2>Employee calculations</h2></div><span>{runEmployees.length} employees</span></div>
      {runEmployees.length ? <div className="timekeeping-table-wrap"><table className="timekeeping-table payroll-employee-table"><thead><tr><th>EMPLOYEE</th><th>GROSS</th><th>REGULAR TAXABLE</th><th>SUPPLEMENTARY TAXABLE</th><th>TAXABLE TOTAL</th><th>DEDUCTIONS</th><th>NET PAY</th><th>EMPLOYER COST</th></tr></thead><tbody>{runEmployees.map(item => { const employee = employeeById.get(item.employeeId); return <tr key={item.id}><td><strong>{employee ? `${employee.firstName} ${employee.lastName}` : 'Employee'}</strong><small>{employee?.employeeNo || item.employeeId}</small>{item.warnings && <small className="payroll-warning-text">{item.warnings}</small>}</td><td>{money(item.gross)}</td><td>{money(item.regularTaxableIncome)}</td><td>{money(item.supplementaryTaxableIncome)}</td><td>{money(item.taxableIncome)}</td><td>{money(item.totalDeductions)}</td><td><strong>{money(item.netPay)}</strong></td><td>{money(item.employerCost)}</td></tr>; })}</tbody></table></div> : <div className="timekeeping-empty">{selected.status === 'DRAFT' ? 'Compute the run to create employee calculations.' : 'No employees were included in this run.'}</div>}
      {runEmployees.length > 0 && <details className="payroll-lines-details"><summary>View {lines.length} payroll line items</summary><div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE</th><th>CODE</th><th>DESCRIPTION</th><th>CATEGORY</th><th>TAXABLE</th><th>AMOUNT</th></tr></thead><tbody>{lines.map(line => { const employee = employeeById.get(line.employeeId); return <tr key={line.id}><td>{employee ? `${employee.firstName} ${employee.lastName}` : 'Employee'}</td><td>{line.itemCode}</td><td>{line.description}</td><td>{line.category}</td><td>{line.taxable ? 'Yes' : 'No'}</td><td>{money(line.amount)}</td></tr>; })}</tbody></table></div></details>}
      <p className="timekeeping-footnote">The sequence is DRAFT → COMPUTED → REVIEWED → APPROVED → LOCKED → PAID. Locking issues payslips and freezes the regular pay period and included DTR days.</p>
    </section>}
    {selected && !action && selected.status === 'PAID' && <p className="payroll-complete-note">This run is paid and complete.</p>}
    <p className="timekeeping-footnote">Pay groups and pay periods are managed through the payroll setup API. This screen does not create missing pay configuration automatically.</p>
  </section>;
}
