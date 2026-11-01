-- Retain rejected and reversed acknowledgments as part of the settlement audit trail.
ALTER TABLE payroll_bank_settlement
    ADD COLUMN exception_reason text,
    ADD COLUMN exception_evidence_reference varchar(255),
    ADD COLUMN exception_by uuid,
    ADD COLUMN exception_at timestamptz;

ALTER TABLE payroll_bank_settlement DROP CONSTRAINT ck_settlement_confirmation;
ALTER TABLE payroll_bank_settlement DROP CONSTRAINT payroll_bank_settlement_status_check;

ALTER TABLE payroll_bank_settlement ADD CONSTRAINT payroll_bank_settlement_status_check
    CHECK (status IN ('RECORDED','CONFIRMED','REJECTED','REVERSED'));
ALTER TABLE payroll_bank_settlement ADD CONSTRAINT ck_settlement_confirmation CHECK (
    (status='RECORDED' AND confirmed_by IS NULL AND confirmed_at IS NULL
        AND exception_by IS NULL AND exception_at IS NULL)
    OR
    (status='CONFIRMED' AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL
        AND confirmed_by <> recorded_by AND exception_by IS NULL AND exception_at IS NULL)
    OR
    (status='REJECTED' AND confirmed_by IS NULL AND confirmed_at IS NULL
        AND exception_by IS NOT NULL AND exception_at IS NOT NULL AND exception_by <> recorded_by
        AND length(btrim(exception_reason)) > 0 AND length(btrim(exception_evidence_reference)) > 0)
    OR
    (status='REVERSED' AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL
        AND confirmed_by <> recorded_by AND exception_by IS NOT NULL AND exception_at IS NOT NULL
        AND exception_by <> confirmed_by
        AND length(btrim(exception_reason)) > 0 AND length(btrim(exception_evidence_reference)) > 0)
);
CREATE INDEX idx_payroll_bank_settlement_exception
    ON payroll_bank_settlement(tenant_id, run_id, status)
    WHERE status IN ('REJECTED','REVERSED');
