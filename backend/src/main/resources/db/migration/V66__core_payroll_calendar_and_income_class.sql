-- Expand payroll calendars and preserve the statutory regular/supplementary income classification.
ALTER TABLE pay_group DROP CONSTRAINT ck_pg_frequency;
ALTER TABLE pay_group ADD CONSTRAINT ck_pg_frequency
    CHECK (frequency IN ('MONTHLY','SEMI_MONTHLY','WEEKLY','DAILY'));
ALTER TABLE compensation DROP CONSTRAINT ck_comp_basis;
ALTER TABLE compensation ADD CONSTRAINT ck_comp_basis CHECK (pay_basis IN ('MONTHLY','DAILY','HOURLY'));

ALTER TABLE recurring_item ADD COLUMN income_class varchar(24) NOT NULL DEFAULT 'REGULAR';
ALTER TABLE adjustment ADD COLUMN income_class varchar(24) NOT NULL DEFAULT 'SUPPLEMENTARY';
ALTER TABLE payroll_line ADD COLUMN income_class varchar(24) NOT NULL DEFAULT 'SUPPLEMENTARY';
ALTER TABLE payroll_employee ADD COLUMN regular_taxable_income numeric(18,4) NOT NULL DEFAULT 0;
ALTER TABLE payroll_employee ADD COLUMN supplementary_taxable_income numeric(18,4) NOT NULL DEFAULT 0;
ALTER TABLE recurring_item ADD CONSTRAINT ck_recurring_income_class CHECK (income_class IN ('REGULAR','SUPPLEMENTARY'));
ALTER TABLE recurring_item DROP CONSTRAINT ck_recurring_cat;
ALTER TABLE recurring_item ADD CONSTRAINT ck_recurring_cat CHECK (category IN ('EARNING','DEDUCTION','EMPLOYER_COST'));
ALTER TABLE adjustment ADD CONSTRAINT ck_adjustment_income_class CHECK (income_class IN ('REGULAR','SUPPLEMENTARY'));
ALTER TABLE payroll_line ADD CONSTRAINT ck_payroll_line_income_class CHECK (income_class IN ('REGULAR','SUPPLEMENTARY'));
-- Income class is used for taxable earnings; deduction and employer-cost lines retain
-- a valid class value for schema consistency but are not classified as compensation.

ALTER TABLE employee ADD COLUMN prior_employer_name varchar(255);
ALTER TABLE employee ADD COLUMN prior_employer_tin varchar(32);
ALTER TABLE employee ADD COLUMN prior_employer_address text;
ALTER TABLE employee ADD COLUMN prior_employer_compensation numeric(18,4) NOT NULL DEFAULT 0;
ALTER TABLE employee ADD COLUMN prior_employer_taxable_compensation numeric(18,4) NOT NULL DEFAULT 0;
ALTER TABLE employee ADD COLUMN prior_employer_non_taxable_compensation numeric(18,4) NOT NULL DEFAULT 0;
ALTER TABLE employee ADD COLUMN prior_employer_tax_withheld numeric(18,4) NOT NULL DEFAULT 0;
ALTER TABLE employee ADD COLUMN prior_employer_certificate_received boolean NOT NULL DEFAULT false;

-- BIR Annex E daily and weekly withholding brackets, effective 2023-01-01.
INSERT INTO withholding_tax_bracket (effective_from, period_type, lower_bound, upper_bound, base_tax, rate) VALUES
 (DATE '2023-01-01', 'DAILY', 0, 685, 0, 0.00),
 (DATE '2023-01-01', 'DAILY', 685, 1096, 0, 0.15),
 (DATE '2023-01-01', 'DAILY', 1096, 2192, 61.65, 0.20),
 (DATE '2023-01-01', 'DAILY', 2192, 5479, 280.85, 0.25),
 (DATE '2023-01-01', 'DAILY', 5479, 21918, 1102.60, 0.30),
 (DATE '2023-01-01', 'DAILY', 21918, NULL, 6034.00, 0.35),
 (DATE '2023-01-01', 'WEEKLY', 0, 4808, 0, 0.00),
 (DATE '2023-01-01', 'WEEKLY', 4808, 7692, 0, 0.15),
 (DATE '2023-01-01', 'WEEKLY', 7692, 15385, 432.60, 0.20),
 (DATE '2023-01-01', 'WEEKLY', 15385, 38462, 1971.20, 0.25),
 (DATE '2023-01-01', 'WEEKLY', 38462, 153846, 7740.45, 0.30),
 (DATE '2023-01-01', 'WEEKLY', 153846, NULL, 42355.65, 0.35);

-- Hire-year proration is an employer policy setting on each leave type, not a statutory entitlement.
ALTER TABLE leave_type ADD COLUMN hire_proration_enabled boolean NOT NULL DEFAULT true;
CREATE UNIQUE INDEX uq_leave_hire_proration_once
    ON leave_ledger (tenant_id, employee_id, leave_type_id)
    WHERE reference_type = 'HIRE_PRORATION';
