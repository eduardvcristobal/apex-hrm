-- M3.2.9: strict single-import bank result batches and traceable retry exports.
ALTER TABLE bank_file ADD COLUMN recovery_source_file_id uuid REFERENCES bank_file(id);
CREATE INDEX idx_bank_file_recovery_source ON bank_file(tenant_id,recovery_source_file_id);

CREATE TABLE bank_credit_import (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0,
 bank_file_id uuid NOT NULL REFERENCES bank_file(id),
 evidence_reference varchar(255) NOT NULL,
 recorded_by uuid NOT NULL,
 CONSTRAINT uq_bank_credit_import_file UNIQUE (tenant_id,bank_file_id)
);
CREATE TABLE bank_credit_result (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0,
 bank_import_id uuid NOT NULL REFERENCES bank_credit_import(id),
 run_id uuid NOT NULL REFERENCES payroll_run(id),
 bank_file_id uuid NOT NULL REFERENCES bank_file(id),
 employee_id uuid NOT NULL REFERENCES employee(id),
 employee_no varchar(255) NOT NULL,
 amount numeric(18,2) NOT NULL CHECK (amount > 0),
 credit_status varchar(16) NOT NULL CHECK (credit_status IN ('ACCEPTED','REJECTED')),
 bank_reference varchar(120) NOT NULL,
 CONSTRAINT uq_bank_credit_result_employee UNIQUE (tenant_id,bank_file_id,employee_id)
);
CREATE INDEX idx_bank_credit_result_run_employee ON bank_credit_result(tenant_id,run_id,employee_id,credit_status);
