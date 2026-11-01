-- Domain constraints and query indexes that the generated DDL does not cover.

ALTER TABLE employee            ADD CONSTRAINT ck_employee_status   CHECK (status IN ('ACTIVE','ON_LEAVE','SEPARATED'));
ALTER TABLE compensation        ADD CONSTRAINT ck_comp_basis        CHECK (pay_basis IN ('MONTHLY','DAILY'));
ALTER TABLE compensation        ADD CONSTRAINT ck_comp_rate         CHECK (base_rate > 0);
ALTER TABLE recurring_item      ADD CONSTRAINT ck_recurring_cat     CHECK (category IN ('EARNING','DEDUCTION'));
ALTER TABLE pay_group           ADD CONSTRAINT ck_pg_frequency      CHECK (frequency IN ('MONTHLY','SEMI_MONTHLY'));
ALTER TABLE pay_group           ADD CONSTRAINT ck_pg_contrib        CHECK (contribution_schedule IN ('EVERY_CUTOFF','LAST_CUTOFF'));
ALTER TABLE pay_period          ADD CONSTRAINT ck_pp_dates          CHECK (end_date >= start_date);
ALTER TABLE pay_period          ADD CONSTRAINT ck_pp_status         CHECK (status IN ('OPEN','LOCKED'));
ALTER TABLE holiday             ADD CONSTRAINT ck_holiday_type      CHECK (holiday_type IN ('REGULAR','SPECIAL_NON_WORKING','SPECIAL_WORKING'));
ALTER TABLE time_log            ADD CONSTRAINT ck_timelog_type      CHECK (log_type IN ('IN','OUT'));
ALTER TABLE time_log            ADD CONSTRAINT ck_timelog_source    CHECK (source IN ('MOBILE','WEB','MANUAL','IMPORT'));
ALTER TABLE dtr_day             ADD CONSTRAINT ck_dtr_status        CHECK (status IN ('OPEN','LOCKED'));
ALTER TABLE leave_request       ADD CONSTRAINT ck_leave_status      CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED'));
ALTER TABLE leave_request       ADD CONSTRAINT ck_leave_dates       CHECK (date_to >= date_from);
ALTER TABLE overtime_request    ADD CONSTRAINT ck_ot_status         CHECK (status IN ('PENDING','APPROVED','REJECTED'));
ALTER TABLE attendance_correction ADD CONSTRAINT ck_corr_status     CHECK (status IN ('PENDING','APPROVED','REJECTED'));
ALTER TABLE payroll_run         ADD CONSTRAINT ck_run_status        CHECK (status IN ('DRAFT','COMPUTED','REVIEWED','APPROVED','LOCKED','PAID'));
ALTER TABLE payroll_run         ADD CONSTRAINT ck_run_type          CHECK (run_type IN ('REGULAR','OFF_CYCLE','THIRTEENTH_MONTH'));
ALTER TABLE payroll_line        ADD CONSTRAINT ck_line_category     CHECK (category IN ('EARNING','DEDUCTION','EMPLOYER_COST'));
ALTER TABLE adjustment          ADD CONSTRAINT ck_adj_category      CHECK (category IN ('EARNING','DEDUCTION'));
ALTER TABLE adjustment          ADD CONSTRAINT ck_adj_status        CHECK (status IN ('PENDING','APPROVED','REJECTED','APPLIED'));
ALTER TABLE adjustment          ADD CONSTRAINT ck_adj_amount        CHECK (amount > 0);
ALTER TABLE journal_entry       ADD CONSTRAINT ck_je_status         CHECK (status IN ('DRAFT','POSTED','EXPORTED'));
ALTER TABLE loan                ADD CONSTRAINT ck_loan_status       CHECK (status IN ('ACTIVE','PAID','CANCELLED'));

-- Hot paths: attendance by employee/time, DTR by period, ledger balance, payroll lines by run.
CREATE INDEX idx_time_log_emp_time   ON time_log (tenant_id, employee_id, log_time);
CREATE INDEX idx_dtr_day_date        ON dtr_day (tenant_id, work_date);
CREATE INDEX idx_leave_ledger_bal    ON leave_ledger (tenant_id, employee_id, leave_type_id);
CREATE INDEX idx_payroll_line_run    ON payroll_line (tenant_id, run_id);
CREATE INDEX idx_comp_emp_effective  ON compensation (tenant_id, employee_id, effective_from DESC);
CREATE INDEX idx_emprec_emp_effective ON employment_record (tenant_id, employee_id, effective_from DESC);
