-- Workspace identity/admin foundation adapted to Apex HRM's existing tenant and company tables.
ALTER TABLE tenant ADD COLUMN slug varchar(80);
ALTER TABLE tenant ADD COLUMN display_name varchar(200);
UPDATE tenant SET slug = lower(regexp_replace(coalesce(code, 'workspace'), '[^A-Za-z0-9-]+', '-', 'g')) || '-' || left(id::text, 8), display_name = name;
ALTER TABLE tenant ALTER COLUMN slug SET NOT NULL;
ALTER TABLE tenant ALTER COLUMN display_name SET NOT NULL;
ALTER TABLE tenant ADD CONSTRAINT uq_tenant_slug UNIQUE (slug);

ALTER TABLE company ADD COLUMN company_code varchar(40);
ALTER TABLE company ADD COLUMN country_code char(2) NOT NULL DEFAULT 'PH';
ALTER TABLE company ADD COLUMN timezone varchar(64) NOT NULL DEFAULT 'Asia/Manila';
UPDATE company SET company_code = 'LEGACY-' || upper(left(id::text, 8)) WHERE company_code IS NULL;
ALTER TABLE company ALTER COLUMN company_code SET NOT NULL;
ALTER TABLE company ADD CONSTRAINT uq_company_tenant_code UNIQUE (tenant_id, company_code);
ALTER TABLE company ADD CONSTRAINT uq_company_tenant_id_id UNIQUE (tenant_id, id);

CREATE TABLE branch (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL, company_id uuid NOT NULL,
 branch_code varchar(40) NOT NULL, branch_name varchar(200) NOT NULL, region_code varchar(40),
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_branch_company_tenant FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT uq_branch_company_code UNIQUE (company_id,branch_code)
);
CREATE INDEX ix_branch_tenant_company ON branch(tenant_id,company_id);

CREATE TABLE audit_event (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, tenant_id uuid, actor_id uuid,
 action varchar(100) NOT NULL, entity_type varchar(100) NOT NULL, entity_id uuid,
 occurred_at timestamptz NOT NULL DEFAULT now(), metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_audit_tenant_when ON audit_event(tenant_id,occurred_at DESC);
