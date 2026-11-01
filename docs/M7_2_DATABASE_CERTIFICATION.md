# M7.2 PostgreSQL / Flyway / Tenant Isolation Certification

**Baseline**: main commit `8d958e36c4a2dca1b03bde84c88fdef2a12b39f0`.  
**Branch**: `feature/m7-2-postgresql-flyway-tenant-certification`.  
**Release state**: **NO-GO until real CI evidence is green and reviewed**.

## Scope

1. Fresh PostgreSQL 16 startup from an empty database: Spring Boot/Flyway must reach V58 and pass Hibernate validation.
2. Controlled upgrade rehearsal: Flyway CLI first migrates to V53, the packaged application then applies V54–V58.
3. Inspect PostgreSQL catalog for enabled and FORCE RLS plus tenant-scoped USING/WITH CHECK policies on ten Performance/Learning tables.
4. Use a disposable NOBYPASSRLS role and two synthetic tenants to demonstrate SELECT filtering and denied cross-tenant INSERT/UPDATE/DELETE, missing context and context switching.
5. Preserve each job's Flyway history as a downloadable CI artifact.

## Source

- `scripts/db/m7_2_rls_certification.sql` — rollback-only policy/tenant isolation scenario; no permanent test records, privileges or roles.
- `.github/workflows/m7-2-database-certification.yml` — fresh migration + RLS test.
- `.github/workflows/m7-2-upgrade-rehearsal.yml` — V53→V58 migration rehearsal.

## Safety constraints

**Use only isolated disposable databases.** The script inserts synthetic tenant rows, creates an ephemeral probe role and issues DML inside a transaction, then rolls back. The script intentionally fails fast on policy breaks, incorrect Flyway version or missing context filtering. A successful clean run leaves no fixtures.

The migration/verification connection is a privileged CI administrator. Only the *RLS probe* switches to a restricted NOBYPASSRLS role. This is not sufficient to prove a separately configured production least-privilege application DB account, or that the production user cannot bypass RLS. Production certification needs an independently provisioned non-owner account, scoped grants and role-level connection tests.

The upgrade rehearsal reconstructs V53 on a new empty CI database, **not a restored production snapshot**. Real schema drift, production volumes, backup/restore, long-running locks, and historical checksums must still be tested with a sanitized snapshot before release.

The RLS probe intentionally covers ten new Performance/Learning tables and practical DML on `ld_skill`. The legacy payroll and Personnel schemas are not certified by that evidence. Enumerate all legacy tenant tables, perform cross-tenant API testing and verify row-level isolation for the existing tables separately.

## How to run against a disposable environment

```bash
cd backend
mvn -B -ntp -DskipTests package
# Run the app against your isolated PostgreSQL 16 instance with DB_URL / DB_USER / DB_PASSWORD.
# After it has migrated successfully to V58:
cd ..
PGHOST=localhost PGPORT=5432 PGUSER=hrm PGPASSWORD=hrm PGDATABASE=apex_hrm \
  psql -v ON_ERROR_STOP=1 -f scripts/db/m7_2_rls_certification.sql
```

## Required acceptance criteria

- Fresh job green and shows V58, every Flyway entry successful, no version collision.
- Upgrade job green and contains evidence of V53 followed by V58.
- RLS catalog asserts ENABLE/FORCE and tenant predicates on ten module tables.
- Restricted role cannot read or update tenant B while tenant A is active; cannot insert mismatched tenant ID.
- Empty tenant context returns no protected skill rows; changing context makes only target rows visible.
- No synthetic fixtures or probe role persist after script exit.
- Existing M3.4 suite remains passing.
- Outstanding M7.1 static Python test failure needs separate correction; M7.2 should not imply M7.1 certified.

## Remaining production gates

PostgreSQL app role least privilege, all legacy tenant table audits, full multi-tenant authenticated HTTP tests, payroll PII encryption, sensitive-data access review, rollout/rollback review, production-like upgrade timings and security/privacy/operations approval remain pending. Do not claim production readiness from this PR alone.
