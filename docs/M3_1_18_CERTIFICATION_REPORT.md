# M3.1.18 — Append-Only Final-Pay Source Snapshots and Period Completeness Gate

**Production certification: NO-GO.** Implemented immutable-at-application/database-trigger-level source evidence and conservative cutoff review, **not** an executable final-pay banking system or legally certified final-pay computation.

## Delivered

1. **Flyway V56:** `payroll_final_pay_source_snapshot`, tenant-composite FKs to approved settlement, legacy payroll employee and recorder, forced RLS, immutable UPDATE/DELETE rejection trigger, SHA-256 digest uniqueness per settlement. Repeated capture of unchanged evidence is rejected (409); changed sources permit a new historical snapshot, never overwriting a previous one.
2. **MFA/role-protected API:** `POST /api/v1/payroll/final-pay/source-snapshots/settlements/{settlementId}` captures a new immutable source document; `GET` lists up to 100 captures, rejects other-tenant settlements with 404, and recomputes SHA-256 from canonical stored JSON on every read.
3. **Canonical source evidence** includes the approved final-pay settlement and separation case plus the verified employee's payroll run and cutoff IDs/statuses, payroll line IDs/amounts, DTR days/statuses, effective-dated legacy compensation records, loans, leave entries and pending/approved adjustments. The source scope is January 1 of the separation year or hire date (whichever is later) through the separation effective date.
4. **Coverage gate:** counts days not covered by a `LOCKED` or `PAID` payroll run with the employee in the run; counts days missing an effective legacy compensation record; counts DTR records not LOCKED/FINALIZED/APPROVED. Absence of any DTR yields `INCOMPLETE_SOURCE`. Even when these mechanical checks are clean, `REVIEW_REQUIRED` remains: business eligibility, tax annualization, workday schedules, source ledgers and statutory entitlements are not independently certified.
5. **Personnel UI:** manually capture financial source snapshots and inspect completeness findings, SHA-256 digest and bank payout disabled status. Does not replace final-pay financial or government submission approval.
6. **Regression:** Unit tests for inclusive cutoff gaps, overlapping periods, compensation gaps and SHA-256 determinism; authenticated PostgreSQL HTTP tests for unlinked identity denial, snapshot creation, repeat prevention, missing pay/compensation coverage, open DTR, SHA-256, tenant isolation and MFA; PostgreSQL checks for forced RLS, immutable trigger, duplicate identity and actual row UPDATE/DELETE rejection. Existing identity/payment concurrency and Chromium desktop/mobile tests remain mandatory.

## Evidence
- GitHub PR #1: https://github.com/eduardvcristobal/apex-hrm/pull/1 (draft; do not merge).
- Latest CI job `authenticated-financial-api` is intended to exercise real Spring Boot/PostgreSQL and Playwright Chromium for desktop 1440px and mobile 390px.
- Files: `backend/src/main/resources/db/migration/V56__final_pay_source_snapshot.sql`, `backend/src/main/java/com/apex/hrm/payroll/service/FinalPaySourceSnapshotService.java`, `frontend/src/FinalPayReleaseReviewPanel.tsx`, `backend/scripts/m3_1_18_snapshot_schema.sql`.

## Control limitations and production NO-GO
- Hash and append-only trigger provide tamper detection against **ordinary application/database data writes**, not a cryptographic signed ledger or defense against a privileged database administrator who can disable triggers or rewrite the database. External WORM storage, signed checkpoints and independent audit exports are not implemented.
- The coverage check sees legacy payroll periods and compensation dates, not guaranteed complete workday/DTR attendance. Duplicate/overlapping pay runs, source-category policies, changing compensation, retroactive adjustments, partial cutoffs, paid/unpaid leave conversions and BIR final tax still require explicit reconciliation. `REVIEW_REQUIRED` is never transformed into `PAYOUT_CERTIFIED`.
- An approved settlement is a manually reviewed finance figure, not automatic legally verified severance, DTR, 13th-month, loan recovery, separation entitlement or payroll payout.
- There is no real bank API, bank account ownership verification, certified final payslip, bank statement import, government filing submission or financial PAID transition. Human HR, payroll, finance, privacy, legal, security and operations approvals are required.
- No real production data, real bank transfer or actual payout is created or executed by these tests.

**Recommended next milestone: M3.1.19 — Cross-Source Financial Certification Workflow & External Evidence Integrity.** Add independently approved period-completeness attestations and source version controls; integrate bank statement evidence only through separately verified authoritative sources; keep payout disabled until legal, bank and human acceptance are complete.
