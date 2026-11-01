# M7.4 — Full Payroll E2E Certification (integrated main)

**Baseline**: `main` commit `8d958e36c4a2dca1b03bde84c88fdef2a12b39f0` (2026-11-01).

**Release decision: NO-GO until green persisted E2E, multi-tenant HTTP tests, statutory sign-off and production-like bank evidence.**

## Scope and provenance

This branch restores previously unported real PostgreSQL certification scenarios from the divergent M3.2 branch without modifying or merging that branch. All tests run only when `HRM_TEST_DB_URL` is provided; the new CI job pins it to disposable database `apex_hrm_m74_cert`. Do not point any test at a live or shared database. The tests intentionally create synthetic employee records and financial transactions.

| Scenario | Expected coverage |
|---|---|
| `PostgreSqlPayrollSchemaCertificationTest` | Flyway schema, tenant columns and DTR uniqueness |
| `PayrollAuthorizationHttpTest` | Deny employee payout, unverified payroll MFA, unauthorized DTR processing and spoofed `X-Tenant-ID` |
| `PostgreSqlPayrollLifecycleE2ETest` | Personnel → attendance punches → DTR → payroll compute/review/approve/lock → snapshot payslip → bank-file generation → bank credit import → dual-person settlement confirmation → PAID → reversal dispute; separate tenant sees no DTR/payroll |
| `PostgreSqlPartialBankRecoveryE2ETest` | Two-person bank credit with partial acceptance/rejection, disallow premature PAID and over-acknowledgement, create a one-employee recovery export, import recovered credit, reconcile, mark PAID |

## CI

`.github/workflows/m7-4-payroll-certification.yml` runs a fresh PostgreSQL 16 service, all production/test Java source compilation, the four real E2E classes and captured Surefire/Flyway migration evidence. Existing `m3-4-ci.yml` remains in place for frontend/backend/smoke/dependency scans.

Commands for a **dedicated disposable** local PostgreSQL instance:

```sh
export HRM_TEST_DB_URL=jdbc:postgresql://localhost:5432/apex_hrm_m74_cert
export HRM_TEST_DB_USER=hrm
export HRM_TEST_DB_PASSWORD='your-test-only-password'
cd backend
mvn -B -ntp -Dtest=PostgreSqlPayrollSchemaCertificationTest,PayrollAuthorizationHttpTest,PostgreSqlPayrollLifecycleE2ETest,PostgreSqlPartialBankRecoveryE2ETest test
```

## Important baseline and limitations

The baseline main is at V58 and does **not** include PR #9's V59 employee bank-account encryption or PR #10's V60/V61 encrypted bank CSV/retention work, both of which remain draft. This milestone certifies the **main-integrated legacy payroll workflow**. After those PRs merge, all real database scenarios must be adapted to the encrypted schema and rerun against the merged branch. They **must not** be counted as successful production encryption tests.

The bank settlement tests use human-recorded bank acknowledgments and synthetic bank responses; they do not confirm that a real bank credited funds. Passing integration tests do not certify statutory withholding rates, all holiday types, security of live bank file custody, a production user without DB owner privileges, disaster recovery or formal human approval.

## Acceptance gates

- CI restores, compiles and executes *all* four test classes (not just Maven compile) and publishes JUnit/Surefire reports.
- Full persisted lifecycle validates DTR locked status, payslip snapshot amount, export totals, bank import evidence, dual control, payout block before confirmation, reversal dispute and tenant negative checks.
- Partial recovery exports only rejected employees and prevents duplicate accepted credits.
- Unauthorized roles and absent MFA receive HTTP 403 even if attempting a tenant-header spoof.
- Full repo M3.4 regression remains green on this PR head.
- Following merge of dependencies, rerun tests against encrypted V59–V61 data paths.
- Approval required from payroll, HR, finance, privacy/security and operations. Until then: **NO-GO**.

## Coordination

Work on `feature/m7-4-payroll-full-e2e-certification`, draft PR against main. **Never merge or modify another agent's branch**. Original M3.2 and all other branch histories remain untouched.
