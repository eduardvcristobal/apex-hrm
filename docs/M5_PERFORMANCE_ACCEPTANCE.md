# Apex HRM M5 — Performance Management implementation and acceptance

## Scope adapted from `feature/m5-performance-management`
- M5.1: Performance cycles, tenant-scoped goals/KPIs, reviews and development plans.
- M5.5: Explicit employee-to-login identity linking; self-service goal updates, draft self-assessment and review acknowledgment; manager access limited to linked direct reports.
- M5.6: Audited KPI progress history and normalized weighted attainment scores (goal-level attainment capped at 100%; nonpositive/null targets count as zero).
- M5.7: Review draft, manager submission, employee acknowledgment, HR finalization.
- M5.8: Development-plan PLANNED -> IN_PROGRESS -> COMPLETED, optional cancellation.
- M5.9: Route-level authorization wired. Branch authorization test files are not included in this adaptation.

## Important limitations
- HR-managed review creation remains available as a legacy one-step SUBMITTED endpoint; use draft->self-assessment->submit->acknowledge->finalize for the full workflow.
- No automatic notification, talent-calibration, employee appeal, progress approvals or certification of compliance.
- Explicit login/employee links are intentionally never guessed by employee email. HR must supply an existing tenant identity UUID.
- Performance schema migrations are V54–V56 on current `main`; do not rewrite their checksums after application.

## Remaining acceptance gates
1. Add and run focused backend authorization tests for HR, manager, employee, and cross-tenant access.
2. Run frontend route tests and browser flows for cycle creation, activation, KPI progress, self-assessment, review submission, acknowledgment, finalization, and plan completion.
3. On a disposable PostgreSQL database using a restricted runtime role, migrate V1–V56 and verify each performance RLS policy.
4. Test two tenants and verify cross-tenant employee, goal, review, plan, milestone, and evidence IDs cannot be read or changed.
5. Exercise role and MFA combinations, including changed manager-report relationships and concurrent KPI progress submissions.
6. Require the GitHub Actions checks to pass on the integrated branch and complete privacy/security review.

## Release decision
**NO-GO** until Flyway migration, backend/frontend build, role-level API integration, browser E2E, concurrency and privacy/security approval pass.

## M5.10–M5.16 continuation (2026-11-01)
- M5.10: Manager direct-report and active-cycle selector, review draft creation, review submission.
- M5.11: Revalidate current manager relationship upon submission, restrict team review inbox to the assigned reviewer, prevent completing plans with outstanding milestones.
- M5.12: Route-level MFA and tenant checks. **The branch's negative-authorization tests and live multi-tenant probes remain unadapted/unrun.**
- M5.13: HR goal editing while active, progress history access, preserve existing weight and metadata.
- M5.14: Individual development milestones with immutable evidence records (V56), completion lifecycle and self-service UI.
- M5.15: Cycle-level HR reporting: goals, review statuses, average rating.
- M5.16: Current `.github/workflows/m3-4-ci.yml` provides Maven, PostgreSQL/Flyway startup, frontend build, browser smoke, dependency audit and secret-scanning checks.

## Adaptation to current main
- The branch's V90/V91/V92 migrations were renumbered to V54/V55/V56 because current `main` had already applied through V53.
- The adapted migrations were applied to the local development PostgreSQL database; Flyway reported schema version V56.
- Backend main-source compilation and frontend production build passed. The backend health endpoint returned `UP` after startup.
- Backend tests and browser flows have not been run. The branch's controller test files are not included in this adaptation yet.

## Known certification gaps and limitations
- M5.16 is **not complete** until GitHub Actions succeeds and the M5-specific API and browser scenarios are exercised.
- Real authorization/tenant-isolation API tests, concurrent KPI updates, RBAC and MFA regression, and browser E2E are still required.
- Manager UI supports draft creation and submission, but dedicated HR calibration/appeals and multi-review approval are not implemented.
- Goal progress history UI currently indicates record count rather than exposing a detailed audit timeline.
- Do not renumber or rewrite V54/V55/V56 after they have been applied to shared databases; only add later forward migrations for schema corrections.
