# M6 Learning & Development — acceptance handoff

Status: **IMPLEMENTED; LOCAL ACCEPTANCE PARTIAL**.

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
- Configured PostgreSQL account `hrm` is not a superuser and does not have `BYPASSRLS`; it has ordinary table grants for Learning tables.
- The local app database is migrated through V65.

## Acceptance gaps

- The owner journey is a navigation tour; it does not create a populated Learning fixture or exercise the full course → enrollment → completion → employee portal path in the browser.
- The owner journey and database-level RLS probe do not replace a complete two-tenant API authorization test (HR/MFA writes, employee/auditor denials, and account linking).
- Manual desktop/mobile and export-content inspection remain to be done in a prepared acceptance workspace.

## Deferred product enhancements

Scheduled sessions, instructor assignment, capacity, approval flows, reminders, certificate attachments, renewal notifications, retake attempts, and position-specific skills-gap analysis are outside the delivered M6 scope.

## Release decision

**Not fully certified for release** until the populated Learning browser workflow and role-level API checks (including employee/auditor denial and account linking) pass. Fresh-database migration, Learning unit tests, and core forced-RLS isolation now pass.
