# M3.3 — Security, Privacy and Multi-Tenant Hardening

Status: **in progress; not a production security certification**.

## Adapted runtime protections

- Legacy HRM requests clear thread-local tenant context before resolving the caller and reject inactive workspace principals. Tenant scope continues to come from the authenticated workspace identity; `X-Tenant-ID` is ignored.
- `TenantJdbcExecutor` rejects missing/sentinel tenant IDs and prevents a request bound to one workspace from issuing raw SQL under another workspace. Trusted background work may supply an explicit tenant when no request context is bound.
- Payroll period rows are locked while regular and final-pay runs are created. Payroll run rows use the existing tenant-scoped pessimistic lock for lifecycle transitions, serialized against settlement and bank workflows.

## Certification gates still open

1. Add and run focused tenant filter, JDBC gateway, MFA cipher, and payroll locking regression tests.
2. Run real PostgreSQL RLS integration probes with a restricted application role and verify all sensitive tenant tables, not only branch/audit examples.
3. Exercise authenticated cross-tenant identifiers across personnel, timekeeping, payroll, payslip, bank export, general ledger, and document APIs.
4. Test concurrent lifecycle transitions end to end, including exactly-once payslip/bank export creation and immutable locked payroll snapshots.
5. Review audit storage integrity, PII at rest and in transit, secrets injection and rotation, session/CSRF behavior, and backups/restores.
6. Run full backend and frontend regression, fresh-database migration, browser end-to-end, and vulnerability checks before production approval.

Passing unit tests alone does not certify tenant isolation or production readiness. Reassess this work after the payroll milestone changes have landed on `main`.
