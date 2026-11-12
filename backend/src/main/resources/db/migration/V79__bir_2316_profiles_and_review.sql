ALTER TABLE employee
  ADD COLUMN employee_rdo_code varchar(12),
  ADD COLUMN registered_zip_code varchar(12),
  ADD COLUMN local_home_address text,
  ADD COLUMN local_home_zip_code varchar(12),
  ADD COLUMN foreign_address text,
  ADD COLUMN statutory_minimum_wage_daily numeric(18,4),
  ADD COLUMN statutory_minimum_wage_monthly numeric(18,4),
  ADD COLUMN prior_employer_rdo_code varchar(12),
  ADD COLUMN prior_employer_zip_code varchar(12);

ALTER TABLE company
  ADD COLUMN zip_code varchar(12),
  ADD COLUMN employer_type varchar(40),
  ADD COLUMN main_employer boolean NOT NULL DEFAULT true;

CREATE TABLE bir2316_review (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  employee_id uuid NOT NULL,
  tax_year integer NOT NULL CHECK (tax_year BETWEEN 2000 AND 2200),
  status varchar(20) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  source_fingerprint varchar(64) NOT NULL,
  submitted_by uuid NOT NULL REFERENCES auth_identity(id),
  submitted_at timestamptz NOT NULL DEFAULT now(),
  decided_by uuid REFERENCES auth_identity(id),
  decided_at timestamptz,
  decision_note text,
  CONSTRAINT fk_bir2316_review_employee FOREIGN KEY (tenant_id, employee_id) REFERENCES employee(tenant_id,id),
  CONSTRAINT uq_bir2316_review_year_employee UNIQUE (tenant_id,tax_year,employee_id)
);
CREATE INDEX ix_bir2316_review_queue ON bir2316_review(tenant_id,tax_year,status,submitted_at);
ALTER TABLE bir2316_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE bir2316_review FORCE ROW LEVEL SECURITY;
CREATE POLICY bir2316_review_tenant ON bir2316_review
  USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
