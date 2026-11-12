CREATE TABLE employee_pera_tax_credit (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL,
  tax_year integer NOT NULL CHECK (tax_year BETWEEN 2000 AND 2200),
  qualified_contributions numeric(18,2) NOT NULL DEFAULT 0 CHECK (qualified_contributions >= 0),
  overseas_filipino boolean NOT NULL DEFAULT false,
  tax_credit_amount numeric(18,2) NOT NULL DEFAULT 0 CHECK (tax_credit_amount >= 0),
  certificate_number varchar(100),
  certificate_received boolean NOT NULL DEFAULT false,
  CONSTRAINT fk_employee_pera_tax_credit_employee FOREIGN KEY (tenant_id,employee_id) REFERENCES employee(tenant_id,id),
  CONSTRAINT uq_employee_pera_tax_credit_year UNIQUE (tenant_id,employee_id,tax_year)
);
CREATE INDEX ix_employee_pera_tax_credit_year ON employee_pera_tax_credit(tenant_id,tax_year,employee_id);
ALTER TABLE employee_pera_tax_credit ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_pera_tax_credit FORCE ROW LEVEL SECURITY;
CREATE POLICY employee_pera_tax_credit_tenant ON employee_pera_tax_credit
  USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
