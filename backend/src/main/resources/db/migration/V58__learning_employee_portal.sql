-- M6.7: Explicit and auditable employee-to-login association for private learning portal.
-- Never infer employee ownership from email, names, or supplied employee IDs.
CREATE TABLE ld_employee_account (
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 identity_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 linked_at timestamptz NOT NULL DEFAULT now(),
 linked_by uuid NOT NULL,
 PRIMARY KEY(tenant_id,identity_id),
 CONSTRAINT uq_ld_employee_account_employee UNIQUE(tenant_id,employee_id),
 CONSTRAINT fk_ld_account_linked_by FOREIGN KEY(tenant_id,linked_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_ld_account_identity FOREIGN KEY(tenant_id,identity_id) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_ld_account_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id)
);
ALTER TABLE ld_employee_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE ld_employee_account FORCE ROW LEVEL SECURITY;
CREATE POLICY ld_employee_account_tenant ON ld_employee_account
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
