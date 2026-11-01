# M3.1.20 — External Financial Attestation & Period-Closure Acceptance

**Decision: INTERNAL EVIDENCE WORKFLOW IMPLEMENTED — PRODUCTION PAYROLL AND BANK RELEASE NO-GO.**

## Scope delivered

### M3.1.20.1 — Tenant-isolated document provenance and period acceptance records
Flyway `V59__final_pay_period_attestations.sql` adds `payroll_final_pay_period_attestation`, tied by tenant-specific FKs to an approved final-pay settlement, a preserved M3.1.18 source snapshot and a M3.1.19 independent source-review record.

Each registration includes a user-supplied declared issuer, document reference, lowercase SHA-256 digest, source snapshot digest, requester and state. Crucially, **`provenance_status=USER_DECLARED_UNVERIFIED` is immutable and cannot be promoted to bank-confirmed by this feature.** A user-entered digest is NOT evidence that the document exists at a trusted external source; no document contents are uploaded, no issuer certificate or signature is checked, and no bank network is contacted.

Database controls: forced RLS, unique attestation per snapshot, independent requester/reviewer checks, immutable provenance fields and irreversible final decisions, and comprehensive audit log entries.

### M3.1.20.2 — Multi-actor period-closure preflight
Protected endpoints:
- `POST /api/v1/payroll/final-pay/period-attestations`: register a declared provenance reference to a current, latest preserved source.
- `GET /api/v1/payroll/final-pay/period-attestations?settlementId=...`: tenant-isolated decision history.
- `POST /api/v1/payroll/final-pay/period-attestations/{id}/review`: internal-only review, requiring **SOURCE_REVIEWED** source record, live matching SHA-256, latest snapshot and complete mechanical cutoff evidence.
- `POST /api/v1/payroll/final-pay/period-attestations/{id}/reject`: record independent rejection, allowed when sources are incomplete.

Both positive and negative decisions require reviewer separation from the attestation requester, the snapshot capturer, and the independent source reviewer. One finalized decision cannot be rewritten. `INTERNALLY_REVIEWED` (where mechanically possible) is an internal metadata acknowledgement, **not** legal final-pay certification, official period closure, government filing, bank confirmation or authority to disburse funds.

All returned records explicitly set `issuerIndependentlyVerified=false`, `bankConfirmed=false`, `payoutCertified=false`, `disbursementEnabled=false`.

### M3.1.20.3 — Personnel frontend
Personnel → Employee Profile → HR Lifecycle → final pay now includes a period-closure evidence register. It displays self-declared issuer, SHA-256 digest, review history, conditions for an independent internal decision and payout NO-GO. It cannot issue banking instructions or mark payroll PAID.

### M3.1.20.4 — CI certification
The GitHub Actions workflow includes Java Maven tests, TypeScript/Vite build, PostgreSQL 16/Flyway startup + tenant/immutability constraints, authenticated synthetic multi-actor financial HTTP testing, and actual Chromium desktop/mobile navigation.

Added acceptance checks:
- Missing or malformed SHA-256 / insufficient document provenance rejected (400).
- One attestation per immutable snapshot; duplicates rejected (409).
- Independent requester/capturer/source reviewer separation enforced (403).
- Another tenant cannot review or list records (404); no MFA cannot read (403).
- A rejected or incomplete source record cannot produce internal accepted period closure (409).
- A distinct officer can independently reject (200); decisions cannot be repeated or rewritten (409).
- Direct PostgreSQL updates to decided document hash/status and deletion of the attestation are rejected.
- All financial response flags remain NO-GO; the existing stale-source mutation and browser E2E suites remain mandatory.

Reference the latest successful `feature/m3-1-payroll-compliance` GitHub Actions run for executable test evidence.

## Acceptance limitations / production blockers

1. **External evidence is not verified**: a declared issuer and claimed fingerprint do not establish external authenticity. An official bank-signed statement/certificate, trusted ingestion, signature verification, or reconciliation with a bank-controlled feed must still be implemented and independently accepted.
2. Payroll coverage calculations are conservative diagnostics. Statutory final-pay computations, effective compensation changes, holidays, DTR leave/day scheduling, BIR annualization, full payslip/legal separation entitlements and SSS/PhilHealth/Pag-IBIG government remittances are **not** legally certified.
3. `INTERNALLY_REVIEWED` denotes only a segregated internal record review, not an official `pay_period.status=CLOSED` transition or a `payroll_run.status=PAID` change. Neither is performed.
4. SHA-256 and PostgreSQL triggers deter ordinary app data manipulation but do not secure privileged database access without external WORM checkpoints and signed attestations.
5. Real funds must not be released until authoritative bank verification, reversal/exception settlement, disaster recovery, load tests and HR/payroll/finance/legal/privacy/security/operations approvals are complete.

**Production: NO-GO. PR #1 remains DRAFT and UNMERGED.**

## Recommended next
**M3.1.21 — Independent External Document Verification & Multi-Attestor Release Readiness.** Implement a provider-verified source-document retrieval adapter, offline/signed attestations and rigorous source-to-bank identity reconciliation; keep disbursement impossible until a genuine bank provider has been integrated and formally accepted.
