CREATE TABLE hr_policy (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 title varchar(200) NOT NULL,
 category varchar(80) NOT NULL DEFAULT 'GENERAL',
 summary varchar(500) NOT NULL DEFAULT '',
 body text NOT NULL,
 version_label varchar(40) NOT NULL DEFAULT '1.0',
 status varchar(16) NOT NULL DEFAULT 'DRAFT',
 effective_on date,
 published_at timestamptz,
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_hr_policy_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_hr_policy_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_hr_policy_status CHECK(status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);
CREATE INDEX ix_hr_policy_visible ON hr_policy(tenant_id,status,effective_on);
ALTER TABLE hr_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE hr_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY hr_policy_tenant ON hr_policy USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
