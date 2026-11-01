# M3.1.16 — Final Pay Source-Ledger Reconciliation & Browser E2E

**Decision: FINANCIAL SOURCE REVIEW IMPLEMENTED; PRODUCTION NO-GO.**

## Scope delivered

### M3.1.16.1 — Tenant-scoped source-ledger comparison
New service `FinalPaySourceLedgerService` and MFA-protected read-only endpoint:
`GET /api/v1/payroll/final-pay/source-ledger/settlements/{settlementId}`.

- Requires an **approved** `personnel_employee` → legacy `employee` identity mapping before touching source-ledger details.
- Reads `payroll_line` joined to a `LOCKED` or `PAID` `payroll_run` and its `pay_period`, restricted to the separation tax year and dates through the effective separation. Reports number of lines, previously paid `THIRTEENTH_MONTH`, and `WTAX` / `WTAX_ANNUAL` deductions.
- Compares settlement `outstanding_loans` with the summed balances of `ACTIVE` loan records for the verified legacy employee. Explicitly flags `LOAN_BALANCE_MISMATCH` and missing source loan records.
- Sums convertible `leave_ledger` balances by days from `leave_type.convertible=true` through separation. Does **not** multiply days into PHP without legally approved eligibility and conversion rates.
- Returns comparative gross source values, declared final-pay amounts, review statuses and required action list. It always reports `certificationStatus=REVIEW_REQUIRED` and `disbursementEnabled=false`. The source snapshots are NOT final payslips or posting instructions.
- Exposes the source totals and warnings in Personnel's approved separation/final-pay review UI.

**Critical accounting distinction:** historical 13th-month paid on locked payroll is NOT proof of the residual 13th-month accrual owed. The complete earning period, eligible salary basis, prior payouts, separation date, tax and other adjustments must be independently reconciled. Outstanding loans may require separate loan-deduction approvals. Historical leave days cannot be converted to cash without the correct pay basis and policy. Absence of source lines/loans is never proof of a complete ledger.

### M3.1.16.2 — Authenticated source-ledger integration regression

The existing Spring Boot/PostgreSQL 16 authenticated finance workflow now additionally verifies:
- Before identity approval, source-ledger access fails closed with `IDENTITY_UNVERIFIED`.
- After identity approval, the endpoint reads the verified payroll ID, but no locked payroll records report `PAYROLL_SOURCE_MISSING`.
- A seeded `ACTIVE` loan balance of **PHP 700** versus settlement loan deduction of **PHP 500** results in `LOAN_BALANCE_MISMATCH`.
- A convertible leave type with **4.5** days produces `CONVERSION_RATE_AND_ELIGIBILITY_REVIEW_REQUIRED`, not a calculated cash payout.
- Tenant B cannot read tenant A's source review (HTTP 404).
- All of the above remain NO-GO.

### M3.1.16.3 — Real Chromium browser E2E

Added `frontend/e2e/m3_1_16_browser.cjs`, executed in the `authenticated-financial-api` GitHub Actions job after real Java backend, PostgreSQL and Vite start. Playwright's Chromium browser verifies:
- Desktop **1440×900** and mobile **390×844** authenticated navigation from the People directory → employee profile → HR lifecycle → approved separation → source-ledger and payment NO-GO panels.
- Review-required financial source data is presented without offering actual bank disbursement.
- Guest users cannot view the protected Personnel financial details.
- Browser page errors are monitored during these journeys.

All data, accounts, employee names, bank references and token hashes used in CI are **synthetic fixtures**; no real money is moved. The browser step installs Playwright Chromium at runtime and executes against actual Vite/Java/PostgreSQL, not mocked APIs.

## Regression evidence

GitHub Actions workflow branch `feature/m3-1-payroll-compliance` requires:
1. Java/Maven unit tests (including source-ledger status comparison).
2. React/TypeScript production build.
3. PostgreSQL 16 Flyway startup, tenant-RLS schema assertions and rollback-only SQL lifecycle regression.
4. Authenticated financial HTTP workflow, multi-actor concurrent approvals, source-ledger mismatch cases, and desktop/mobile Chromium browser verification.

Successful run following the source-mismatch fixture: https://github.com/eduardvcristobal/apex-hrm/actions/runs/41276959210

## Acceptance limitations and production NO-GO

- **Unresolved:** no complete automatic legal final-pay calculation from all effective-dated payroll/timekeeping source records. Need authoritative pro-rata earnings, actual DTR, leave policy, 13th-month accrued less paid, tax annualization, loan/advance recovery eligibility and severance pay statutory eligibility.
- **Unresolved:** no certified official final payslip; no real banking integration, account-ownership confirmation, bank acknowledgment, paid/unpaid settlement reconciliation, double-disbursement drill or live payout.
- **Unresolved:** no formal multi-replica stress/load performance campaign; existing concurrent actor races pass but do not prove sustained scale.
- **Unresolved:** browser E2E covers the Personnel final-pay review workflow and two viewport sizes; it does not yet automate all finance screens and all negative paths in a browser.
- **Unresolved:** official BIR/SSS/PhilHealth/Pag-IBIG filings and production legal/payroll/finance/security/operations approvals.
- The read-only source review must not be treated as payout authorization or substitute for human certification of complete ledgers.

**Next recommended milestone:** M3.1.17 — Source-of-Truth Final-Pay Accrual, DTR and Withholding Certification. Build source-anchored full accrual/eligibility logic and referenceable immutable snapshots, with negative and positive certified sample cases, before discussing live bank disbursement.

**Do not merge PR #1 automatically, turn on live payouts, or declare M3.1 production-ready.**
