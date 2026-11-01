# M3.1.17 — Source-of-Truth Final-Pay Accrual, DTR & Withholding Diagnostics

**Decision: PRE-CERTIFICATION ONLY — PRODUCTION FINANCIAL PAYOUT NO-GO.**

## Implemented
- Extended the existing tenant-scoped, MFA-protected `GET /api/v1/payroll/final-pay/source-ledger/settlements/{settlementId}` endpoint.
- For an approved Personnel-to-legacy-Payroll identity, retrieves **finalized/open DTR day counts**, absences, lateness minutes, undertime minutes and approved OT minutes through the approved separation effective date. Records with statuses other than `LOCKED`, `FINALIZED` or `APPROVED` are considered unfinalized. A missing or open DTR is explicitly flagged for review.
- Queries only `BASIC` earnings from `LOCKED`/`PAID` payroll lines whose full pay period falls in the separation tax year and ends by separation date. Calculates a **non-binding 13th-month candidate = locked BASIC / 12**, then subtracts previously recorded `THIRTEENTH_MONTH` lines as a residual diagnostic. This is **not** a statutory payment determination because partial periods, rate changes, earning categories and cutoff gaps may be unaccounted for. Missing payroll or a negative residual are always flagged.
- Summarizes historical `WTAX`/`WTAX_ANNUAL` deductions. No tax amount is marked certified: whenever lines exist, response states `ANNUAL_TAX_RECOMPUTATION_REQUIRED`; otherwise `WITHHOLDING_SOURCE_MISSING`. Actual Philippine BIR annualization and independent approval are still required.
- Retains M3.1.16's outstanding-loan vs final-pay claim comparison and convertible leave **days** review, using only verified legacy employee identity.
- Adds unit tests for incomplete DTR, overpaid candidate and the diagnostic 13th formula, and extends authenticated PostgreSQL/HTTP CI cases with one locked payroll run (`BASIC=14400`, previously paid 13th=`800`, recorded withholding=`200`), an open absent DTR day and a locked DTR with late/undertime minutes. The expected residual diagnostic is `400`; it must never enable payout.
- Displays these read-only DTR/accrual/tax diagnostic values and review blockers in the Personnel final-pay screen.

## Verification plan / evidence
- GitHub Actions payroll-tests: Maven unit tests, including DTR/source classification and 13th diagnostics.
- frontend-build: production TypeScript/Vite bundle.
- postgres-migration-smoke: Flyway PostgreSQL 16 + existing tenant/RLS and rollback-only settlement controls.
- authenticated-financial-api: real Spring Boot, PostgreSQL, multi-user MFA and payment approval races plus Chromium desktop/mobile final-pay navigation. New assertions verify the diagnostic amounts, open DTR days, and withheld-tax review required.
- Reference the latest successful GitHub Actions workflow on the branch for the completed evidence; earlier failures were caused by a CI shell assertion (`jq` syntax) and were corrected without changing financial source calculations.

## What is NOT yet certified
1. This is **source-backed diagnostic arithmetic, not a fully source-certified final-pay computation**. It cannot prove every cutoff and earning category is complete or that holidays, absences, overtime, rate changes and statutory entitlements have been correctly converted into peso amounts.
2. The 13th-month candidate has no statutory validity until eligible base pay, exclusions, effective compensation changes, leave periods and all prior payouts are independently reconciled against actual complete payroll records.
3. Historical withholding totals are not final tax obligations. Actual annualized tax must be recalculated using official BIR schedules effective for the relevant year, taxable-benefit classification, prior refunds, period completeness, and authorized adjustments.
4. No monetary leave-conversion rate, confirmed recovery authorization, final-pay entitlement adjudication, legally issued payslip, bank account ownership verification, bank transmission, real bank statement match, or final PAID payroll posting is provided.
5. No production authorization, legal/HR/payroll/finance/privacy/operations sign-off or full merged-mainline release acceptance has occurred.

**Do not enable real payments; do not represent the ledger preview as a legally issued final-pay settlement; keep PR #1 draft and do not merge automatically.**

## Recommended continuation
**M3.1.18 — Immutable Final-Pay Source Snapshots & Period Completeness Gate.** Record signed/approved effective-dated cutoff coverage, complete source lineage and final compensation adjustments, without permitting money movement until independent payroll/finance signoff and actual banking reconciliation.
