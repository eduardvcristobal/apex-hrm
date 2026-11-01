# M3.1.15 — Authenticated Financial E2E, Concurrency & Reconciliation Pre-Certification

**Decision: PRODUCTION NO-GO.** Completed an authenticated financial **HTTP/API** end-to-end regression against a real Spring Boot Java 17 server and PostgreSQL 16. This does not constitute browser automation, live banking certification or authorization to release funds.

## Implemented

### 1. Segregation-of-duties security fix
`FinalPayBankEvidenceService` now disallows a bank-evidence reviewer who entered the evidence, prepared the payment instruction **or** authorized that instruction. This closes the former gap where a preparer could also review evidence. Unit regression tests cover each independent actor constraint.

### 2. Read-only financial reconciliation preflight
`GET /api/v1/payroll/final-pay/reconciliation/settlements/{settlementId}` is protected by payroll role + MFA. It examines approved settlement net, payment instruction status and amount, independent identity link, account ownership, primary-account status and external evidence status/amount. It returns a control status, independent internal consistency flag, `bankConfirmed=false` and `disbursementEnabled=false`. **Reviewed manual evidence is NOT bank confirmation.** No actual bank transfer, official payslip, posted payroll PAID state or loan settlement is triggered. The Personnel UI displays this status and the no-payment warning.

### 3. Correct HTTP status handling
`GlobalExceptionHandler` now preserves `ResponseStatusException` codes (notably 403, 404 and 409) rather than converting them to HTTP 500. Unit tests verify explicit statuses. Authenticated HTTP tests originally caught this actual backend defect.

### 4. Authenticated multi-user API E2E and approval races
New CI job `authenticated-financial-api` starts the actual Spring Boot server on PostgreSQL 16, seeds **synthetic CI-only** Personnel and Payroll employee data, an approved final-pay settlement, distinct payroll principals and hashed 64-character bearer tokens. It executes `backend/scripts/m3_1_15_authenticated_http.sh` using real HTTP calls against the Spring Security/Bearer/RLS stack:
- Denies anonymous and unverified-MFA access.
- Hides another tenant's settlement, identity and payment authorization.
- Requests and independently approves a Personnel ↔ Payroll employee identity match.
- Submits competing concurrent approvals from two authenticated officers; requires exactly one 200 and one 409.
- Selects only a masked primary account from the mapped payroll employee.
- Rejects duplicate payment preparation, self-authorization and another tenant's attempt to approve.
- Submits two concurrent payment approval requests; requires exactly one 200 and one 409.
- Rejects altered reported bank amounts, duplicates, evidence self-review and authorizer review.
- Permits only a separate officer to review submitted manual bank evidence, and prevents a second decision.
- Reports `MANUAL_EVIDENCE_REVIEWED` with `internallyConsistent=true` but **`bankConfirmed=false` and `disbursementEnabled=false`**.
- Checks the database has exactly one authorization and one reviewed evidence record.

The CI job runs on an ephemeral PostgreSQL service; it contains no real patient/employee banking data or payment network calls. The prior rollback-only SQL financial lifecycle suite, Maven tests, frontend build and Flyway migration/tenant RLS smoke tests remain separate required jobs.

## Verified CI evidence

- Successful authenticated API + Maven + frontend + PostgreSQL jobs: https://github.com/eduardvcristobal/apex-hrm/actions/runs/41275651451
- Logs include independent PASS assertions for 403/404/409, racing decisions, actor segregation and manual-only reconciliation.
- Initial failed authenticated run exposed `ResponseStatusException` being mapped to 500; corrected at code level, rerun green.
- No claim that a Playwright/real-browser journey or external banking-system contract test was executed.

## Outstanding mandatory gates

1. **Browser E2E:** Actual multi-user navigation, role switching and payroll/Personnel UX with Playwright on the real database, including responsive/accessibility checks.
2. **Multi-replica/load and deadlock testing:** A two-request CI race passed; a formal high-concurrency and fault-injection campaign across multiple replicas has NOT passed.
3. **Bank integration:** Real payment initiation/dispatch, external bank acknowledgement, reconciliation with authoritative signed bank statements, returned/unclaimed payment workflow, idempotent settlement posting and bank account ownership evidence.
4. **Source-of-truth payroll accounting:** Automatic earned 13th-month, tax annualization, leave balances, loans, separation eligibility, property recovery and immutable ledger reconciliation; currently reviewed monetary inputs.
5. **Official payslips and payout:** A review statement is not a legally issued payslip; no final payroll PAID transition or actual disbursement is implemented.
6. **Compliance and approvals:** BIR/SSS/PhilHealth/Pag-IBIG filing formats, HR/payroll/finance/privacy/security/operations signoffs, production restricted-DB-role review, operational recovery and pilot acceptance.

**Next recommendation:** Continue with certified source-ledger reconciliation and actual browser E2E before considering integration with a real bank. Keep PR #1 draft and production payout NO-GO.
