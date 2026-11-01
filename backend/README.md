# Apex HRM (com.apex.hrm)

Multi-tenant HRM backend for the Philippines: **Personnel**, **Timekeeping**, **Payroll**.
Java 17, Spring Boot 3.3, PostgreSQL 16, Flyway, Hibernate discriminator multi-tenancy.

## Run

```bash
./run.sh                            # stop a conflicting Apex HRM process, then start the API on :8080
mvn spring-boot:run                  # start directly without the port check
mvn test                             # engine unit tests (DTR, premiums, SSS/PhilHealth/Pag-IBIG, withholding tax)
```

The API expects your local PostgreSQL server at `localhost:5432`, with database/user/password `apex_hrm / hrm / hrm` by default. Start PostgreSQL before running the API. The optional `docker-compose.yml` can start a containerized database if desired. Copy `.env.example` to `.env` for local-only service configuration; the backend loads this file when present.

Configure `HRM_MFA_ENCRYPTION_KEY` before enrolling MFA or saving encrypted Resend credentials; generate it with `openssl rand -base64 32` and keep it stable. Configure `GOOGLE_CLIENT_ID` and the matching frontend `VITE_GOOGLE_CLIENT_ID` for Google sign-in. Put platform administrator addresses in `PLATFORM_ADMIN_EMAILS`. SMTP settings are needed for password recovery and invitations. The backend env template documents optional backup requirements.

## Structure

| Package | Contents |
|---|---|
| `common` | tenant context + filter, base entities, generic CRUD/read controllers, error handling |
| `platform` | workspace identity, token auth, MFA, invitations, billing, platform administration, audit, backup, mail settings |
| `org` | companies, work locations (region, geofence), departments, cost centers, positions |
| `personnel` | employees, bank accounts, dependents, documents, employment history, effective-dated compensation, recurring items, loans |
| `timekeeping` | holidays, schedules, punches, corrections, DTR, leave (ledger), overtime |
| `payroll` | pay groups/periods, rule tables, runs, lines, adjustments, payslips, journal entries, bank files |
| `*.engine` | pure calculation classes (no Spring, no DB): `DtrCalculator`, `AttendancePayCalculator`, `Sss/PhilHealth/PagIbig/WithholdingTax` calculators |

Migrations V1–V8 create the original Apex HRM schema. Additive migrations V9–V49 add workspace identity and tenant isolation, MFA, invitations, billing, platform operations, secured personnel features, organization charts, and payroll settlement support. Existing Apex HRM tables and payroll/timekeeping endpoints remain in place.

## Multi-tenancy

Workspace access uses short-lived bearer tokens. The legacy `X-Tenant-ID` header is ignored: legacy `/api/**` HRM requests bind the tenant from the authenticated workspace identity. New identity and admin endpoints live under `/api/v1/**`; authorization and role checks are enforced by Spring Security. Create a workspace through `/api/v1/security/signup`, then use the returned bearer token. Platform administrator sign-in is Google-only and restricted to `PLATFORM_ADMIN_EMAILS`.

## Quick walkthrough

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/security/signup \
  -H 'Content-Type: application/json' \
  -d '{"workspaceName":"Acme Corp","email":"owner@acme.example","username":"acme-owner","password":"change-this-demo-password"}' | jq -r .value)
curl localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN"
# The owner enrolls an authenticator before using workspace administration.
```

Workflow endpoints: `/api/attendance/*`, `/api/dtr/process`, `/api/leave/*`, `/api/overtime/*`,
`/api/employees/{id}/compensation`, `/api/payroll/*`. Master data and read-only listings are generated under `/api/<resource>`.

## Payroll settlements (V49)

The payroll workspace supports final-pay runs for separated employees and a year-end annual tax adjustment in the December 31 payroll. Final pay requires the regular period containing the separation date to be locked. The year-end adjustment run replaces the regular run for the December 31 period and requires prior regular periods, 13th-month runs, and final-pay runs for that year to be locked or paid. These runs use the normal review, approval, lock, payslip, journal, and payment lifecycle.

The calculations currently provide a starting point for review. Final pay does not calculate the separation-month statutory contribution step, and minimum-wage earners are flagged for manual taxable-benefit review. The 13th-month exemption uses recorded non-taxable earning lines (excluding de minimis and tax refunds) to estimate the shared ₱90,000 benefit ceiling; verify benefit classifications, prior-employer income, leave conversion, and statutory deductions before approval. Do not treat the generated result as a substitute for payroll or tax review.

## Design rules worth knowing

* **Closed periods never change.** Locking a regular run locks the pay period and DTR days; corrections go through
  approved adjustments into a later run. Payslips are JSON snapshots taken at lock.
* **Rules are data.** Rates, brackets, premium multipliers and minimum wages live in versioned tables
  (`effective_from`). Add a row when a law changes; do not edit history.
* **Premium pay stacks multiplicatively:** hourly x day multiplier x overtime multiplier, with night differential as an
  extra 10% of the applicable rate. Overtime is paid only up to approved minutes and only if detected in the DTR.
* **GL/JE** are generated from `gl_mapping` per pay item and cost center; the generator refuses to produce an
  unbalanced entry or one with unmapped items.
* **Bank files** come from a per-tenant template (column order, delimiter, header). Bank-specific fixed-width layouts need a dedicated formatter.

## Not done yet / verify before production

* Google sign-in, email recovery, invitations, and platform-admin actions require their corresponding provider and secret configuration. Keep MFA encryption keys and OAuth credentials out of source control.
* **Statutory seed values must be verified** against current SSS, PhilHealth, Pag-IBIG circulars and the BIR table
  (`V7__reference_data.sql`). Only the NCR minimum wage is seeded. Holidays are entered per year from proclamations.
* Payroll gaps: final pay, cumulative/annualized withholding (only the strict table method), mid-period salary
  proration, holiday-pay eligibility (day-before rule), half-day leave, double holidays, Pag-IBIG 1% low-income rate,
  taxable excess of 13th month, government remittance files (SSS/PhilHealth/Pag-IBIG/BIR 1601-C, 2316, alphalist).
* Employee bank account numbers are stored as plain text; encrypt at rest and restrict access.
* New SaaS personnel tables use forced PostgreSQL row-level security through the transaction-local tenant executor. Existing Apex tables continue to use Hibernate tenant discrimination; production still needs a least-privileged application database role and tenant-isolation regression testing.
