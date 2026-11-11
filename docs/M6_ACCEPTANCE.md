# M6 Learning & Development — acceptance handoff

Status: **IMPLEMENTED; LOCAL ACCEPTANCE COMPLETE (2026-11-11)**.

## Delivered

- Tenant-scoped course catalog, employee enrollment, skill catalog and assessment, certifications and renewal.
- Forced RLS on learning tables, tenant-composite foreign keys, uniqueness/check constraints, and audit events.
- HR writes require an HR administrator or tenant administrator with verified MFA. Read access is limited to HR administrators and auditors; the employee portal is identity-bound.
- Tenant-scoped company/employee pickers, enrollment workflow, completion/expiry reports and CSV export.
- Owner-only employee identity linking, with an audit event. `/api/v1/learning/me` derives the employee solely from the authenticated identity.
- Additional employee-ID lookup is tenant scoped and returns 404 for an ID outside the active workspace.
- The course form names its company picker “Company” rather than exposing an internal-ID label.

## Verification performed (2026-11-11)

- Backend `mvn -q -DskipTests compile`: passed.
- Frontend production build (`npm run build`): passed; Vite reports the existing large-bundle advisory.
- Existing Playwright owner journey: passed across all modules, including Learning & Development and My learning.
- Fresh PostgreSQL 16 database: all 65 Flyway migrations applied successfully.
- Forced-RLS probe with a non-owner, non-superuser, non-`BYPASSRLS` role: tenant A saw only its own Learning row; an insert carrying tenant B's ID was rejected by row-level security.
- Focused Learning JUnit tests in Linux/Docker: 12 tests passed, 0 failures (3 portal tests and 9 controller tests).
- Populated Learning Playwright flow (`npm run test:e2e:learning`): passed. It creates a course and employee, enrolls and completes the course with score 96, links the employee account, and verifies the employee portal, report and CSV download, and 390px mobile layout.
- Role checks passed: owner writes with verified MFA; owner writes without verified MFA denied; HR administrator writes denied without MFA and allowed with MFA; HR administrator account linking denied; employee catalog/picker reads and writes denied while `/me` shows only the linked employee's completed course; auditor reads/reports allowed and writes/account linking denied; anonymous reads denied.
- Two-tenant API checks passed: a tenant cannot resolve another tenant's employee ID or create a course for another tenant's company.
- Enrollment transition SQL now casts the nullable score parameter to numeric. The populated workflow confirmed `ENROLLED → IN_PROGRESS → COMPLETED` and persisted score 96 on PostgreSQL 16.
- Configured PostgreSQL account `hrm` is not a superuser and does not have `BYPASSRLS`; it has ordinary table grants for Learning tables.
- The local app database is migrated through V65.

## Deferred product enhancements

Scheduled sessions, instructor assignment, capacity, approval flows, reminders, certificate attachments, renewal notifications, retake attempts, and position-specific skills-gap analysis are outside the delivered M6 scope.

## Acceptance result

**Local Learning acceptance complete.** The deferred enhancements above remain outside this delivered scope. Apply the normal integrated CI and deployment release checks before production rollout.
