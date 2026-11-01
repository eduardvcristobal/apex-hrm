## M3.2.7 — Manual settlement and payment reconciliation

- Migration V49 creates a tenant-scoped payment acknowledgment ledger with unique bank-file and bank-reference constraints.
- New API endpoints: POST /api/payroll/runs/{runId}/settlements, POST /api/payroll/settlements/{id}/confirm, GET /api/payroll/runs/{runId}/settlements. Existing payroll MFA RBAC applies.
- Recording requires an exported file and exact amount; confirmation requires a different authenticated user and evidence reference. Bank-file generation by itself is **not** payment.
- A pessimistic run lock and confirmed-settlement sum protect LOCKED → PAID against unconfirmed, incomplete, duplicated and overpaid amounts.
- PARTIAL is a reconciliation state, not a completed payroll payment. Zero-net runs require a separate no-payment approval pathway and remain blocked by the current PAID gate.
- A submitted bank reference is **manual operator evidence, not independent bank verification**. Finance must check the actual bank acknowledgment. The application does not transfer money.
- Coverage includes unit negative cases, partial outstanding amounts, and PostgreSQL end-to-end premature-pay rejection followed by dual-control reconciliation.
- Final certification remains **NO-GO** until M3.1 regression, finance/HR signoff, authenticated browser full journey, multi-bank scenarios, and external settlement verification where applicable.

# M3.2 — Payroll & Timekeeping Integration Certification

## Baseline and dependency
- Base: `main` at `7bf2b308d3d759c370ab61210cba781dd8f1f494`.
- M3.1 Payroll Compliance Completion is being implemented separately. Do not merge M3.2 until M3.1 is merged, M3.2 is rebased, and all tests are rerun against the merged payroll rules.
- Current coverage is **service-level with mocked repositories**, not a real-PostgreSQL or browser E2E certification.

## M3.2.6 follow-up work (2026-11-01)

- Added `PayrollAuthorizationHttpTest`: verifies payroll operations reject an ordinary employee, payroll officer without MFA, and that DTR processing rejects payroll-only and non-MFA HR roles. The spoofed `X-Tenant-ID` header does not grant payroll access. This is targeted endpoint RBAC; not M3.3-wide security certification.
- Added `frontend/e2e/public-routes.spec.ts` and `.github/workflows/m3-2-browser-smoke.yml`: Chromium real-browser smoke on login, invitation acceptance and signup routes, plus frontend TypeScript build. **Public-route smoke is not authenticated payroll browser E2E.**
- Payment settlement remains **unimplemented/unverified**: `PayrollService.markPaid` advances status after LOCKED without independent bank acknowledgment. Until settlement evidence, reconciliation policy and dual control are specified and implemented, do **not** equate PAID with proof that money was transferred.
- GitHub CI for these new gates must be green before claiming them passed. The code is committed but certification stays **NO-GO** regardless of CI result until M3.1, M3.3 and financial/operational gates close.

## M3.2.6 execution evidence (2026-11-01)

- **Latest verified green commit:** `5aa815a81147763928e78009ea2463eca11104d7`
- **GitHub Actions success:** https://github.com/eduardvcristobal/apex-hrm/actions/runs/41266535479
- **Database:** ephemeral PostgreSQL 16 CI service; 48 Flyway migrations validated.
- **Lifecycle test:** `PostgreSqlPayrollLifecycleE2ETest` uses real Spring repositories/services with synthetic tenant, employee, schedule, compensation, punches, and bank account. Exercises authenticated DTR and payment HTTP endpoints via MockMvc, DTR/payroll financial reconciliation, locked attendance safeguards, stored payslip JSON, bank-file totals and cross-tenant access verification.
- **Critical finding fixed for covered path:** the real-database test reproduced cross-tenant payroll run ID reads with an independent thread/session. Explicit tenant filtering was added to `PayrollRunRepository.findById`, then verified by CI. **This is not proof that other repositories are tenant-safe**; carry repository-wide `findById`/bulk-operation isolation assessment into M3.3.
- **Initial failures addressed:** synthetic SaaS tenant/company required fields and MockMvc request-local tenant context cleanup.
- **Scope boundary:** this is a *backend integration and selected authenticated HTTP E2E* gate, not a complete Playwright/browser scenario, multi-replica concurrency gate, independent bank settlement confirmation, or full statutory M3.1 acceptance. Keep overall production certification at **NO-GO**.

## Additional M3.2.2–M3.2.5 coverage (committed and CI verified)
- `AttendanceDtrCertificationTest`: attendance punches become DTR minutes; locked DTR is not recomputed.
- `DtrPayrollReconciliationTest`: DTR minute-to-pay amount arithmetic; approved vs unapproved overtime.
- `PayrollLifecycleCertificationTest`: review → approve → lock → paid; payslip issuance and DTR freeze; invalid order rejected.
- `PostgreSqlPayrollSchemaCertificationTest`: opt-in isolated PostgreSQL Flyway migrate/validate and tenant-column/schema assertions.
- `.github/workflows/m3-2-certification.yml`: Maven tests with PostgreSQL 16 service and JUnit evidence upload.

These tests are an incremental certification scaffold. The M3.2.6 test additionally exercises selected real endpoints through Spring Security and TenantFilter. Bank file creation is not proof of bank settlement.

## Tests introduced
1. `PayslipSnapshotCertificationTest`: the generated payslip retains employee number, pay dates, earning/deduction lines, totals and net-pay arithmetic; subsequent employee-record edits do not change the serialized snapshot.
2. `BankCreditingCertificationTest`: approved/locked payroll bank export uses net pay, reports employees without matching bank accounts, and rejects draft runs.

## Required acceptance path
1. Provision isolated test tenants and realistic synthetic employee, pay group, compensation, holiday, schedule, leave and time-log fixtures.
2. Run attendance punches and approved corrections, leave and OT through the real API and PostgreSQL.
3. Process daily DTR and verify late, absence, rest day, holiday, night and approved OT minutes.
4. Create pay period and compute payroll; reconcile DTR-derived earnings/deductions and statutory amounts against independently calculated expected values.
5. Review, approve, lock; verify that DTR is frozen, a payslip is issued and amounts agree to the run and employee payroll lines.
6. Generate bank file, reconcile records and net amount, handle missing bank details, then mark payroll paid with audit evidence.
7. Verify recomputation rules, duplicate runs, unsuccessful transitions, employee joins/separations, month-end and cross-year boundaries, concurrency, and tenant isolation.
8. Capture test outputs, Flyway state, screenshots or API evidence, environment versions, and reviewer signoffs.

## Known blockers / observations
- The baseline only contains engine unit tests; no real-database E2E harness or CI workflow was observed.
- `PayrollService.computeEmployee` warns when DTR days are absent but currently proceeds to compute payroll; certification should determine whether missing/unprocessed DTR must block approval.
- `PayrollService.markPaid` sets the run to PAID; it does not independently validate bank settlement or payment confirmation.
- `BankFileService.generate` skips employees without a matching account; a nonempty skipped list must prevent an operations signoff until resolved.
- M3.1 will change payroll calculations, so expected financial fixtures must be revalidated afterward.

## Validation commands (when Maven and database are available)
```bash
cd backend
mvn -Dtest=PayslipSnapshotCertificationTest,BankCreditingCertificationTest test
mvn test
```
These commands are instructions; **no passing test claim** is made from this branch yet. The execution environment used for these GitHub edits could not clone GitHub via network and has no Maven executable. CI success has been verified at the M3.2.6 SHA above. For a local isolated PostgreSQL test, set HRM_TEST_DB_URL, HRM_TEST_DB_USER and HRM_TEST_DB_PASSWORD before running `mvn test`.

## Certification decision
**NOT CERTIFIED / NO-GO** until the real PostgreSQL lifecycle, payroll compliance integration, payment evidence, CI checks and security isolation gates pass.
