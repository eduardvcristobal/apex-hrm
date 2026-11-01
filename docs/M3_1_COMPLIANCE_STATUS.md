# M3.1 — Philippine Payroll Compliance (in progress)

Base: main @ 7bf2b308d3d759c370ab61210cba781dd8f1f494
Branch: feature/m3-1-payroll-compliance

## Implemented on this branch

- GitHub Actions CI verifies backend Maven unit tests and frontend TypeScript/Vite build.
- Partial monthly pay cutoffs use inclusive calendar-day proration of hire/separation dates, with a separate PRORATION deduction included in gross and taxable earnings calculations.
- Assignment changes do not create false proration. Intra-cutoff compensation changes fail closed until split-rate reconciliation is implemented.
- Earned basic pay for 13th-month computation subtracts PRORATION.
- Final-pay preview endpoint POST /api/payroll/final-pay/preview provides a reviewed gross/deduction/net worksheet. It does not disburse pay or infer benefit entitlement.
- Annualized tax preview endpoint POST /api/payroll/tax/annualization-preview uses caller-provided verified annual brackets, cumulative withholding, and the shared exemption ceiling for 13th-month and other bonuses. It does not alter payroll runs or submit BIR returns.
- Effective-dated Pag-IBIG low-income employee contribution rate via migration V49, calculator and Tax & Compliance UI.
- GET /api/payroll/runs/{id}/remittance-review?agency=SSS|PHILHEALTH|PAGIBIG produces an INTERNAL review CSV (not a government-approved upload file).
- Unit regressions cover proration, final-pay arithmetic, annualization arithmetic, Pag-IBIG threshold and CSV formula injection.

## Outstanding production blockers (not completed by this PR)

1. Split-rate earnings for compensation changes mid-cutoff, day-based proration policy configuration, and complete leave/holiday eligibility including half-day leave and double holidays.
2. End-to-end final-pay workflow: approved leave/asset/loan clearance, separation pay eligibility, employee-year statutory reconciliation, approval, payslip settlement and payout.
3. Actual year-to-date tax accumulation and automatic annualized withholding/refunds in payroll runs, including 13th-month and all other benefits across pay periods.
4. Actual SSS/PhilHealth/Pag-IBIG agency-specific submission file layouts and BIR 1601-C, 2316 and alphalist exports, validated against official specs.
5. PostgreSQL/Flyway integration tests, multi-tenant authorization tests, browser E2E and real payroll acceptance scenarios.
6. Review all statutory seed data with counsel/payroll, legal-effective-date rates, holidays, regional wage orders, and privacy controls for employee bank accounts.
7. The M3.1 backend preview endpoints need matching frontend review workflows.

## Acceptance

Do not declare M3.1 complete or activate production payroll until the above gates are implemented and independently tested with validated real statutory tables, plus HR/payroll/legal/security sign-off.

## Reference

Pag-IBIG Circular No. 460 (low-income 1% employee share for <= PHP 1,500): https://www.pagibigfund.gov.ph/
Republic Act 10963 (shared 13th-month and other benefits exclusion cap): https://elibrary.judiciary.gov.ph/thebookshelf/showdocs/2/80559
