# Apex HRM M5 — Performance Management implementation and acceptance

## Scope implemented on feature/m5-performance-management
- M5.1: Performance cycles, tenant-scoped goals/KPIs, reviews and development plans.
- M5.5: Explicit employee-to-login identity linking; self-service goal updates, draft self-assessment and review acknowledgment; manager access limited to linked direct reports.
- M5.6: Audited KPI progress history and normalized weighted attainment scores (goal-level attainment capped at 100%; nonpositive/null targets count as zero).
- M5.7: Review draft, manager submission, employee acknowledgment, HR finalization.
- M5.8: Development-plan PLANNED -> IN_PROGRESS -> COMPLETED, optional cancellation.
- M5.9: Authorization-negative unit tests and route authorization wired.

## Important limitations
- HR-managed review creation remains available as a legacy one-step SUBMITTED endpoint; use draft->self-assessment->submit->acknowledge->finalize for the full workflow.
- Manager dashboard currently reads direct-report goals/reviews; the manager draft/submit APIs exist, but manager-specific creation/submission UI is not yet complete.
- No automatic notification, talent-calibration, employee appeal, locked review exports, goal editing, progress approvals or certification of compliance.
- Explicit login/employee links are intentionally never guessed by employee email. HR must supply an existing tenant identity UUID.
- M5 migrations are numbered V90 and V91 to avoid immediate branch numbering conflicts. Do **not** apply them to a shared database until the concurrently developed M3 migrations and Flyway ordering are reconciled.

## Required acceptance gates (not yet executed)
1. Run Maven tests: `cd backend && ./mvnw test` (or `mvn test` if the wrapper is absent).
2. Run frontend tests and build: `cd frontend && npm ci && npm run build`; run project test script if configured.
3. On a disposable PostgreSQL database with restricted runtime role, migrate V1..V91 using Flyway and verify schema + RLS policies. Confirm all previously deployed migration checksums match.
4. Test two tenants: same tenant sees only its own employees and goals; cross-tenant IDs yield no records or errors.
5. Verify admin, HR, manager, employee and auditor permissions, with and without MFA, including direct-report changes.
6. Test concurrent KPI progress submissions and immutable history/audit events.
7. Browser-test cycle creation, activation, KPI progress, review self-assessment, submission, acknowledgment, finalization and development plan completion.
8. Integrate M3.1–M3.4 main branch after those PRs merge; resolve migration number and conflicts; rerun the full CI suite.

## Release decision
**NO-GO** until Flyway migration, backend/frontend build, role-level API integration, browser E2E, concurrency and privacy/security approval pass.

## M5.10–M5.16 continuation (2026-11-01)
- M5.10: Manager direct-report and active-cycle selector, review draft creation, review submission.
- M5.11: Revalidate current manager relationship upon submission, restrict team review inbox to the assigned reviewer, prevent completing plans with outstanding milestones.
- M5.12: Route-level MFA and tenant checks; negative authorization tests. **Live multi-tenant penetration/regression tests not yet run.**
- M5.13: HR goal editing while active, progress history access, preserve existing weight and metadata.
- M5.14: Individual development milestones with immutable evidence records (V92), completion lifecycle and self-service UI.
- M5.15: Cycle-level HR reporting: goals, review statuses, average rating.
- M5.16: GitHub Actions workflow `.github/workflows/m5-performance.yml` for Maven tests, PostgreSQL Flyway startup, and frontend TypeScript/Vite build.

## Known certification gaps and limitations
- M5.16 is **not complete** until the latest commit's GitHub Actions checks succeed and manual/automated browser scenarios are added and executed.
- Vitest and Playwright are not installed in the existing frontend package; the CI workflow does not claim these suites pass.
- Real authorization/tenant-isolation API tests, concurrent KPI updates, RBAC and MFA regression, and browser E2E are still required.
- Manager UI supports draft creation and submission, but dedicated HR calibration/appeals and multi-review approval are not implemented.
- Goal progress history UI currently indicates record count rather than exposing a detailed audit timeline.
- M5 V90/V91/V92 ordering must be reconciled with M3 migrations before merging to `main`; never rewrite already-applied Flyway checksums.
- Keep draft PR #5 until all full-system gates pass and HR/security approval is recorded.
