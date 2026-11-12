import { useCallback, useEffect, useMemo, useState } from 'react';
import { Toast } from './Toast';

type Page<T> = { content: T[] };
type PayPeriod = { id: string; payGroupId: string; periodOfMonth: number; startDate: string; endDate: string; payDate: string; status: string };
type PayGroup = { id: string; companyId: string; code: string; name: string; frequency: string };
type Run = { id: string; payPeriodId: string; payGroupId: string; runType: string; status: string; computedAt: string | null; approvedAt: string | null; lockedAt: string | null; paidAt: string | null; remarks: string | null; employeeCount: number; totalGross: number; totalDeductions: number; totalNet: number; totalEmployerCost: number };
type PayrollEmployee = { id: string; runId: string; employeeId: string; gross: number; taxableIncome: number; regularTaxableIncome: number; supplementaryTaxableIncome: number; totalDeductions: number; netPay: number; employerCost: number; warnings: string | null };
type Employee = { id: string; companyId: string; employeeNo: string; firstName: string; lastName: string; separationDate: string | null };
type PayrollLine = { id: string; payrollEmployeeId: string; employeeId: string; itemCode: string; description: string; category: string; quantity: number | null; rate: number | null; amount: number; taxable: boolean };
type BankTemplate = { id: string; bankCode: string; name: string };
type BankFile = { id: string; runId: string; bankTemplateId: string; fileName: string; recoverySourceFileId: string | null; recordCount: number; totalAmount: number };
type BankCreditResult = { id: string; employeeNo: string; amount: number; creditStatus: 'ACCEPTED' | 'REJECTED'; bankReference: string };
type BankSettlement = { id: string; runId: string; bankFileId: string; bankReference: string; acknowledgedAmount: number; evidenceReference: string; status: 'RECORDED' | 'CONFIRMED' | 'REJECTED' | 'REVERSED'; recordedBy: string; confirmedBy: string | null; exceptionReason: string | null; exceptionEvidenceReference: string | null };
type SettlementSummary = { expected: number; confirmed: number; outstanding: number; status: string; acknowledgments: BankSettlement[] };
type EmployeeAcknowledgements = { acknowledged: number; total: number; pending: number };

const money = (amount: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP' }).format(Number(amount || 0));
const date = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${value}T12:00:00`));
const stages = ['DRAFT', 'COMPUTED', 'REVIEWED', 'APPROVED', 'EMPLOYEE_REVIEW', 'LOCKED', 'PAID'];

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
  const [employeeAcknowledgements, setEmployeeAcknowledgements] = useState<EmployeeAcknowledgements | null>(null);
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
  type CertificateRow = { employeeId: string; employeeNo: string; employeeName: string; employeeTin: string | null; employeeAddress: string | null; birthDate: string | null; hireDate: string|null; separationDate: string|null; employerName: string | null; employerTin: string | null; employerAddress: string | null; employerRdo: string | null; priorEmployerName: string | null; priorEmployerCompensation: number; priorEmployerTaxableCompensation: number; priorEmployerNonTaxableCompensation: number; priorEmployerTaxWithheld: number; grossCompensation: number; nonTaxableCompensation: number; taxableCompensation: number; withholdingTax: number; missingFields: string[]; readyForReview: boolean };
  type Bir2316Draft = { summary: CertificateRow; compensationItems: Record<string, number>; taxableCompensationPresentEmployer: number; taxableCompensationPreviousEmployer: number; grossTaxableCompensation: number; taxDue: number; presentEmployerTaxWithheld: number; previousEmployerTaxWithheld: number; totalTaxesWithheld: number; peraTaxCredit: number; minimumWageEarner: boolean; formVersion: string; draft: boolean; manualCompletionFields: string[]; employeeTaxProfile: TaxProfile; employerTaxProfile: EmployerTax; priorEmployerTaxProfile: PriorTax; peraTaxCreditProfile:PeraTax; reviewStatus: string };
  const [certificateRows, setCertificateRows] = useState<CertificateRow[]>([]);
  const [certificateLoading, setCertificateLoading] = useState(false);
  const [certificateEmployeeId, setCertificateEmployeeId] = useState('');
  type TaxProfile = { tin: string|null; minimumWageEarner: boolean; employeeRdoCode: string|null; registeredZipCode: string|null; localHomeAddress: string|null; localHomeZipCode: string|null; foreignAddress: string|null; statutoryMinimumWageDaily: number|null; statutoryMinimumWageMonthly: number|null; mobile: string|null; branchCode:string|null; regionNum:string|null; nationality:string|null; employmentStatus:string|null; reasonSeparation:string|null; substitutedFiling:string|null; minimumWageFactorUsed:number|null };
  type PriorTax = { priorEmployerName: string|null; priorEmployerTin: string|null; priorEmployerAddress: string|null; priorEmployerRdoCode: string|null; priorEmployerZipCode: string|null; priorEmployerCompensation: number; priorEmployerTaxableCompensation: number; priorEmployerNonTaxableCompensation: number; priorEmployerTaxWithheld: number; priorEmployerCertificateReceived: boolean; priorNontaxBasicSmw:number; priorNontaxHolidayPay:number; priorNontaxOvertimePay:number; priorNontaxNightDiff:number; priorNontaxHazardPay:number; priorNontax13thMonth:number; priorNontaxDeminimis:number; priorNontaxSssEtc:number; priorNontaxSalaries:number; priorTaxableBasicSalary:number; priorTaxable13thMonth:number; priorTaxableSalaries:number };
  type EmployerTax = { companyId: string; legalName: string; tin: string|null; branchCode:string|null; address: string|null; rdoCode: string|null; zipCode: string|null; employerType: string|null; mainEmployer: boolean };
  type PeraTax = { employeeId: string; taxYear: number; qualifiedContributions: number; overseasFilipino: boolean; taxCreditAmount: number; certificateNumber: string|null; certificateReceived: boolean };
  type ReviewStatus = { id: string; employeeId: string; employeeNo: string; employeeName: string; taxYear: number; status: string; submittedBy: string; submittedAt: string; decidedBy: string|null; decidedAt: string|null; decisionNote: string|null; stale: boolean };
  const [taxProfile, setTaxProfile] = useState<TaxProfile|null>(null);
  const [priorTax, setPriorTax] = useState<PriorTax|null>(null);
  const [employerTax, setEmployerTax] = useState<EmployerTax|null>(null);
  const [peraTax, setPeraTax] = useState<PeraTax|null>(null);
  const [profileLoading, setProfileLoading] = useState(false);
  const [reviews, setReviews] = useState<ReviewStatus[]>([]);

  const request = useCallback(async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
    const response = await fetch(path, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers } });
    if (!response.ok) {
      let message = `Request failed (${response.status}).`;
      try { const body = await response.json() as { message?: string; detail?: string }; message = body.detail || body.message || message; } catch { /* response has no JSON body */ }
      if (response.status === 401) message = 'Your sign-in session has expired. Please sign in again.';
      if (response.status === 403) message = 'Payroll access requires a workspace owner or payroll officer account and an MFA-verified session.';
      if (response.status === 429) message = 'Too many requests. Wait a few minutes before trying again.';
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
  useEffect(() => { void loadTaxMaintenance(certificateEmployeeId); }, [certificateEmployeeId, certificateYear, employees, request]);

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
      setEmployeeAcknowledgements(run.status === 'EMPLOYEE_REVIEW' ? await request<EmployeeAcknowledgements>(`/api/payroll/runs/${run.id}/employee-acknowledgements`) : null);
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
      setReviews(await request<ReviewStatus[]>(`/api/payroll/reports/bir-2316-reviews?year=${certificateYear}`));
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load annual tax certificate data.'); }
    finally { setCertificateLoading(false); }
  }

  async function loadTaxMaintenance(employeeId: string) {
    if (!employeeId) { setTaxProfile(null); setPriorTax(null); setEmployerTax(null); return; }
    const employee=employees.find(item=>item.id===employeeId); if(!employee)return;
    setProfileLoading(true);
    try {
      const [personal,prior,employer,pera]=await Promise.all([
        request<TaxProfile>(`/api/payroll/employees/${employeeId}/tax-profile`),
        request<PriorTax>(`/api/payroll/employees/${employeeId}/prior-employer`),
        request<EmployerTax>(`/api/payroll/companies/${employee.companyId}/tax-profile`),
        request<PeraTax>(`/api/payroll/employees/${employeeId}/pera-tax-credit?taxYear=${certificateYear}`),
      ]);
      setTaxProfile(personal);setPriorTax(prior);setEmployerTax(employer);setPeraTax(pera);
    } catch(reason) { setError(reason instanceof Error?reason.message:'Could not load 2316 tax profile.'); }
    finally { setProfileLoading(false); }
  }

  async function saveTaxMaintenance() {
    if(!certificateEmployeeId||!taxProfile||!priorTax||!employerTax||!peraTax)return;
    setBusy('tax-profile');setError('');setNotice('');
    try {
      await Promise.all([
        request(`/api/payroll/employees/${certificateEmployeeId}/tax-profile`,{method:'PUT',body:JSON.stringify(taxProfile)}),
        request(`/api/payroll/employees/${certificateEmployeeId}/prior-employer`,{method:'PUT',body:JSON.stringify(priorTax)}),
        request(`/api/payroll/companies/${employerTax.companyId}/tax-profile`,{method:'PUT',body:JSON.stringify(employerTax)}),
        request(`/api/payroll/employees/${certificateEmployeeId}/pera-tax-credit?taxYear=${certificateYear}`,{method:'PUT',body:JSON.stringify(peraTax)}),
      ]);
      setNotice('Employee, prior-employer, and employer tax profiles saved.');
      await loadTaxMaintenance(certificateEmployeeId);
    } catch(reason) { setError(reason instanceof Error?reason.message:'Could not save tax profile.'); }
    finally { setBusy(''); }
  }

  async function submitBir2316Review(employeeId: string) {
    setBusy(`2316-submit-${employeeId}`);setError('');
    try {
      await request<ReviewStatus>(`/api/payroll/reports/bir-2316-reviews?year=${certificateYear}&employeeId=${employeeId}`,{method:'POST'});
      setReviews(await request<ReviewStatus[]>(`/api/payroll/reports/bir-2316-reviews?year=${certificateYear}`));
      setNotice('Form 2316 submitted for independent HR review.');
    } catch(reason) { setError(reason instanceof Error?reason.message:'Could not submit Form 2316 for review.'); }
    finally { setBusy(''); }
  }

  async function decideBir2316Review(review: ReviewStatus, decision: 'APPROVE'|'REJECT') {
    setBusy(`2316-decision-${review.id}`);setError('');
    try {
      await request(`/api/payroll/reports/bir-2316-reviews/${review.id}/decision`,{method:'POST',body:JSON.stringify({decision,note:decision==='REJECT'?'Please correct the tax profile or reconciliations and resubmit.':'Reviewed against payroll and tax-profile source data.'})});
      setReviews(await request<ReviewStatus[]>(`/api/payroll/reports/bir-2316-reviews?year=${certificateYear}`));
      setNotice(decision==='APPROVE'?'Form 2316 approved for printing.':'Form 2316 review rejected. Correct it and resubmit.');
    } catch(reason) { setError(reason instanceof Error?reason.message:'Could not record review decision.'); }
    finally { setBusy(''); }
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

  async function downloadAlphalistReconciliation() {
    const selectedEmployee=employees.find(employee=>employee.id===certificateEmployeeId);
    if(!selectedEmployee){setError('Choose an employee so the system can identify the employer company for this annual report.');return;}
    setBusy('alphalist');setError('');
    try {
      const query=`year=${certificateYear}&companyId=${selectedEmployee.companyId}`;
      const response=await fetch(`/api/payroll/reports/bir-1604c-reconciliation.csv?${query}`,{headers:{Authorization:`Bearer ${token}`}});
      if(!response.ok)throw new Error(`Could not export Alphalist reconciliation (${response.status}).`);
      const blob=await response.blob();const url=URL.createObjectURL(blob);const anchor=document.createElement('a');
      anchor.href=url;anchor.download=`bir-1604c-alphalist-reconciliation-${certificateYear}.csv`;anchor.click();URL.revokeObjectURL(url);
      const employees=Number(response.headers.get('X-Report-Employee-Count')||0);
      const schedule1=Number(response.headers.get('X-Report-Schedule1-Count')||0);
      const schedule2=Number(response.headers.get('X-Report-Schedule2-Count')||0);
      const exceptions=Number(response.headers.get('X-Report-Exception-Count')||0);
      setNotice(`Downloaded ${employees} employee records: ${schedule1} Schedule 1 and ${schedule2} Schedule 2. ${exceptions} employee records need review. This is an internal workpaper, not a BIR DAT filing file.`);
    } catch(reason) {setError(reason instanceof Error?reason.message:'Could not build the annual Alphalist reconciliation.');}
    finally {setBusy('');}
  }

  async function downloadBir1604cDat() {
    const selectedEmployee=employees.find(employee=>employee.id===certificateEmployeeId);
    if(!selectedEmployee){setError('Choose an employee so the system can identify the employer company for this annual DAT.');return;}
    setBusy('bir-dat');setError('');setNotice('');
    try {
      const query=`year=${certificateYear}&companyId=${selectedEmployee.companyId}`;
      const response=await fetch(`/api/payroll/reports/bir-1604c.dat?${query}`,{headers:{Authorization:`Bearer ${token}`}});
      if(!response.ok){let message=`BIR DAT export blocked (${response.status}).`;try{const body=await response.json() as {message?:string;detail?:string};message=body.detail||body.message||message;}catch{}throw new Error(message);}
      const blob=await response.blob();const url=URL.createObjectURL(blob);const anchor=document.createElement('a');
      const disposition=response.headers.get('Content-Disposition')||'';const filename=disposition.match(/filename="?([^";]+)"?/i)?.[1]||`bir-1604c-${certificateYear}.DAT`;
      anchor.href=url;anchor.download=filename;anchor.click();URL.revokeObjectURL(url);
      setNotice(`Downloaded ${filename}. Import it into BIR Alphalist Data Entry and Validation Module and confirm its validation report before submission.`);
    } catch(reason) {setError(reason instanceof Error?reason.message:'Could not generate the BIR 1604-C DAT file.');}
    finally {setBusy('');}
  }

  async function printCertificate() {
    if (!certificateEmployeeId) { setError('Choose an employee before preparing a printable Form 2316 draft.'); return; }
    setBusy('certificate-print'); setError('');
    try {
      const draft = await request<Bir2316Draft>(`/api/payroll/reports/bir-2316-issued?year=${certificateYear}&employeeId=${certificateEmployeeId}`);
      const row = draft.summary;
      const formStart = row.hireDate?.startsWith(`${certificateYear}-`) ? `${row.hireDate.slice(5,7)}/${row.hireDate.slice(8,10)}` : '01/01';
      const formEnd = row.separationDate?.startsWith(`${certificateYear}-`) ? `${row.separationDate.slice(5,7)}/${row.separationDate.slice(8,10)}` : '12/31';
      const personal = draft.employeeTaxProfile;
      const employer = draft.employerTaxProfile;
      const prior = draft.priorEmployerTaxProfile;
      const popup = window.open('', '_blank', 'noopener,noreferrer');
      if (!popup) throw new Error('Allow pop-ups to open the printable Form 2316 draft.');
      const escape = (value: unknown) => String(value ?? '—').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]!));
      const itemLabels: Record<string, string> = { '29': 'Basic salary (exempt portion / MWE)', '30': 'Holiday pay (MWE)', '31': 'Overtime pay (MWE)', '32': 'Night shift differential (MWE)', '33': 'Hazard pay (MWE)', '34': '13th month pay and other benefits', '35': 'De minimis benefits', '36': 'SSS / GSIS / PHIC / Pag-IBIG and union dues', '37': 'Salaries and other forms of compensation', '38': 'Total non-taxable / exempt compensation', '39': 'Basic salary', '40': 'Representation', '41': 'Transportation', '42': 'Cost of living allowance (COLA)', '43': 'Fixed housing allowance', '44A': 'Others (specify)', '44B': 'Other taxable compensation', '45': 'Commission', '46': 'Profit sharing', '47': 'Fees including director fees', '48': 'Taxable 13th month benefits', '49': 'Hazard pay', '50': 'Overtime pay', '51A': 'Others (specify)', '51B': 'Other taxable compensation' };
      const compensationRows = Object.entries(itemLabels).map(([key, label]) => `<tr><th>${escape(key)}</th><td>${escape(label)}</td><td class="amount">${money(draft.compensationItems[key] || 0)}</td></tr>`).join('');
      const amount = (value: number) => money(value || 0);
      popup.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>BIR Form 2316 draft ${certificateYear}</title><style>@page{size:A4;margin:12mm}*{box-sizing:border-box}body{font:10px Arial,sans-serif;color:#111;margin:0}h1{font-size:17px;text-align:center;margin:0 0 3px}h2{font-size:11px;background:#e8edf2;padding:4px;margin:7px 0 3px}p{margin:3px 0}.banner{border:2px solid #a40000;color:#a40000;text-align:center;font-size:13px;font-weight:bold;padding:5px;margin-bottom:5px}.sub{text-align:center;font-size:9px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:4px}.box{border:1px solid #555;padding:4px;min-height:24px}.label{color:#444;font-size:8px}.value{font-weight:bold;min-height:12px}.blank{background:#fff4c2}.wide{grid-column:1/-1}table{width:100%;border-collapse:collapse;font-size:9px}td,th{border:1px solid #777;text-align:left;padding:3px}th{font-weight:normal}.amount{text-align:right;white-space:nowrap}.page{min-height:270mm}.pagebreak{break-before:page}.signatures{display:grid;grid-template-columns:1fr 1fr;gap:25px;margin-top:22px}.sig{border-top:1px solid #111;padding-top:4px}.footer{font-size:8px;margin-top:8px}.no-print{margin:12px}@media print{.no-print{display:none}.page{min-height:0}}</style></head><body><div class="page"><div class="banner">DRAFT FOR REVIEW · NOT SIGNED</div><h1>BIR Form No. 2316</h1><p class="sub">Certificate of Compensation Payment / Tax Withheld · ${escape(draft.formVersion)} · 1. Tax year ${certificateYear} · 2. Period ${formStart} to ${formEnd}</p><h2>Part I · Employee information</h2><div class="grid"><div class="box"><div class="label">3. Taxpayer Identification Number</div><div class="value">${escape(row.employeeTin)}</div></div><div class="box blank"><div class="label">5. RDO code</div><div class="value">${escape(personal.employeeRdoCode)}</div></div><div class="box wide"><div class="label">4. Employee name</div><div class="value">${escape(row.employeeName)}</div></div><div class="box wide"><div class="label">6. Registered address</div><div class="value">${escape(row.employeeAddress)}</div></div><div class="box blank"><div class="label">6A. ZIP code</div><div class="value">${escape(personal.registeredZipCode)}</div></div><div class="box blank"><div class="label">7. Date of birth</div><div class="value">${escape(row.birthDate)}</div></div><div class="box blank"><div class="label">6B. Local home address</div><div class="value">${escape(personal.localHomeAddress)}</div></div><div class="box blank"><div class="label">6C. ZIP code</div><div class="value">${escape(personal.localHomeZipCode)}</div></div><div class="box blank"><div class="label">6D. Foreign address</div><div class="value">${escape(personal.foreignAddress)}</div></div><div class="box blank"><div class="label">8. Contact number</div><div class="value">${escape(personal.mobile)}</div></div><div class="box blank"><div class="label">9–10. Statutory minimum wage rate per day / month</div><div class="value">${personal.statutoryMinimumWageDaily==null?"—":money(Number(personal.statutoryMinimumWageDaily))} / ${personal.statutoryMinimumWageMonthly==null?"—":money(Number(personal.statutoryMinimumWageMonthly))}</div></div><div class="box"><div class="label">11. Minimum wage earner</div><div class="value">${draft.minimumWageEarner ? 'YES' : 'NO'}</div></div></div><h2>Part II · Present employer information</h2><div class="grid"><div class="box"><div class="label">12. Employer TIN</div><div class="value">${escape(row.employerTin)}</div></div><div class="box blank"><div class="label">15. Main / secondary employer · type</div><div class="value">${employer.mainEmployer ? "Main" : "Secondary"} · ${escape(employer.employerType)}</div></div><div class="box wide"><div class="label">13. Employer name</div><div class="value">${escape(row.employerName)}</div></div><div class="box wide"><div class="label">16. Registered address</div><div class="value">${escape(row.employerAddress)}</div></div><div class="box blank"><div class="label">14A. ZIP code</div><div class="value">${escape(employer.zipCode)}</div></div></div><h2>Part III · Previous employer</h2><div class="grid"><div class="box blank"><div class="label">16. Previous employer TIN</div><div class="value">${escape(prior.priorEmployerTin)}</div></div><div class="box"><div class="label">17. Previous employer name</div><div class="value">${escape(row.priorEmployerName || '')}</div></div><div class="box blank wide"><div class="label">18. Previous employer address</div><div class="value">${escape(prior.priorEmployerAddress)} · ZIP ${escape(prior.priorEmployerZipCode)} · RDO ${escape(prior.priorEmployerRdoCode)}</div></div></div><h2>Part IV-A · Summary</h2><table><tbody><tr><th>Gross compensation from present employer</th><td class="amount">${amount((draft.compensationItems['38'] || 0) + draft.taxableCompensationPresentEmployer)}</td></tr><tr><th>Less: non-taxable / exempt compensation</th><td class="amount">${amount(draft.compensationItems['38'])}</td></tr><tr><th>Taxable compensation from present employer</th><td class="amount">${amount(draft.taxableCompensationPresentEmployer)}</td></tr><tr><th>Add: taxable compensation from previous employer</th><td class="amount">${amount(draft.taxableCompensationPreviousEmployer)}</td></tr><tr><th>Gross taxable compensation</th><td class="amount">${amount(draft.grossTaxableCompensation)}</td></tr><tr><th>24. Tax due</th><td class="amount">${amount(draft.taxDue)}</td></tr><tr><th>25A. Taxes withheld · present employer</th><td class="amount">${amount(draft.presentEmployerTaxWithheld)}</td></tr><tr><th>25B. Taxes withheld · previous employer</th><td class="amount">${amount(draft.previousEmployerTaxWithheld)}</td></tr><tr><th>26. Total amount withheld as adjusted</th><td class="amount">${amount(draft.presentEmployerTaxWithheld+draft.previousEmployerTaxWithheld)}</td></tr><tr><th>27. PERA 5% tax credit</th><td class="amount">${amount(draft.peraTaxCredit)} <small>${draft.peraTaxCreditProfile.certificateNumber?`TCC ${escape(draft.peraTaxCreditProfile.certificateNumber)}`:'No verified TCC recorded'}</small></td></tr><tr><th>28. Total taxes withheld (26 + 27)</th><td class="amount">${amount(draft.totalTaxesWithheld)}</td></tr></tbody></table><p class="footer">Amounts are generated from finalized payroll records and employee tax profile. Verify statutory exemptions, including the applicable aggregate 13th month / other benefits limit, and complete yellow fields before signing.</p></div><div class="page pagebreak"><div class="banner">DRAFT FOR REVIEW · NOT SIGNED</div><h1>BIR Form No. 2316 · ${certificateYear}</h1><h2>Part IV-B · Details of compensation income and tax withheld from present employer</h2><table><thead><tr><th>Form line</th><th>Compensation item</th><th class="amount">Amount (₱)</th></tr></thead><tbody>${compensationRows}</tbody></table><h2>Part V · Certification and acknowledgment</h2><p>I declare under the penalties of perjury that this certificate has been made in good faith, verified by me, and to the best of my knowledge and belief is true and correct.</p><div class="signatures"><div class="sig">53. Present employer / authorized agent signature over printed name<br><br>Date signed: __________________</div><div class="sig">54. Employee signature over printed name<br><br>Date signed: __________________</div></div><h2>Substituted filing</h2><p>Complete the substituted filing declaration on the official form when applicable.</p><div class="signatures"><div class="sig">55. Present employer / authorized agent signature over printed name<br><br>Date signed: __________________</div><div class="sig">56. Employee signature over printed name<br><br>Date signed: __________________</div></div><p>Employee CTC / valid ID number: ____________________ &nbsp; Place issued: ____________________ &nbsp; Date issued: __________ &nbsp; Amount paid if CTC: __________</p><p class="footer"><strong>Manual completion required:</strong> ${draft.manualCompletionFields.map(escape).join('; ')}. This unsigned draft is a working aid and requires review against the current BIR form and source records before use.</p></div><button class="no-print" onclick="window.print()">Print draft</button></body></html>`);
      popup.document.close();
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not prepare the printable Form 2316 draft.'); }
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
    APPROVED: { action: 'publish-for-employee-review', label: 'Publish payslips for employee review', confirm: 'Publish payslips to employees? Payroll remains open until every employee acknowledges their payslip.' },
    EMPLOYEE_REVIEW: { action: 'lock', label: 'Close payroll after acknowledgements', confirm: 'Close and freeze this payroll run? Every employee must have acknowledged their payslip.' },
    LOCKED: { action: 'pay', label: 'Mark paid', confirm: 'Confirm that this payroll run has been paid?' },
  };
  const action = selected ? lifecycleAction[selected.status] : undefined;

  return <section className="timekeeping-workspace payroll-workspace">
    <header className="timekeeping-heading"><div><p className="eyebrow">PAYROLL</p><h1>Payroll runs</h1><p>Create payroll for an open pay period, review the calculation, and complete the controlled approval lifecycle.</p></div><button type="button" className="session-revoke" onClick={() => void load()} disabled={loading}>{loading ? 'Loading…' : '↻ Refresh'}</button></header>
    {mfaVerified === false && <div className="payroll-mfa-notice"><span><strong>Verify your authenticator to use payroll</strong><small>Payroll actions require MFA. Enable and verify an authenticator in account security, then sign in again so this session receives payroll access.</small></span><button type="button" className="checklist-action" onClick={onOpenSecurity}>Open account security</button></div>}
    <Toast message={error||notice} kind={error?'error':'success'} onDismiss={()=>{setError('');setNotice('')}} />
    <div className="timekeeping-metrics payroll-metrics"><article><span>PAY PERIODS</span><strong>{periods.length}</strong><small>{openPeriods.length} open</small></article><article><span>PAYROLL RUNS</span><strong>{runs.length}</strong><small>{runs.filter(run => !['PAID'].includes(run.status)).length} in progress</small></article><article><span>PENDING APPROVAL</span><strong>{runs.filter(run => run.status === 'REVIEWED').length}</strong><small>Reviewed runs</small></article><article><span>PAID RUNS</span><strong>{runs.filter(run => run.status === 'PAID').length}</strong><small>Completed</small></article></div>
    <section className="payroll-panel">
      <div className="payroll-panel-heading"><div><p className="eyebrow">YEAR-END TAX REPORTING</p><h2>Form 2316 and 1604-C reporting</h2><p>Maintain tax data, reconcile finalized payroll, prepare each employee’s Form 2316, and check the annual Alphalist schedules before filing.</p></div></div>
      <div className="tax-form-grid">
        <label className="payroll-field">Tax year<input type="number" min="2000" max={new Date().getFullYear()+1} value={certificateYear} onChange={event=>setCertificateYear(Number(event.target.value))}/></label>
        <label className="payroll-field">Employee<select value={certificateEmployeeId} onChange={event=>setCertificateEmployeeId(event.target.value)}><option value="">Select employee…</option>{employees.map(employee=><option key={employee.id} value={employee.id}>{employee.employeeNo} · {employee.firstName} {employee.lastName}</option>)}</select></label>
        <button type="button" className="security-submit" onClick={()=>void loadCertificate()} disabled={certificateLoading||mfaVerified===false}>{certificateLoading?'Loading…':'Load register and reviews'}</button>
        <button type="button" className="security-submit" onClick={()=>void downloadCertificate()} disabled={busy==='certificate'||mfaVerified===false}>{busy==='certificate'?'Exporting…':'Export CSV for review'}</button>
        <button type="button" className="security-submit" onClick={()=>void downloadAlphalistReconciliation()} disabled={busy==='alphalist'||mfaVerified===false||!certificateEmployeeId}>{busy==='alphalist'?'Building…':'Export 1604-C Alphalist reconciliation'}</button>
        <button type="button" className="security-submit" onClick={()=>void downloadBir1604cDat()} disabled={busy==='bir-dat'||mfaVerified===false||!certificateEmployeeId}>{busy==='bir-dat'?'Validating…':'Export BIR 1604-C DAT'}</button>
        <button type="button" className="security-submit" onClick={()=>void printCertificate()} disabled={busy==='certificate-print'||mfaVerified===false||!certificateEmployeeId||reviews.find(r=>r.employeeId===certificateEmployeeId)?.status!=='APPROVED'||reviews.find(r=>r.employeeId===certificateEmployeeId)?.stale}>{busy==='certificate-print'?'Preparing…':'Print approved Form 2316'}</button>
      </div>
      <p className="payroll-help">Both Alphalist actions cover the selected employee’s employer and include all eligible employees for the tax year. DAT export is blocked until every employee record reconciles and has a current approved Form 2316. Validate the downloaded DAT in BIR’s Alphalist Data Entry and Validation Module before filing.</p>
      {certificateEmployeeId&&taxProfile&&priorTax&&employerTax&&<details className="payroll-lines-details" open>
        <summary>{profileLoading?'Loading tax profile…':'Maintain employee, employer, and previous-employer tax data'}</summary>
        <div className="tax-form-grid">
          <label className="payroll-field">Employee TIN<input value={taxProfile.tin||''} maxLength={16} placeholder="000-000-000" onChange={e=>setTaxProfile({...taxProfile,tin:e.target.value})}/></label>
          <label className="payroll-field">Employee branch code<input value={taxProfile.branchCode||''} maxLength={4} inputMode="numeric" placeholder="4 digits" onChange={e=>setTaxProfile({...taxProfile,branchCode:e.target.value})}/></label>
          <label className="payroll-field">Assigned BIR region number<input value={taxProfile.regionNum||''} maxLength={4} inputMode="numeric" placeholder="4 digits" onChange={e=>setTaxProfile({...taxProfile,regionNum:e.target.value})}/></label>
          <label className="payroll-field">Nationality<input value={taxProfile.nationality||''} maxLength={30} placeholder="e.g. Filipino" onChange={e=>setTaxProfile({...taxProfile,nationality:e.target.value})}/></label>
          <label className="payroll-field">BIR employment status<select value={taxProfile.employmentStatus||''} onChange={e=>setTaxProfile({...taxProfile,employmentStatus:e.target.value||null})}><option value="">Select status…</option><option value="R">Regular</option><option value="C">Casual</option><option value="CP">Contractual / project-based</option><option value="S">Seasonal</option><option value="P">Probationary</option><option value="AL">Apprentice / learner</option></select></label>
          <label className="payroll-field">Reason for separation<select value={taxProfile.reasonSeparation||''} onChange={e=>setTaxProfile({...taxProfile,reasonSeparation:e.target.value||null})}><option value="">Select if applicable…</option><option value="NA">Not applicable</option><option value="T">Terminated / resigned</option><option value="TR">Transferred</option><option value="R">Retirement</option><option value="D">Death</option></select></label>
          <label className="payroll-field">Substituted filing<select value={taxProfile.substitutedFiling||''} onChange={e=>setTaxProfile({...taxProfile,substitutedFiling:e.target.value||null})}><option value="">Select…</option><option value="Y">Yes</option><option value="N">No</option></select></label>
          <label className="payroll-field">Employee RDO<input value={taxProfile.employeeRdoCode||''} maxLength={3} inputMode="numeric" placeholder="e.g. 042" onChange={e=>setTaxProfile({...taxProfile,employeeRdoCode:e.target.value})}/></label>
          <label className="payroll-field">Registered address ZIP<input value={taxProfile.registeredZipCode||''} maxLength={4} inputMode="numeric" placeholder="4 digits" onChange={e=>setTaxProfile({...taxProfile,registeredZipCode:e.target.value})}/></label>
          <label className="payroll-field">Local home address<input value={taxProfile.localHomeAddress||''} maxLength={500} onChange={e=>setTaxProfile({...taxProfile,localHomeAddress:e.target.value})}/></label>
          <label className="payroll-field">Local home ZIP<input value={taxProfile.localHomeZipCode||''} maxLength={4} inputMode="numeric" placeholder="4 digits" onChange={e=>setTaxProfile({...taxProfile,localHomeZipCode:e.target.value})}/></label>
          <label className="payroll-field">Foreign address (if applicable)<input value={taxProfile.foreignAddress||''} maxLength={500} onChange={e=>setTaxProfile({...taxProfile,foreignAddress:e.target.value})}/></label>
          <label className="payroll-field">Contact number<input value={taxProfile.mobile||''} maxLength={32} onChange={e=>setTaxProfile({...taxProfile,mobile:e.target.value})}/></label>
          <label className="payroll-field">Statutory minimum wage per day<input type="number" min="0" step="0.01" value={taxProfile.statutoryMinimumWageDaily??''} onChange={e=>setTaxProfile({...taxProfile,statutoryMinimumWageDaily:e.target.value===''?null:Number(e.target.value)})}/></label>
          <label className="payroll-field">Statutory minimum wage per month<input type="number" min="0" step="0.01" value={taxProfile.statutoryMinimumWageMonthly??''} onChange={e=>setTaxProfile({...taxProfile,statutoryMinimumWageMonthly:e.target.value===''?null:Number(e.target.value)})}/></label>
          <label className="payroll-field">MWE annual wage factor (days)<input type="number" min="1" max="366" step="1" value={taxProfile.minimumWageFactorUsed??''} placeholder="e.g. 261" onChange={e=>setTaxProfile({...taxProfile,minimumWageFactorUsed:e.target.value===''?null:Number(e.target.value)})}/></label>
          <label className="payroll-field">Minimum wage earner<select value={String(taxProfile.minimumWageEarner)} onChange={e=>setTaxProfile({...taxProfile,minimumWageEarner:e.target.value==='true'})}><option value="false">No</option><option value="true">Yes</option></select></label>
          <label className="payroll-field">Employer TIN<input value={employerTax.tin||''} maxLength={16} placeholder="000-000-000" onChange={e=>setEmployerTax({...employerTax,tin:e.target.value})}/></label>
          <label className="payroll-field">Employer branch code<input value={employerTax.branchCode||''} maxLength={4} inputMode="numeric" placeholder="4 digits" onChange={e=>setEmployerTax({...employerTax,branchCode:e.target.value})}/></label>
          <label className="payroll-field">Employer RDO<input value={employerTax.rdoCode||''} maxLength={3} inputMode="numeric" onChange={e=>setEmployerTax({...employerTax,rdoCode:e.target.value})}/></label>
          <label className="payroll-field">Employer registered address<input value={employerTax.address||''} maxLength={500} onChange={e=>setEmployerTax({...employerTax,address:e.target.value})}/></label>
          <label className="payroll-field">Employer ZIP<input value={employerTax.zipCode||''} maxLength={4} inputMode="numeric" onChange={e=>setEmployerTax({...employerTax,zipCode:e.target.value})}/></label>
          <label className="payroll-field">Employer type<select value={employerTax.employerType||''} onChange={e=>setEmployerTax({...employerTax,employerType:e.target.value||null})}><option value="">Select type…</option><option value="PRIVATE">Private</option><option value="GOVERNMENT">Government</option><option value="NONPROFIT">Nonprofit</option><option value="OTHER">Other</option></select></label>
          <label className="payroll-field">Employer classification<select value={String(employerTax.mainEmployer)} onChange={e=>setEmployerTax({...employerTax,mainEmployer:e.target.value==='true'})}><option value="true">Main employer</option><option value="false">Secondary employer</option></select></label>
          <label className="payroll-field">Previous employer name<input value={priorTax.priorEmployerName||''} maxLength={255} onChange={e=>setPriorTax({...priorTax,priorEmployerName:e.target.value})}/></label>
          <label className="payroll-field">Previous employer TIN<input value={priorTax.priorEmployerTin||''} maxLength={16} onChange={e=>setPriorTax({...priorTax,priorEmployerTin:e.target.value})}/></label>
          <label className="payroll-field">Previous employer address<input value={priorTax.priorEmployerAddress||''} maxLength={500} onChange={e=>setPriorTax({...priorTax,priorEmployerAddress:e.target.value})}/></label>
          <label className="payroll-field">Previous employer ZIP<input value={priorTax.priorEmployerZipCode||''} maxLength={4} inputMode="numeric" onChange={e=>setPriorTax({...priorTax,priorEmployerZipCode:e.target.value})}/></label>
          <label className="payroll-field">Previous employer RDO<input value={priorTax.priorEmployerRdoCode||''} maxLength={3} inputMode="numeric" onChange={e=>setPriorTax({...priorTax,priorEmployerRdoCode:e.target.value})}/></label>
          <label className="payroll-field">Prior gross compensation<input type="number" min="0" step="0.01" value={priorTax.priorEmployerCompensation||0} onChange={e=>setPriorTax({...priorTax,priorEmployerCompensation:Number(e.target.value)})}/></label>
          <label className="payroll-field">Prior taxable compensation<input type="number" min="0" step="0.01" value={priorTax.priorEmployerTaxableCompensation||0} onChange={e=>setPriorTax({...priorTax,priorEmployerTaxableCompensation:Number(e.target.value)})}/></label>
          <label className="payroll-field">Prior non-taxable compensation<input type="number" min="0" step="0.01" value={priorTax.priorEmployerNonTaxableCompensation||0} onChange={e=>setPriorTax({...priorTax,priorEmployerNonTaxableCompensation:Number(e.target.value)})}/></label>
          <label className="payroll-field">Prior tax withheld<input type="number" min="0" step="0.01" value={priorTax.priorEmployerTaxWithheld||0} onChange={e=>setPriorTax({...priorTax,priorEmployerTaxWithheld:Number(e.target.value)})}/></label>
          <label className="payroll-field">Prior employer Form 2316 received<select value={String(priorTax.priorEmployerCertificateReceived)} onChange={e=>setPriorTax({...priorTax,priorEmployerCertificateReceived:e.target.value==='true'})}><option value="false">No</option><option value="true">Yes</option></select></label>
          {([['priorNontaxBasicSmw','Prior non-taxable basic / MWE salary'],['priorNontaxHolidayPay','Prior non-taxable holiday pay'],['priorNontaxOvertimePay','Prior non-taxable overtime'],['priorNontaxNightDiff','Prior non-taxable night differential'],['priorNontaxHazardPay','Prior non-taxable hazard pay'],['priorNontax13thMonth','Prior non-taxable 13th month / benefits'],['priorNontaxDeminimis','Prior non-taxable de minimis'],['priorNontaxSssEtc','Prior non-taxable statutory contributions'],['priorNontaxSalaries','Prior other non-taxable compensation'],['priorTaxableBasicSalary','Prior taxable basic salary'],['priorTaxable13thMonth','Prior taxable 13th month / benefits'],['priorTaxableSalaries','Prior other taxable compensation']] as const).map(([key,label])=><label className="payroll-field" key={key}>{label}<input type="number" min="0" step="0.01" value={priorTax[key]||0} onChange={e=>setPriorTax({...priorTax,[key]:Number(e.target.value)})}/></label>)}
          {peraTax&&<><label className="payroll-field">Qualified PERA contributions · {certificateYear}<input type="number" min="0" max={peraTax.overseasFilipino?200000:100000} step="0.01" value={peraTax.qualifiedContributions||0} onChange={e=>setPeraTax({...peraTax,qualifiedContributions:Number(e.target.value)})}/></label>
          <label className="payroll-field">Contributor type<select value={String(peraTax.overseasFilipino)} onChange={e=>setPeraTax({...peraTax,overseasFilipino:e.target.value==='true'})}><option value="false">Local</option><option value="true">Overseas Filipino</option></select></label>
          <label className="payroll-field">PERA tax credit from TCC<input type="number" min="0" max={Number((peraTax.qualifiedContributions*0.05).toFixed(2))} step="0.01" value={peraTax.taxCreditAmount||0} onChange={e=>setPeraTax({...peraTax,taxCreditAmount:Number(e.target.value)})}/></label>
          <label className="payroll-field">PERA Tax Credit Certificate number<input value={peraTax.certificateNumber||''} maxLength={100} placeholder="Enter issued TCC number" onChange={e=>setPeraTax({...peraTax,certificateNumber:e.target.value})}/></label>
          <label className="payroll-field">TCC received and verified<select value={String(peraTax.certificateReceived)} onChange={e=>setPeraTax({...peraTax,certificateReceived:e.target.value==='true'})}><option value="false">No</option><option value="true">Yes</option></select></label></>}
          <button type="button" className="security-submit" onClick={()=>void saveTaxMaintenance()} disabled={busy==='tax-profile'||profileLoading}>{busy==='tax-profile'?'Saving…':'Save tax profiles'}</button>
        </div>
      </details>}
      {certificateRows.length>0&&<div className="timekeeping-table-wrap"><table className="timekeeping-table"><thead><tr><th>EMPLOYEE / ANNUAL TOTALS</th><th>GROSS</th><th>NON-TAXABLE</th><th>TAXABLE</th><th>WITHHELD</th><th>DATA READINESS</th><th>REVIEW</th></tr></thead><tbody>{certificateRows.map(row=>{const review=reviews.find(r=>r.employeeId===row.employeeId);return <tr key={row.employeeId}><td><strong>{row.employeeName}</strong><small>{row.employeeNo} · TIN {row.employeeTin||'missing'}</small></td><td>{money(row.grossCompensation)}</td><td>{money(row.nonTaxableCompensation)}</td><td>{money(row.taxableCompensation)}</td><td>{money(row.withholdingTax)}</td><td>{row.readyForReview?'Complete':row.missingFields.join(', ')}</td><td><strong>{review?`${review.status}${review.stale?' · STALE':''}`:'Not submitted'}</strong><div className="timekeeping-row-actions"><button type="button" className="session-revoke" onClick={()=>setCertificateEmployeeId(row.employeeId)}>Select</button><button type="button" className="security-submit" disabled={!row.readyForReview||!!busy||review?.status==='PENDING'||(review?.status==='APPROVED'&&!review.stale)} onClick={()=>void submitBir2316Review(row.employeeId)}>Submit</button>{review?.status==='PENDING'&&<><button type="button" className="security-submit" disabled={!!busy||review.stale} onClick={()=>void decideBir2316Review(review,'APPROVE')}>Approve</button><button type="button" className="timekeeping-reject" disabled={!!busy} onClick={()=>void decideBir2316Review(review,'REJECT')}>Reject</button></>}</div>{review?.decisionNote&&<small>{review.decisionNote}</small>}</td></tr>})}</tbody></table></div>}
      <p className="timekeeping-footnote">Printing is blocked until HR or a tenant administrator approves the current payroll and profile snapshot. A source change after approval invalidates the approval and requires resubmission.</p>
    </section>
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
      {selected.status === 'EMPLOYEE_REVIEW' && <p className="payroll-help">Employee payslip acknowledgements: <strong>{employeeAcknowledgements?.acknowledged ?? 0} / {employeeAcknowledgements?.total ?? selected.employeeCount}</strong>{(employeeAcknowledgements?.pending ?? selected.employeeCount) > 0 ? ` · ${employeeAcknowledgements?.pending ?? selected.employeeCount} still pending` : ' · all employees acknowledged'}</p>}
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
      <p className="timekeeping-footnote">The sequence is DRAFT → COMPUTED → REVIEWED → APPROVED → EMPLOYEE REVIEW → LOCKED → PAID. Publishing issues payslips in self service; closing is blocked until each employee acknowledges. Closing freezes the regular pay period and included DTR days.</p>
    </section>}
    {selected && !action && selected.status === 'PAID' && <p className="payroll-complete-note">This run is paid and complete.</p>}
    <p className="timekeeping-footnote">Pay groups and pay periods are managed through the payroll setup API. This screen does not create missing pay configuration automatically.</p>
  </section>;
}
