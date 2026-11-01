# M7.1 — Integrated main branch reconciliation and certification inventory

**Baseline:** `8d958e36c4a2dca1b03bde84c88fdef2a12b39f0` (2026-11-01).  
**Development branch:** `feature/m7-1-integration-readiness`.  
**Release decision:** **NO-GO** until CI and live integration gates below are green.

## Read-only reconciliation of the six divergent PR branches

The original PRs remain open, with code adapted into main through separate commits. Ahead/behind numbers describe **Git ancestry only**, not unmerged feature counts. Do not merge these historical branches directly, cherry-pick migrations, rewrite applied migration checksums, or force-push the agents' branches.

| PR | Legacy branch | Ahead / behind current main | Main integration and remaining risk |
|---|---|---:|---|
| #1 | `feature/m3-1-payroll-compliance` | 133 / 7 | Main contains payroll review/Pag-IBIG updates (V50); branch additionally has V49–V60 advanced final-pay workflow, provider/issuer proof and many tests. **Not integrated or certified**; audit services and regulatory evidence separately before porting. |
| #2 | `feature/m3-2-payroll-timekeeping-certification` | 78 / 7 | Main contains settlement V51–V53 and associated controllers. Branch has conflicting V49–V51 migrations, extensive PostgreSQL/E2E tests and dedicated workflows not incorporated. Adapt tests to main's schema, do not transplant the old SQL. |
| #3 | `feature/m3-3-security-privacy-tenancy` | 16 / 7 | Main includes tenant filter, executor and payroll locks. Branch contains regression tests; M7.1 restores tenant and MFA cases. PostgreSQL RLS/locking tests still require Docker-capable certification. |
| #4 | `feature/m3-4-automated-regression-ci` | 10 / 7 | Main has `.github/workflows/m3-4-ci.yml`, Vitest and unauthenticated Playwright smoke. Run and check this workflow; do not count unauthenticated smoke as payroll E2E. |
| #5 | `feature/m5-performance-management` | 41 / 7 | Main adapts V90–V92 to V54–V56 and Performance APIs/UI. Branch's controller negative tests were omitted; M7.1 restores `PerformanceWorkflowControllerTest`. M5 authorization/browser/RLS certification pending. |
| #6 | `feature/m6-learning-development` | 27 / 7 | Main adapts V80/V81 to V57/V58 with Learning APIs/UI. Some unit tests are present, but live restricted-role RLS, tenant-bound identity and browser coverage remain uncertified. |

## Migration namespace reconciliation

Main's currently adapted sequence is V49 (previous payroll settlement and annualization), V50 (Pag-IBIG), V51–V53 (bank response, settlement and recovery), V54–V56 (Performance), V57–V58 (Learning). The original branches reuse V49–V60 / V80–V81 / V90–V92 for different SQL, creating **known cross-branch conflicts**. Comparing only version numbers will not establish equivalent semantics.

**Safe integration rule:** any still-needed branch schema feature must be reconciled against the current *schema* and added as a new **forward-only** migration above the latest applied version; first verify deployed `flyway_schema_history` checksums. Never rename an already applied migration in a shared environment.

`scripts/integration_guard.py` enforces unique versioned Flyway filenames within the checked-out tree, detects obvious duplicated annotated controller mappings and checks that relevant security matchers and final deny-all rules remain present. It is deliberately a static preflight **not** an actual Flyway execution, Spring handler registration test, endpoint-level RBAC proof or a check of all historical PR branch SQL. Its unit suite is `scripts/tests/test_integration_guard.py`.

## Restored regression coverage

- `TenantFilterSecurityTest`: ignores spoofed `X-Tenant-ID`, clears thread-local tenant context, rejects unauthenticated and disabled identities.
- `MfaSecretCipherSecurityTest`: AES-GCM random IV, tampering/wrong-key rejection and explicit key rotation.
- `PerformanceWorkflowControllerTest`: prevents unauthorized employee links, manager-team access, review finalization and KPI writes.

These test files were copied from the isolated legacy branches without modifying those branches. They **must pass compilation and Maven execution** on the integrated source; no local Maven execution is claimed by this audit.

## API and permission review

- Current `SecurityConfig` uses scoped `/api/v1/learning/**` and `/api/v1/performance/**` matchers and final deny-all, plus privileged legacy payroll routes.
- Static scanning detects exact duplicate (HTTP verb, literal path) annotations but does not resolve path variable aliases, method-specific `@RequestMapping`, runtime handler registration or Spring matcher precedence.
- Required next gate: start backend with two isolated tenant fixtures; exercise owner, HR, payroll officer, manager, employee, auditor and anonymous identities; assert 401/403 and no cross-tenant reads/writes for every sensitive endpoint.
- Continue review of the `EmployeeBankAccount.accountNo` storage path and protect sensitive payroll bank data at rest.

## CI and release gates

M7.1 introduces `.github/workflows/m7-1-integration.yml` running the Python guard and restored Maven negative tests. Existing M3.4 workflow remains unchanged. **Pending:** actual green GitHub Actions runs, fresh and upgrade PostgreSQL/Flyway checks, Testcontainers RLS certification, real authenticated Playwright cases, payroll bank reconciliation, and human security/HR/payroll sign-offs.

### Validation commands

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/integration_guard.py --json
cd backend && mvn -B -ntp -Dtest=TenantFilterSecurityTest,MfaSecretCipherSecurityTest,PerformanceWorkflowControllerTest test
cd ../frontend && npm ci && npm run build && npm run test:unit && npm run test:e2e
```

## Next priority

M7.2 should perform a disposable PostgreSQL V1–V58 fresh migration, controlled upgrade from a pre-integration snapshot, RLS validation under restricted DB credentials, and evidence-backed backfill of remaining branch tests. M3.1 extended final-pay features must not be treated as integrated merely because a prior PR is open.
