-- A recorded bank acknowledgment is separate from bank-file generation and requires
-- a different authenticated operator to confirm it before payroll can be marked PAID.
CREATE TABLE payroll_bank_settlement (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0,
 run_id uuid NOT NULL REFERENCES payroll_run(id),
 bank_file_id uuid NOT NULL REFERENCES bank_file(id),
 bank_reference varchar(120) NOT NULL,
 acknowledged_amount numeric(18,2) NOT NULL CHECK (acknowledged_amount > 0),
 evidence_reference varchar(255) NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'RECORDED' CHECK (status IN ('RECORDED','CONFIRMED')),
 recorded_by uuid NOT NULL,
 confirmed_by uuid,
 confirmed_at timestamptz,
 CONSTRAINT uq_payroll_bank_settlement_file UNIQUE (tenant_id, bank_file_id),
 CONSTRAINT uq_payroll_bank_settlement_reference UNIQUE (tenant_id, bank_reference),
 CONSTRAINT ck_settlement_confirmation CHECK (
   (status='RECORDED' AND confirmed_by IS NULL AND confirmed_at IS NULL)
   OR (status='CONFIRMED' AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND confirmed_by <> recorded_by)
 )
);
CREATE INDEX idx_payroll_bank_settlement_run ON payroll_bank_settlement(tenant_id,run_id,status);
