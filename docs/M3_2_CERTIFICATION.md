# M3.2 — Payroll & Timekeeping Integration Certification

## Baseline and dependency
- Base: `main` at `7bf2b308d3d759c370ab61210cba781dd8f1f494`.
- M3.1 Payroll Compliance Completion is being implemented separately. Do not merge M3.2 until M3.1 is merged, M3.2 is rebased, and all tests are rerun against the merged payroll rules.
- Current coverage is **service-level with mocked repositories**, not a real-PostgreSQL or browser E2E certification.

## Tests introduced
1. `PayslipSnapshotCertificationTest`: the generated payslip retains employee number, pay dates, earning/deduction lines, totals and net-pay arithmetic; subsequent employee-record edits do not change the serialized snapshot.
2. `BankCreditingCertificationTest`: approved/locked payroll bank export uses net pay, reports employees without matching bank accounts, and rejects draft runs.

## Required acceptance path
1. Provision isolated test tenants and realistic synthetic employee, pay group, compensation, holiday, schedule, leave and time-log fixtures.
2. Run attendance punches and approved corrections, leave and OT through the real API and PostgreSQL.
3. Process daily DTR and verify late, absence, rest day, holiday, night and approved OT minutes.
4. Create pay period and compute payroll; reconcile DTR-derived earnings/deductions and statutory amounts against independently calculated expected values.
5. Review, approve, lock; verify that DTR is frozen, a payslip is issued and amounts agree to the run and employee payroll lines.
6. Generate bank file, reconcile records and net amount, handle missing bank details, then mark payroll paid with audit evidence.
7. Verify recomputation rules, duplicate runs, unsuccessful transitions, employee joins/separations, month-end and cross-year boundaries, concurrency, and tenant isolation.
8. Capture test outputs, Flyway state, screenshots or API evidence, environment versions, and reviewer signoffs.

## Known blockers / observations
- The baseline only contains engine unit tests; no real-database E2E harness or CI workflow was observed.
- `PayrollService.computeEmployee` warns when DTR days are absent but currently proceeds to compute payroll; certification should determine whether missing/unprocessed DTR must block approval.
- `PayrollService.markPaid` sets the run to PAID; it does not independently validate bank settlement or payment confirmation.
- `BankFileService.generate` skips employees without a matching account; a nonempty skipped list must prevent an operations signoff until resolved.
- M3.1 will change payroll calculations, so expected financial fixtures must be revalidated afterward.

## Validation commands (when Maven and database are available)
```bash
cd backend
mvn -Dtest=PayslipSnapshotCertificationTest,BankCreditingCertificationTest test
mvn test
```
These commands are instructions; **no passing test claim** is made from this branch yet. The execution environment used for these GitHub edits could not clone GitHub via network and has no Maven executable.

## Certification decision
**NOT CERTIFIED / NO-GO** until the real PostgreSQL lifecycle, payroll compliance integration, payment evidence, CI checks and security isolation gates pass.
