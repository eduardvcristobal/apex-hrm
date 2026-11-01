# M6 Learning & Development — certification handoff

Status: **ADAPTED / NOT CERTIFIED** on current `main`.

## Delivered
- M6.1 schema: tenant isolation (RLS/FORCE RLS), FK and check constraints, indexes.
- M6.2–M6.3 course/enrollment/skill/certification APIs with audit events and HR write RBAC + verified MFA.
- M6.4 learning workspace / navigation.
- M6.6 atomic enrollment state transitions, completion scores, certificate renewal, named company/employee selection.
- M6.7 identity-bound employee learning portal, explicit owner-only link operation with audit.
- M6.8 report API and UI: completion totals by course, upcoming expirations (60 days), proficiency level distribution, CSV export.
- M6.9 unit regression tests for validation and portal ownership.

## Adaptation to current main
- The branch's V80/V81 migrations were renumbered to V57/V58 after the integrated performance migrations V54–V56.
- The employee account association now has a tenant-composite foreign key for `linked_by`, in addition to the tenant-scoped identity and employee constraints.
- Backend test sources compiled, backend startup applied V57/V58 to local development PostgreSQL, and the health endpoint returned `UP`.
- The frontend production build passed. Backend unit tests and browser flows have not been executed.
- Existing `.github/workflows/m3-4-ci.yml` covers Maven/PostgreSQL startup, frontend build, browser smoke, npm audit and secret scanning.

## Remaining manual/integration verification
1. On a fresh PostgreSQL database with restricted runtime role, migrate V1–V58 and verify all learning RLS policies.
2. Test two separate tenants and ensure cross-tenant course, employee, enrollment, credential, and account-link identifiers fail safely.
3. Verify HR + MFA may write, unauthenticated and employee accounts cannot write, auditor reads only, and tenant owner with MFA can link identities.
4. Link one employee identity. Verify GET /api/v1/learning/me returns only that employee's records, including with guessed employee IDs and after tenant switches.
5. Course created; employee enrolled; ENROLLED -> IN_PROGRESS -> COMPLETED; ensure direct completion, reopening, and overwriting final states are rejected. Confirm score 0–100 and cancellation behavior.
6. Validate duplicate enrollment/skill constraints and certificate issue/expiry/renewal rules.
7. Exercise UI on desktop and mobile: company picker, employee picker, all sections, filters, reports and CSV downloads.
8. Run real API browser E2E with populated fixtures and refresh flows.
9. Require the integrated GitHub Actions workflow to pass.

## Important concurrency and release blockers
- **Migration history:** V57/V58 are applied locally. Do not rewrite their checksums; use forward migrations for later schema changes.
- **Not production-certified:** Unit tests, restricted-role RLS checks, two-tenant API tests, and learning browser E2E still need execution.
- The reported skill distribution is not a position-specific skills gap analysis. Integrate job/position target competencies in a later iteration.
- History is currently one enrollment per employee/course (retakes require a later enrollment-attempt model).
- CSV export contains tenant-licensed HR data; limit report exports to authorized HR/auditor access and follow internal retention practices.
- Enrollments are single-assignment; capacity, instructor assignment, scheduled sessions, approvals, reminders, certificate attachments and renewal notifications remain future enhancements.

## Release decision
**NO-GO** until the remaining database, tenant-isolation, role/MFA, browser and HR/security acceptance checks pass.
