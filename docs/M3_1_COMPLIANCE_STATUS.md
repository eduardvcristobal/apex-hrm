# M3.1 — Philippine Payroll Compliance (in progress)

Base: main @ 7bf2b308d3d759c370ab61210cba781dd8f1f494
Branch: feature/m3-1-payroll-compliance

## Implemented on this branch

- GitHub Actions CI verifies backend Maven unit tests and frontend TypeScript/Vite build.
- Partial monthly pay cutoffs use inclusive calendar-day proration of hire/separation dates, with a separate PRORATION deduction included in gross and taxable earnings calculations.
- Assignment changes do not create false proration. Intra-cutoff compensation changes fail closed until split-rate reconciliation is implemented.
- Earned basic pay for 13th-month computation subtracts PRORATION.
- Final-pay preview endpoint POST /api/payroll/final-pay/preview provides a reviewed gross/deduction/net worksheet. It does not disburse pay or infer benefit entitlement.
- Annualized tax preview endpoint POST /api/payroll/tax/annualization-preview uses caller-provided verified annual brackets, cumulative withholding, and the shared exemption ceiling for 13th-month and other bonuses. It does not alter payroll runs or submit BIR returns.
- Effective-dated Pag-IBIG low-income employee contribution rate via migration V49, calculator and Tax & Compliance UI.
- GET /api/payroll/runs/{id}/remittance-review?agency=SSS|PHILHEALTH|PAGIBIG produces an INTERNAL review CSV (not a government-approved upload file).
- Unit regressions cover proration, final-pay arithmetic, annualization arithmetic, Pag-IBIG threshold and CSV formula injection.

## M3.1 continuation — ledger-based annualization

- Flyway V50 seeds the BIR annual compensation brackets effective January 1, 2023, with tax-boundary tests.
- GET `/api/payroll/tax/employees/{employeeId}/annualization?year=2026` calculates a review-only annual reconciliation from LOCKED/PAID payroll lines and effective-dated ANNUAL brackets.
- The ledger groups taxable regular earnings, deductions for absence, late/undertime, proration and statutory employee shares, plus coded `THIRTEENTH_MONTH`, `OTHER_BENEFIT`, and `WTAX` amounts.
- Reconciliation returns an explicit REVIEW_REQUIRED status. Missing payroll records fail closed.
- This is not automatically posted to a pay run, does not generate an official BIR form, and cannot certify an incomplete year or uncategorized fringe benefits.
- Unit and service-level mocked ledger tests cover tax thresholds, bonus exemption handling and persisted-record lookup.
- Tax & Compliance now includes an Annualization Review tab that loads employee selections and displays persisted-year tax reconciliations without posting deductions or refunds.
- Tax sources: https://bir-cdn.bir.gov.ph/local/pdf/1701%20Jan%202018%20final%20with%20rates.pdf and https://bir-cdn.bir.gov.ph/local/pdf/RR%20No.%2011-2018.pdf

## M3.1.11 — Controlled annual withholding adjustments

- Flyway V51 adds `annual_tax_adjustment_request`, unique per tenant, employee, and tax year. The unique record persists even after rejection to prevent a silent duplicate request.
- The approved payroll item `WTAX_ANNUAL` is reserved from generic adjustment creation; generic adjustment requester identity is now taken from the authenticated user instead of client-supplied JSON.
- POST `/api/payroll/tax/adjustment-requests` requires a **completed prior year**, a positive computed shortfall, independently affirmed payroll-year completeness and benefit classifications, a written justification, and a future OPEN target period whose run has not reached approval.
- A different authenticated payroll administrator approves through POST `.../{id}/approve` or rejects through POST `.../{id}/reject`. Requests are row-locked during decisions; the annual ledger is recalculated and checked against the request snapshot immediately before approval.
- Approval creates one approved, non-taxable `WTAX_ANNUAL` payroll deduction in the standard adjustment queue. It **does not** issue payment, issue a refund, revise locked payroll, or claim a BIR filing.
- GET `/api/payroll/tax/adjustment-requests?employeeId=...&taxYear=...` and GET `.../{id}` support review/approval handoff in the Tax & Compliance UI.
- Unit regressions cover self-approval, stale ledgers, target period eligibility, duplicate requests, negative/refund exclusion, approved item creation and audit controls.
- Explicit bearer tokens can now access existing `/api/payroll/**` POST actions without session CSRF tokens; session/cookie requests remain CSRF-protected.
- CI now includes PostgreSQL 16 application startup and Flyway/Hibernate schema validation smoke testing.

**Important limitations:** completeness and benefit categorization are attested by authorized payroll staff rather than auto-certified; automatic refunds, final pay, complete annual tax reporting, government file submissions and signed human acceptance remain outside this milestone. Treat any request as pending HR/BIR review until independently validated.

## M3.1.12 — Separation-to-final-pay approval handoff

- V52 creates a tenant-scoped `payroll_final_pay_settlement` ledger with forced RLS, composite foreign keys to the Personnel employee, approved separation case and final-pay handoff, immutable submitted monetary components, independent requester/approver identities, and one settlement per handoff.
- Payroll/MFA endpoints:
  - `POST /api/v1/payroll/final-pay/employees/{employeeId}/cases/{caseId}/handoffs/{handoffId}/settlements` — submit an itemized source-verified final-pay calculation.
  - `GET /api/v1/payroll/final-pay/employees/{employeeId}/cases/{caseId}/settlements` — review the case's settlements.
  - `GET /api/v1/payroll/final-pay/settlements/{id}/statement` — itemized financial review statement, explicitly NOT an issued payslip.
  - `POST /api/v1/payroll/final-pay/settlements/{id}/approve|reject` — independent approval/rejection.
- Submission requires an approved effective separation, matching personnel handoff, at least one required CLEARED clearance, no nonreturned assets or unresolved asset exceptions, and attested verified leave, loan, tax and separation entitlement sources.
- The existing `FinalPayCalculator` derives gross, deductions and net from the submitted source amounts. Negative net is rejected for separate recovery review; no silent offset/forgiveness.
- A different authorized payroll approver must decide; a DB row lock and state guard prevent repeated decision. All submission and decision events are appended to the audit trail.
- Unit tests assert review attestations, negative-net fail-closed behavior, required source references, independent decisions and settlement arithmetic. GitHub Actions runs Maven tests, the frontend build and PostgreSQL migration/startup smoke.
- The Personnel workspace now displays a final-pay settlement panel on APPROVED separation cases. Tenant administrators can request a missing HR handoff, submit a source-reviewed settlement, inspect an itemized statement, and independently approve or reject. Payroll officers may use the protected payroll endpoints directly; they do not automatically gain access to private Personnel HR-case endpoints.
- **NO DISBURSEMENT:** `personnel_employee` (new SaaS HR model) and `employee` (legacy payroll model) cannot be assumed to share a verified ID. This milestone records an independently approved settlement snapshot only; it does NOT enqueue a legacy payroll run, deduct loans, issue a legally operative payslip, initiate a bank transfer, or mark any employee paid.
- Sources used for leave conversion, accrued 13th month, loan/property balances and legal separation entitlements are explicitly reviewed inputs, not yet fetched and reconciled automatically. The existing Personnel exit clearance is a hard gate.

## M3.1.13 — Identity linking and staged final-pay authorization

- V53 creates a forced-RLS, tenant-scoped, one-to-one `payroll_personnel_identity_link` table. Company, employee number, legal names and hire date must all match between `personnel_employee` and legacy `employee`. A second authorized payroll officer independently confirms the link; concurrent or duplicate links fail closed.
- `/api/v1/payroll/identity-links` supports request, approve/reject and employee-specific read; records are audited, and approval rechecks the source identity records.
- V54 introduces `payroll_final_pay_payment_authorization` uniquely bound to an APPROVED final-pay settlement, an APPROVED identity link, and a primary `employee_bank_account` for the matched legacy payroll employee. Net must be positive; account ID and legacy employee ID must match. Authorization is two-person, immutable in decision state, audited and does not generate a bank file.
- `/api/v1/payroll/final-pay/payment-authorizations` supports preparation, decision and review. The `eligible-accounts` endpoint returns only bank code and masked last four account digits after identity approval.
- V55 adds forced-RLS external bank evidence with a unique tenant/bank/reference key, amount matching, submission and independent review. `/api/v1/payroll/final-pay/bank-evidence` never marks a payroll run as paid and never claims an external banking API confirmation.
- Personnel approved-separation cases now expose the identity-link and staged payment authorization UI, with a separate bank-evidence entry and independent review step.
- New unit tests cover identity mismatch/same-name collisions, two-person verification, approved-settlement and matched bank-account gating, duplicate decision states and bank-evidence amount/actor checks. CI includes Maven tests, React build and PostgreSQL 16 Flyway startup/migration smoke.

**Strict distinction:** Payment authorization `AUTHORIZED` means a human has authorized an instruction inside Apex HRM, **not** that the bank received funds. Bank evidence `EVIDENCE_REVIEWED` means a separate officer reviewed manually supplied details, **not** that an official bank statement or payment gateway certified them. No automated disbursement, bank acknowledgement polling, actual bank settlement, payout reconciliation, legally issued final payslip, or legacy payroll PAID transition is implemented by M3.1.13. Payroll deductions, 13th-month accrual and leave balances are not yet automatically reconciled against full source ledgers; amounts remain human-attested.

## M3.1.14 — Financial-control pre-certification

- Added rollback-only PostgreSQL 16 integration fixture `backend/scripts/m3_1_14_postgres_e2e.sql` covering two tenants, approved separation-to-evidence lifecycle, duplicate payment/evidence constraints, segregation of duties and restricted-role RLS.
- CI runs the SQL lifecycle fixture after Flyway migration and schema assertions; anonymous financial API probes must return 401/403.
- Maven and frontend builds remain mandatory. **No production banking, certified final payslip, or human financial approval is implied by green CI.**
- See `docs/M3_1_14_CERTIFICATION_REPORT.md` for explicit coverage, evidence and NO-GO gates.

## M3.1.15 — Authenticated API E2E and read-only reconciliation pre-certification

- Added a real Spring Boot/PostgreSQL authenticated financial HTTP regression with synthetic multi-tenant MFA principals; CI races two separate identity approvers and two separate payment approvers, confirming one success and one state-conflict per race.
- Fixed `GlobalExceptionHandler` mistakenly converting explicit 403/404/409 into 500. Hardened bank evidence segregation so the payment preparer cannot review its own payout evidence.
- New payroll-MFA reconciliation preflight and Personnel display retain `bankConfirmed=false` and `disbursementEnabled=false` regardless of manual evidence review.
- Database lifecycle and cross-tenant SQL checks remain mandatory. New `authenticated-financial-api` GitHub Actions job adds real HTTP/CSRF-bearer/MFA/tenant checks.
- This is **authenticated API E2E only**; not Playwright browser E2E, live bank certification, complete source-ledger settlement or production payroll GO.
- See `docs/M3_1_15_CERTIFICATION_REPORT.md` for evidence, explicit limitations and next gates.

## M3.1.16 — Source-ledger review and real-browser E2E

- Introduced read-only payroll/leave/loan source-ledger comparison against a **verified Personnel-to-Payroll employee identity**; loan balance mismatch and convertible leave days are surfaced but never auto-certified in PHP.
- Locked/paid payroll lines for the separation year are summarized as prior 13th-month and withholding history. **Remaining earned 13th-month obligation is not inferred from historic payments alone**.
- CI now tests real loan balance mismatches, convertible leave values, missing locked payroll sources, tenant exclusion and two-actor API approvals.
- Added actual Chromium desktop 1440px and mobile 390px Personnel → HR lifecycle → source-ledger browser E2E through Vite/Spring Boot/PostgreSQL, plus unauthenticated user denial.
- Production status remains **NO-GO**. No payment release, certified payslip, bank confirmation or source-verified legal accrual exists. See `docs/M3_1_16_CERTIFICATION_REPORT.md`.

## M3.1.17 — DTR, 13th-month and withholding source diagnostics

- Added legacy payroll-source locked BASIC earnings, paid 13th-month and withheld WTAX summaries with a diagnostic residual = (locked BASIC / 12) - previously paid 13th month. **This is not a certified statutory accrual.**
- Added DTR finalized/open/absent day counts, late/undertime/approved overtime minutes; pending or missing DTR and incomplete payroll cutoffs remain blocking review findings.
- Unit and authenticated PostgreSQL/HTTP regressions include a locked payroll sample with PHP 14,400 BASIC, PHP 800 previously paid 13th, PHP 200 withheld tax, one open absent DTR and a locked late/undertime DTR.
- UI shows diagnostic values and unresolved tax annualization without enabling disbursement.
- See `docs/M3_1_17_CERTIFICATION_REPORT.md` for methodology and **production NO-GO** limits.

## M3.1.18 — Financial source snapshots and period completeness pre-certification

- Added Flyway V56 tenant-isolated append-only final-pay source snapshots, SHA-256 document digest, forced RLS and UPDATE/DELETE trigger guard.
- Captured source document includes approved settlement and separation, locked/paid payroll runs and lines, DTR, legacy effective-dated compensation, loans, leave and adjustments through separation date.
- New MFA-protected capture/read APIs; duplicates conflict and other tenant's records are hidden. UI shows missing payroll cutoff days, compensation gaps, open DTR, immutable snapshot digest and **disbursement disabled**.
- Automated checks cover cutoff/compensation gaps, snapshot hash, RLS, trigger immutability and existing authenticated API/Chromium flows.
- Capture is an evidence checkpoint, **not** a legally approved payment calculation or banking confirmation. See `docs/M3_1_18_CERTIFICATION_REPORT.md`.

## M3.1.19 — Source review, live freshness and manual bank-evidence integrity

- Added Flyway V57 independent source-review requests and irreversible decisions, tenant RLS, immutable review references and MFA-protected APIs. Review approval revalidates **current** source hashes and denies stale/superseded/incomplete evidence.
- Enhanced completeness to detect missing weekday DTR entries (conservative, not an official holiday/shift audit).
- New CI changes synthetic loan balances after capture to prove fingerprint invalidation, new capture requirement and payout NO-GO. Database regression tests enforce source review and bank-evidence immutability.
- Added Flyway V58 to protect **manual** bank-evidence amounts, references and final review decisions against database rewrite/deletion and enforce reviewer separation. This does **not** confirm a real bank payment.
- Personnel UI now exposes source freshness and independent review actions without payout controls.
- Details: `docs/M3_1_19_CERTIFICATION_REPORT.md`. **PRODUCTION NO-GO.**

## Outstanding production blockers (not completed by this PR)

1. Split-rate earnings for compensation changes mid-cutoff, day-based proration policy configuration, and complete leave/holiday eligibility including half-day leave and double holidays.
2. M3.1.12 adds the approved personnel separation-to-final-pay snapshot and independent approval, but automatic verified leave/loan/13th-month calculation, legal separation entitlement determination, personnel-to-legacy-payroll ID mapping, payslip issuance, disbursement and bank reconciliation remain outstanding.
3. Reviewed positive annual withholding differences can be submitted and independently approved into an open future pay period; automatic refund settlement, complete benefit-code classification, automated completeness checks, and actual year-end tax adjustment release certification remain outstanding.
4. Actual SSS/PhilHealth/Pag-IBIG agency-specific submission file layouts and BIR 1601-C, 2316 and alphalist exports, validated against official specs.
5. PostgreSQL/Flyway integration tests, multi-tenant authorization tests, browser E2E and real payroll acceptance scenarios.
6. Review all statutory seed data with counsel/payroll, legal-effective-date rates, holidays, regional wage orders, and privacy controls for employee bank accounts.
7. The M3.1 backend preview endpoints need matching frontend review workflows.

## Acceptance

Do not declare M3.1 complete or activate production payroll until the above gates are implemented and independently tested with validated real statutory tables, plus HR/payroll/legal/security sign-off.

## Reference

Pag-IBIG Circular No. 460 (low-income 1% employee share for <= PHP 1,500): https://www.pagibigfund.gov.ph/
Republic Act 10963 (shared 13th-month and other benefits exclusion cap): https://elibrary.judiciary.gov.ph/thebookshelf/showdocs/2/80559
