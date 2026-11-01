# M3.1.19 — Cross-Source Review, Stale-Evidence Protection & Manual Bank Evidence Integrity

**Release decision: PRODUCTION NO-GO.** This milestone introduces financial *source-completeness* reviews, not legal final-pay certification, real bank confirmation, or permission to transfer funds.

## Delivered

### 1. Two-person financial source review (Flyway V57)
- `payroll_final_pay_source_review` links an immutable M3.1.18 financial source snapshot, its original SHA-256 fingerprint, and a human evidence reference to a requesting officer.
- `PENDING` may be decided only once by a **different** authenticated MFA-verified payroll officer or tenant admin, to `SOURCE_REVIEWED` or `REJECTED`.
- Forced tenant RLS, composite tenant FKs, unique review per snapshot, independent decision SQL constraint, immutable financial source attributes and guarded final decisions.
- `SOURCE_REVIEWED` only acknowledges the referenced completeness-control evidence; it is **not** final pay approval, statutory compliance certification, external-bank verification or payroll `PAID` transition.

### 2. Source freshness and partial cutoff controls
- Every review request and attempted positive approval re-queries actual legacy payroll/Personnel data, hashes the canonical JSON, compares it to the selected snapshot and rejects stale/superseded evidence.
- Positive review also requires mechanical `READY_FOR_INDEPENDENT_REVIEW` status; missing locked/paid payroll cutoff days, gaps in effective-dated compensation, open/unfinalized DTR, or missing weekday DTR records fail closed.
- Weekday-DTR detection is intentionally conservative; it can flag approved holidays or nonstandard schedules for manual resolution. It **does not prove** comprehensive attendance, legal leave eligibility, financial annualization or complete source accounting.
- Rejected and finalized decisions cannot be subsequently rewritten. Changes to any included source require a **new immutable snapshot** and new independent review.

### 3. Protected financial APIs
- `POST /api/v1/payroll/final-pay/source-reviews`: request independent source review.
- `POST /api/v1/payroll/final-pay/source-reviews/{id}/approve`: verify freshness, no mechanical gaps, different reviewer; record `SOURCE_REVIEWED`.
- `POST /api/v1/payroll/final-pay/source-reviews/{id}/reject`: independent rejection; remains immutable.
- `GET /api/v1/payroll/final-pay/source-reviews?settlementId={id}`: tenant-scoped review history.
- `GET /api/v1/payroll/final-pay/source-reviews/snapshots/{id}/freshness`: SHA-256 match, newest capture flag, source completeness status. Both response schemas always set `payoutCertified=false` and `disbursementEnabled=false`.

### 4. Manual external-bank-evidence write protection (Flyway V58)
- Bank proof entered by a user remains **manually reported**, not independently verified from a bank API or statement.
- PostgreSQL now disallows deletion and modification of evidence reference, amount, bank, value date, recorded actor or timestamp, and prohibits any changes after an independent decision. Decision transition validates the evidence reviewer is neither the recorder, preparer nor authorizer.
- The already existing amount equality and duplicate reference checks continue to apply. These protections safeguard recorded evidence but **cannot prove an actual bank transfer or account ownership**.

### 5. Personnel UI
- Displays latest snapshot freshness, source-review history, user-entered review reference and independent reviewer decision controls.
- Positive decision action is disabled unless the source is current and mechanically reviewable. Backend independently rechecks status and actor separation.
- No controls mark funds paid or initiate bank transfers.

## Automated verification
- Backend unit tests: two-person policy, final decision no-rewrite, source fingerprint and conservative weekday-DTR coverage.
- Real Spring Boot/PostgreSQL 16 authenticated HTTP regression: stale/duplicate requests, self-approval 403, cross-tenant access 404, MFA denial 403, incomplete-source approval denial 409, independent rejection, repeated decision rejection, and source review audit trail.
- Live synthetic loan ledger modification after capture: prior fingerprint becomes `STALE_SOURCE`, review request is rejected, new capture gets a new hash but remains incomplete/NO-GO.
- PostgreSQL migration smoke checks forced RLS, unique FK/identity constraints, immutable review trigger and bank evidence trigger; SQL tests verify direct UPDATE and DELETE attempts against decided source reviews and reviewed manual bank evidence are refused.
- Existing identity/payment concurrency checks, source-ledger arithmetic tests, and Playwright Chromium desktop/mobile Personnel workflow continue running in CI.
- Inspect latest successful GitHub Actions workflow in PR #1 for the exact green head commit and logs.

## Remaining production blockers
1. Genuine complete workday, holiday and schedule policy reconciliation and immutable signed source attestations (not merely a weekday heuristic), complete payroll periods, salary change handling, 13th-month accrued less previously paid, legal severance and authorized loan recovery.
2. Actual BIR year-end annualization, SSS/PhilHealth/Pag-IBIG filings, certified payslips, privacy and financial approvals.
3. Independent, authoritative **bank-provided** signed payment acknowledgments, account ownership, statement imports, bank ledger matching, failed/reversed transfer handling and idempotent `PAID` posting.
4. External WORM/signed checkpoints and privileged DBA threat model: SQL triggers and hashes alone are not immutable against a database superuser or someone able to alter both data and hash.
5. Multi-replica/fault/load certification, full browser financial negative-path coverage, HR/payroll/finance/legal/security/privacy/operations approval.

**Do not merge PR #1 automatically, authorize actual disbursements, or treat manual bank evidence as confirmed payment.**

## Recommended next priority
**M3.1.20 — External Financial Attestation & Period-Closure Acceptance.** Complete explicit, independent payroll period closing attestations and external proof provenance without enabling transfers or overwriting any review snapshots.
