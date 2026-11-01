# M3.1.14 — Final Pay Financial Certification & Regression Evidence

**Decision: PRODUCTION NO-GO.** This milestone adds automated certification checks; it does **not** certify a live disbursement system or enable bank money movement.

## Scope and source-of-truth
- Source branch: `feature/m3-1-payroll-compliance`, draft PR #1; do not merge automatically.
- Final-pay workflow: approved Personnel separation → required clearance and returned assets → payroll settlement → independent identity mapping → independently authorized payment **instruction** → manually reviewed external bank evidence.
- A payment instruction marked `AUTHORIZED` has **not** been transmitted to a bank. `EVIDENCE_REVIEWED` is a reviewer attestation, **not** confirmed external settlement, reconciliation or an official payslip.
- The Personnel and legacy Payroll employee tables remain distinct. Identity mapping is deliberate and requires matching company, employee number, legal names and hire date.

## Automated gates implemented in M3.1.14

| Gate | Test implementation | Expected certification finding |
|---|---|---|
| Backend correctness | Maven `test` | Java tests pass, including financial calculations, identity matching, approval segregation and bank-evidence checks |
| Frontend integrity | `npm ci` + `npm run build` | TypeScript and production build pass |
| Database migration validity | PostgreSQL 16 + Spring Boot startup | Flyway migrations through V55 and Hibernate startup pass |
| Database financial lifecycle | `backend/scripts/m3_1_14_postgres_e2e.sql` | Approved separation, final-pay settlement, identity link, primary bank, authorization and external evidence fixture can coexist |
| Idempotency | Same PostgreSQL script | Duplicate authorization per settlement and duplicate bank evidence reference rejected |
| Segregation of duties | Same PostgreSQL script + service unit tests | Self-approved identity, payment authorization and bank-evidence review rejected |
| Multi-tenancy | Same PostgreSQL script | Two tenants' data remain separate under a non-owner, non-BYPASSRLS role; mixed-tenant foreign keys/writes rejected |
| API perimeter | `.github/workflows/m3-payroll-compliance.yml` | Anonymous GET on financial APIs returns 401/403 |
| Fixture cleanup | Transactional SQL `ROLLBACK` | No test employees, bank evidence or payment instructions remain after run |

CI runs these checks on branch pushes and pull requests targeting `main`. For evidence, inspect the final green workflow run's **payroll-tests**, **frontend-build** and **postgres-migration-smoke** job logs. Earlier fixture failures exposed incorrect SaaS-required fields and the legacy employee status; these were corrected in the test fixture without weakening schema constraints.

## Out-of-scope or unverified gates (all block production acceptance)

- [ ] **Bank integration:** No real payment initiation, bank acknowledgements, authoritative settlement statements, returned-payment handling or bank-account ownership confirmation.
- [ ] **Legally issued final payslips:** The itemized statement remains a finance review artifact, not a generated, certified final payslip.
- [ ] **Source-ledger reconciliation:** Leave conversion, outstanding loans, accrued 13th month, tax adjustments and separation-pay entitlement still require independently validated complete source data; attestations are not automated proof.
- [ ] **Full authenticated HTTP and browser E2E:** CI includes database lifecycle and anonymous access, but not end-to-end authenticated two-user browser journeys through the actual UI.
- [ ] **Concurrency/stress testing:** Row locks and uniqueness are present, but a real simultaneous approval and payment-load drill has not been executed.
- [ ] **Production tenant-security assessment:** RLS regression is automated, but an independent penetration and restricted production-role assessment remains required.
- [ ] **Government filing and legal review:** Official BIR, SSS, PhilHealth and Pag-IBIG reporting/filing certification is not complete.
- [ ] **Human approvals:** HR, payroll, finance, legal/privacy, security and operations signatures are outstanding.
- [ ] **Operational controls:** Backup/restore, controlled deployment, key/secrets rotation, finance reconciliation runbooks and incident drills require evidence.

## Exit criteria for M3.1 production GO
1. Verify independent Personnel ↔ Payroll employee identity and bank-account ownership with signed evidence and reconciliation of every financial component to an immutable source ledger.
2. Execute two-user authenticated browser/API E2E (positive, wrong employee, other tenant, duplicate, stale request, incomplete clearance, cancelled transaction) against PostgreSQL with CI evidence.
3. Prove concurrent approvals and duplicate payment prevention under multiple application replicas and real restricted DB roles.
4. Integrate bank transport and confirm bank acknowledgement, returned-payment handling, official reconciliation statement ingestion, and exactly-once payment posting; do not infer payment success from locally entered evidence.
5. Issue and verify compliant final payslips, statutory records, and formal cross-functional approvals.
6. Promote only after all gates and the merged mainline full regression are successful.

**Recommendation:** Keep PR #1 draft and money release disabled. Treat current development as an automated financial-control pre-certification, not a production-complete final-pay system.
