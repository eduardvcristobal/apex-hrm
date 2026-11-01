-- Generated from the HRM schema spec. Tenant-scoped tables carry tenant_id; G tables are global reference data.

CREATE TABLE company (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  legal_name varchar(255) NOT NULL,
  trade_name varchar(255),
  tin varchar(255),
  rdo_code varchar(255),
  sss_employer_no varchar(255),
  philhealth_employer_no varchar(255),
  pagibig_employer_no varchar(255),
  address text
);
CREATE INDEX idx_company_tenant ON company(tenant_id);

CREATE TABLE work_location (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  company_id uuid NOT NULL REFERENCES company(id),
  code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  region_code varchar(255) NOT NULL,
  city varchar(255),
  address text,
  latitude numeric(18,4),
  longitude numeric(18,4),
  geofence_radius_m integer,
  CONSTRAINT uq_work_location_tenant_id_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_work_location_tenant ON work_location(tenant_id);
CREATE INDEX idx_work_location_company_id ON work_location(company_id);

CREATE TABLE department (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  company_id uuid NOT NULL REFERENCES company(id),
  parent_id uuid REFERENCES department(id),
  code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  CONSTRAINT uq_department_tenant_id_company_id_code UNIQUE (tenant_id, company_id, code)
);
CREATE INDEX idx_department_tenant ON department(tenant_id);
CREATE INDEX idx_department_company_id ON department(company_id);
CREATE INDEX idx_department_parent_id ON department(parent_id);

CREATE TABLE cost_center (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  CONSTRAINT uq_cost_center_tenant_id_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_cost_center_tenant ON cost_center(tenant_id);

CREATE TABLE job_position (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  code varchar(255) NOT NULL,
  title varchar(255) NOT NULL,
  grade varchar(255),
  CONSTRAINT uq_job_position_tenant_id_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_job_position_tenant ON job_position(tenant_id);

CREATE TABLE pay_group (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  company_id uuid NOT NULL REFERENCES company(id),
  code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  frequency varchar(255) NOT NULL DEFAULT 'SEMI_MONTHLY',
  days_factor numeric(18,4) NOT NULL DEFAULT 261,
  hours_per_day numeric(18,4) NOT NULL DEFAULT 8,
  contribution_schedule varchar(255) NOT NULL DEFAULT 'LAST_CUTOFF',
  tax_method varchar(255) NOT NULL DEFAULT 'STRICT',
  CONSTRAINT uq_pay_group_tenant_id_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_pay_group_tenant ON pay_group(tenant_id);
CREATE INDEX idx_pay_group_company_id ON pay_group(company_id);
