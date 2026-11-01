# M7.5 — Performance & Learning Integration Certification

**Baseline:** `main` commit `8d958e36c4a2dca1b03bde84c88fdef2a12b39f0` (Flyway V58), independent of unmerged PRs #7–#11.

**Status:** **CI certification required; human production acceptance outstanding.** Test fixtures contain only synthetic organizations, people and training records. No live employee data.

## What is certified

This branch restores **three** missing M5 tests from the divergent `feature/m5-performance-management` branch without modifying that branch. Two existing M6 unit tests, `LearningControllerTest` and `LearningPortalControllerTest`, were already present on main and are rerun, not copied. Two new database-backed integration classes cover the actual integrated M5/M6 code:

- `PerformanceLearningPostgresE2ETest`: two independent synthetic workspaces, company and employee records, activated performance cycle, weighted KPI goal/attainment, employee performance review, learning course, enrollment, progress and completion, skill registration and course/enrollment reads. Verifies tenant B sees none of tenant A's records, cannot create a cycle/course against tenant A's company, and that attempting to switch the authenticated tenant through `TenantJdbcExecutor` fails.
- `PerformanceLearningAuthorizationHttpTest`: actual Spring Security / MockMvc, denying anonymous performance mutation, employee-initiated HR cycles/courses, manager without MFA, HR without MFA, spoofed `X-Tenant-ID`, and unscoped employee access to tenant-wide learning catalog.
- `scripts/db/m7_5_performance_learning_rls.sql`: verifies that all **14** integrated M5/M6 tenant tables have both RLS and FORCE RLS enabled, and a tenant-scoped USING and WITH CHECK policy. Also verifies successful Flyway history at V58.

## CI

The new `.github/workflows/m7-5-performance-learning.yml` uses PostgreSQL 16 with local-only, throwaway trust authentication to avoid storing even a synthetic database password in Git. It executes all seven test classes (three restored M5 unit classes, two already-integrated M6 unit classes and two new integration classes), checks RLS and exports JUnit/Flyway evidence. The existing M3.4 full-repo regression still applies.

Only execute manually against a **dedicated, disposable** database:

```sh
export HRM_TEST_DB_URL=jdbc:postgresql://localhost:5432/apex_hrm_m75_cert
export HRM_TEST_DB_USER=hrm
export HRM_TEST_DB_PASSWORD=''
cd backend
mvn -B -ntp -Dtest=PerformanceControllerTest,PerformanceEnhancementsControllerTest,PerformanceWorkflowControllerTest,LearningControllerTest,LearningPortalControllerTest,PerformanceLearningPostgresE2ETest,PerformanceLearningAuthorizationHttpTest test
cd ..
psql -v ON_ERROR_STOP=1 -f scripts/db/m7_5_performance_learning_rls.sql
```

## Acceptance restrictions / gaps

- These tests exercise application authorization and authentic PostgreSQL tenant SQL but do **not** substitute for a real separate least-privilege non-owner database role test and complete UI/browser HR + manager/employee E2E acceptance.
- Performance scoring rules and skills/certificates must receive HR acceptance, accessibility review and Philippine privacy/legal approval.
- Existing draft PRs #9/#10 change financial security schema; retest the complete integrated application on their eventual merge. Do not consider their workflows certified from M7.5 alone.
- The synthetic PostgreSQL service uses trust authentication on a temporary localhost CI port; do **not** copy that configuration to production, shared testing, or exposed servers.
- No branch merge, production migration execution, live employee data access, or agent branch changes are authorized by this milestone.

**Production remains NO-GO** until integrated release readiness, operational recovery and human sign-offs.
