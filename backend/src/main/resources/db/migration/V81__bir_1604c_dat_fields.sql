ALTER TABLE company ADD COLUMN branch_code varchar(4);

ALTER TABLE employee
  ADD COLUMN branch_code varchar(4),
  ADD COLUMN region_num varchar(4),
  ADD COLUMN nationality varchar(30),
  ADD COLUMN bir_employment_status varchar(2),
  ADD COLUMN bir_reason_separation varchar(2),
  ADD COLUMN bir_substituted_filing char(1),
  ADD COLUMN minimum_wage_factor_used integer,
  ADD COLUMN prior_nontax_basic_smw numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_nontax_holiday_pay numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_nontax_overtime_pay numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_nontax_night_diff numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_nontax_hazard_pay numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_nontax_13th_month numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_nontax_deminimis numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_nontax_sss_etc numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_nontax_salaries numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_taxable_basic_salary numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_taxable_13th_month numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN prior_taxable_salaries numeric(18,4) NOT NULL DEFAULT 0;

ALTER TABLE company ADD CONSTRAINT ck_company_branch_code CHECK (branch_code IS NULL OR branch_code ~ '^[0-9]{4}$');
ALTER TABLE employee ADD CONSTRAINT ck_employee_branch_code CHECK (branch_code IS NULL OR branch_code ~ '^[0-9]{4}$');
ALTER TABLE employee ADD CONSTRAINT ck_employee_region_num CHECK (region_num IS NULL OR region_num ~ '^[0-9]{4}$');
ALTER TABLE employee ADD CONSTRAINT ck_employee_wage_factor CHECK (minimum_wage_factor_used IS NULL OR minimum_wage_factor_used BETWEEN 1 AND 366);
ALTER TABLE employee ADD CONSTRAINT ck_employee_substituted_filing CHECK (bir_substituted_filing IS NULL OR bir_substituted_filing IN ('Y','N'));
