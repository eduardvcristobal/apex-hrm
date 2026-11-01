-- Generated from the HRM schema spec. Tenant-scoped tables carry tenant_id; G tables are global reference data.

CREATE TABLE tenant (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  status varchar(255) NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uq_tenant_code UNIQUE (code)
);

CREATE TABLE app_user (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  username varchar(255) NOT NULL,
  email varchar(255),
  password_hash varchar(255),
  employee_id uuid,
  role varchar(255) NOT NULL DEFAULT 'EMPLOYEE',
  status varchar(255) NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uq_app_user_tenant_id_username UNIQUE (tenant_id, username)
);
CREATE INDEX idx_app_user_tenant ON app_user(tenant_id);

CREATE TABLE audit_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  entity_type varchar(255) NOT NULL,
  entity_id uuid,
  action varchar(255) NOT NULL,
  actor varchar(255),
  summary text
);
CREATE INDEX idx_audit_log_tenant ON audit_log(tenant_id);
