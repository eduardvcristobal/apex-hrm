ALTER TABLE work_location ADD COLUMN wage_sector varchar(40) NOT NULL DEFAULT 'NON_AGRI';
ALTER TABLE work_location ADD CONSTRAINT ck_work_location_wage_sector
  CHECK (wage_sector IN ('NON_AGRI','AGRI','DOMESTIC_WORKER'));

ALTER TABLE personnel_hr_case
  ADD COLUMN final_pay_due_date date,
  ADD COLUMN coe_requested_at timestamptz,
  ADD COLUMN coe_due_date date,
  ADD COLUMN coe_issued_at timestamptz;

UPDATE personnel_hr_case
SET final_pay_due_date = effective_date + 30
WHERE case_type = 'SEPARATION' AND status = 'APPROVED';

-- NCR agriculture rate under Wage Order NCR-28, effective 26 September 2026.
INSERT INTO wage_order(region_code,sector,daily_rate,effective_from,reference)
SELECT 'NCR','AGRI',718.00,DATE '2026-09-26','Wage Order NCR-28'
WHERE NOT EXISTS (
  SELECT 1 FROM wage_order WHERE region_code='NCR' AND sector='AGRI' AND effective_from=DATE '2026-09-26'
);
