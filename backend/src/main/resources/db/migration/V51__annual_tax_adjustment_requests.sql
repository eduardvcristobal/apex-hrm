-- One controlled annual withholding settlement request per tenant, employee and tax year.
-- Rejection remains in history; no silent retry creates duplicate payroll deductions.
CREATE TABLE annual_tax_adjustment_request (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0,
 employee_id uuid NOT NULL REFERENCES employee(id),
 tax_year integer NOT NULL CHECK (tax_year >= 2023),
 target_period_id uuid NOT NULL REFERENCES pay_period(id),
 requested_by uuid NOT NULL,
 approved_by uuid,
 approved_at timestamptz,
 adjustment_id uuid REFERENCES adjustment(id),
 status varchar(32) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','APPROVED','REJECTED')),
 reconciliation_tax numeric(18,4) NOT NULL CHECK (reconciliation_tax >= 0),
 reconciliation_withheld numeric(18,4) NOT NULL CHECK (reconciliation_withheld >= 0),
 adjustment_amount numeric(18,4) NOT NULL CHECK (adjustment_amount > 0),
 justification varchar(2000) NOT NULL,
 attested_complete_year boolean NOT NULL,
 attested_benefits_reviewed boolean NOT NULL,
 CONSTRAINT uq_annual_tax_adjustment_employee_year UNIQUE (tenant_id,employee_id,tax_year)
);
CREATE INDEX idx_annual_tax_adjustment_tenant ON annual_tax_adjustment_request(tenant_id);
CREATE INDEX idx_annual_tax_adjustment_employee ON annual_tax_adjustment_request(employee_id);
CREATE INDEX idx_annual_tax_adjustment_target ON annual_tax_adjustment_request(target_period_id);
