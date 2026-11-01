# M3.3 — Security, Privacy and Multi-Tenant Hardening (draft)

Baseline: `main` at branch creation. Work is isolated on `feature/m3-3-security-privacy-tenancy`; M3.1 and M3.2 are independently under development. **Production acceptance: NO-GO.**

## Implemented, pending executable verification

- **M3.3.1 Tenant request context:** clear stale per-thread tenant context at the beginning of legacy HRM requests; reject disabled workspace principals; ignore client-supplied `X-Tenant-ID`. Added tests for valid, unauthenticated, disabled, spoofed and versioned requests.
- **M3.3.2 JDBC tenant boundary:** reject missing/sentinel tenants and cross-tenant `inTenant` calls where an authenticated tenant is bound. Preserve existing transaction-local PostgreSQL `set_config(..., true)` behavior. Added gateway tests.
- **M3.3.3 MFA privacy regression:** test AES-GCM randomized envelopes, confidentiality, tamper rejection, missing key failure and explicit prior-key rotation. Existing encryption format is unchanged.

- **M3.3.5 Payroll concurrency guard:** use JPA pessimistic write locking for payroll period run creation and payroll run lifecycle state mutations; prevent two transitions from validating the same stale status before writes. This is application-level defense, not yet a database uniqueness guarantee for all bank files or payslips.
- **M3.3.6 Real PostgreSQL RLS probe:** Testcontainers provisions PostgreSQL 16, runs the actual Flyway migrations, grants a restricted role, and tests branch tenant visibility, cross-tenant insert denial, and the expiry of transaction-local tenant scope.
- **M3.3.7 Payroll concurrency regression:** mock-backed service tests verify pessimistic locks are requested; PostgreSQL integration probes the period row lock under competing transactions.

## Mandatory verification commands

From `backend/`:

```sh
mvn -B test
mvn -B -Dtest=TenantFilterSecurityTest,TenantJdbcExecutorSecurityTest,MfaSecretCipherSecurityTest,PayrollLockingTest test
# Requires a working Docker daemon; Testcontainers skips when Docker is unavailable
mvn -B -Dtest=PostgresTenantRlsIntegrationTest test
```

These tests have **not** been run in this editing environment (no Maven/Docker runtime is available). A skipped Testcontainers test is not a security PASS. Passing them is required, not assumed. The repository baseline needs its own Maven and DB integration checks.

## Remaining M3.3 certification (NO-GO until evidenced)

1. **Authenticated integration tests** against a real PostgreSQL instance with two independent tenants, staff/payroll roles, MFA and explicit foreign-tenant identifiers across employee, DTR, payroll, payslip, bank export, GL and document APIs.
2. Expand the existing RLS policy coverage beyond `branch`/`audit_event` to all sensitive HR/payroll tenant tables and verify **RLS is actually enforced** for the application database role, including `FORCE ROW LEVEL SECURITY`, role privileges, transaction-pooling, tenant UUID reset and rejected direct SQL with no valid tenant.
3. Extend **concurrent payroll transitions** testing (compute/review/approve/lock/pay) to real end-to-end service calls with conflicting requests, asserting exactly-once bank files/payslips, immutable locked snapshots and no double deductions. The current PostgreSQL test demonstrates period-level row locking only.
4. Validate **audit trail integrity** with append-only database permissions, non-repudiation, retention and monitoring; current JPA audit logging alone does not certify tamper-proof storage.
5. Review PII **at rest and in transit** (bank account information, employment documents, statutory IDs, exported CSV files, backups), secrets injection and key rotation. MFA encryption alone is insufficient.
6. Add full **negative authorization and session/CSRF tests** for REST v1 and legacy routes; exercise unauthenticated, inactive, low privilege, MFA-not-verified, platform-admin and tenant-admin cases.
7. Run full backend regression, Flyway fresh-db upgrade and downgrade/backup drill, frontend build, browser E2E, vulnerability scan, and manually sign off security/privacy/HR operations.
8. Rebase and rerun certification when M3.1 and M3.2 reach `main`; do not present this independent branch as integrated payroll certification.

## Risks and scope notes

- `TenantJdbcExecutor` permits explicit tenant selection in non-request code with no `TenantContext`. Background jobs must obtain tenant ID from a trusted scheduler/service and must not accept tenant IDs directly from untrusted requests.
- Existing Spring Security matcher coverage, RLS policies, payment disbursement and audit-write permissions require separate live adversarial testing. Do not treat unit tests as a production security assessment.
- This branch intentionally avoids editing payroll arithmetic and M3.2 certification tests to reduce parallel-agent merge conflicts.
