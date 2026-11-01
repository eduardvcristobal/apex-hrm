-- Payroll settlement: final pay and year-end tax adjustment runs, holiday-pay eligibility, annual tax table.

-- 1. New run types. FINAL_PAY runs belong to one separated employee.
ALTER TABLE payroll_run ADD COLUMN employee_id uuid REFERENCES employee(id);
CREATE INDEX idx_payroll_run_employee ON payroll_run(employee_id);
ALTER TABLE payroll_run DROP CONSTRAINT ck_run_type;
ALTER TABLE payroll_run ADD CONSTRAINT ck_run_type
  CHECK (run_type IN ('REGULAR','OFF_CYCLE','THIRTEENTH_MONTH','FINAL_PAY','YEAR_END_ADJUSTMENT'));
ALTER TABLE payroll_run ADD CONSTRAINT ck_run_final_pay_employee
  CHECK (run_type <> 'FINAL_PAY' OR employee_id IS NOT NULL);

-- 2. Holiday pay eligibility is decided when DTR is processed (worked or on paid leave the day before).
ALTER TABLE dtr_day ADD COLUMN holiday_pay_eligible boolean NOT NULL DEFAULT true;

-- 3. Annual withholding table (TRAIN, effective 2023-01-01): tax = base_tax + rate x (taxable - lower_bound).
-- VERIFY against the current BIR schedule before relying on year-end adjustments.
INSERT INTO withholding_tax_bracket (effective_from, period_type, lower_bound, upper_bound, base_tax, rate) VALUES
 (DATE '2023-01-01', 'ANNUAL',       0,  250000,       0, 0.00),
 (DATE '2023-01-01', 'ANNUAL',  250000,  400000,       0, 0.15),
 (DATE '2023-01-01', 'ANNUAL',  400000,  800000,   22500, 0.20),
 (DATE '2023-01-01', 'ANNUAL',  800000, 2000000,  102500, 0.25),
 (DATE '2023-01-01', 'ANNUAL', 2000000, 8000000,  402500, 0.30),
 (DATE '2023-01-01', 'ANNUAL', 8000000,    NULL, 2202500, 0.35);

-- 4. Pay items and GL mapping for the new codes, for workspaces that already exist.
INSERT INTO pay_item_type (tenant_id, code, name, category, taxable, system_item)
SELECT t.id, v.code, v.name, 'EARNING', v.taxable, true
FROM tenant t CROSS JOIN (VALUES ('LEAVE_CONVERSION','Conversion of unused leave',true),('TAX_REFUND','Refund of excess tax withheld',false)) AS v(code,name,taxable)
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO gl_mapping (tenant_id, item_code, debit_account_id, credit_account_id)
SELECT a.tenant_id, 'LEAVE_CONVERSION', a.id, NULL FROM gl_account a WHERE a.code = '5100'
ON CONFLICT (tenant_id, item_code) DO NOTHING;
INSERT INTO gl_mapping (tenant_id, item_code, debit_account_id, credit_account_id)
SELECT a.tenant_id, 'TAX_REFUND', a.id, NULL FROM gl_account a WHERE a.code = '2130'
ON CONFLICT (tenant_id, item_code) DO NOTHING;
