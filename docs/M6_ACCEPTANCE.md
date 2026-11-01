# M6 Learning & Development — certification handoff

Status: **IMPLEMENTED IN DRAFT / NOT CERTIFIED**. Base: `main` at `7bf2b308d3d759c370ab61210cba781dd8f1f494`. Work branch: `feature/m6-learning-development`. PR: #6.

## Delivered
- M6.1 schema: tenant isolation (RLS/FORCE RLS), FK and check constraints, indexes.
- M6.2–M6.3 course/enrollment/skill/certification APIs with audit events and HR write RBAC + verified MFA.
- M6.4 learning workspace / navigation.
- M6.6 atomic enrollment state transitions, completion scores, certificate renewal, named company/employee selection.
- M6.7 identity-bound employee learning portal, explicit owner-only link operation with audit.
- M6.8 report API and UI: completion totals by course, upcoming expirations (60 days), proficiency level distribution, CSV export.
- M6.9 unit regression tests for validation and portal ownership.

## Required local regression commands
```bash
git fetch origin
git switch feature/m6-learning-development
git pull --ff-only origin feature/m6-learning-development

# Requires configured PostgreSQL + environment from backend/.env.example
cd backend
mvn clean verify

cd ../frontend
npm ci
npm run build
```

## Mandatory manual/integration verification
1. Start a fresh PostgreSQL database and run Flyway **with all currently applicable migrations present**. Assert V80/V81 execute with a restricted application DB role and tenant RLS on.
2. Test two separate tenants and ensure cross-tenant course, employee, enrollment, credential, and account-link identifiers fail safely.
3. Verify HR + MFA may write, unauthenticated and employee accounts cannot write, auditor reads only, and tenant owner with MFA can link identities.
4. Link one employee identity. Verify GET /api/v1/learning/me returns only that employee's records, including with guessed employee IDs and after tenant switches.
5. Course created; employee enrolled; ENROLLED -> IN_PROGRESS -> COMPLETED; ensure direct completion, reopening, and overwriting final states are rejected. Confirm score 0–100 and cancellation behavior.
6. Validate duplicate enrollment/skill constraints and certificate issue/expiry/renewal rules.
7. Exercise UI on desktop and mobile: company picker, employee picker, all sections, filters, reports and CSV downloads.
8. Run real API browser E2E with populated fixtures and refresh flows; no browser suite is yet added by this PR.
9. Check PR CI from the current M3.4 agent once integrated, with no existing CI workflow on the M6 branch baseline.

## Important concurrency and release blockers
- **Flyway ordering:** V80/V81 intentionally avoid V49+ in-flight work, but Flyway can reject pending lower-numbered migrations installed later. Coordinate all concurrent branches and renumber to the final contiguous deployment order before merge. Do not enable out-of-order migrations as a shortcut without a validated release plan.
- **Not production-certified:** Tests in this PR have not been executed in the connected environment. No successful Maven, TypeScript, PostgreSQL migration or browser E2E run can be claimed.
- The reported skill distribution is not a position-specific skills gap analysis. Integrate job/position target competencies in a later iteration.
- History is currently one enrollment per employee/course (retakes require a later enrollment-attempt model).
- CSV export contains tenant-licensed HR data; limit report exports to authorized HR/auditor access and follow internal retention practices.
- Enrollments are single-assignment; capacity, instructor assignment, scheduled sessions, approvals, reminders, certificate attachments and renewal notifications remain future enhancements.

## Merge policy
PR remains **draft**; no auto-merge. Reconcile main migrations and all agent work, run full regression and obtain relevant HR/security approval prior to accepting M6.
