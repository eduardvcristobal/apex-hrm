ALTER TABLE payroll_run DROP CONSTRAINT ck_run_status;
ALTER TABLE payroll_run ADD CONSTRAINT ck_run_status
    CHECK (status IN ('DRAFT','COMPUTED','REVIEWED','APPROVED','EMPLOYEE_REVIEW','LOCKED','PAID'));

ALTER TABLE payslip ADD COLUMN acknowledged_at timestamptz;
CREATE INDEX idx_payslip_run_acknowledgement ON payslip(tenant_id, run_id, acknowledged_at);
