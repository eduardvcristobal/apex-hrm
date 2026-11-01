-- Generated from the HRM schema spec. Tenant-scoped tables carry tenant_id; G tables are global reference data.

CREATE TABLE pay_period (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  pay_group_id uuid NOT NULL REFERENCES pay_group(id),
  period_of_month integer NOT NULL DEFAULT 1,
  start_date date NOT NULL,
  end_date date NOT NULL,
  pay_date date NOT NULL,
  status varchar(255) NOT NULL DEFAULT 'OPEN',
  CONSTRAINT uq_pay_period_tenant_id_pay_group_id_start_date_end_da UNIQUE (tenant_id, pay_group_id, start_date, end_date)
);
CREATE INDEX idx_pay_period_tenant ON pay_period(tenant_id);
CREATE INDEX idx_pay_period_pay_group_id ON pay_period(pay_group_id);

CREATE TABLE pay_item_type (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  category varchar(255) NOT NULL,
  taxable boolean NOT NULL DEFAULT true,
  system_item boolean NOT NULL DEFAULT false,
  CONSTRAINT uq_pay_item_type_tenant_id_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_pay_item_type_tenant ON pay_item_type(tenant_id);

CREATE TABLE statutory_contribution_rule (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  agency varchar(255) NOT NULL,
  effective_from date NOT NULL,
  effective_to date,
  employee_rate numeric(18,4) NOT NULL,
  employer_rate numeric(18,4) NOT NULL,
  min_base numeric(18,4) NOT NULL,
  max_base numeric(18,4) NOT NULL,
  msc_step numeric(18,4),
  ec_low numeric(18,4),
  ec_high numeric(18,4),
  ec_threshold numeric(18,4),
  mpf_threshold numeric(18,4)
);

CREATE TABLE withholding_tax_bracket (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  effective_from date NOT NULL,
  period_type varchar(255) NOT NULL,
  lower_bound numeric(18,4) NOT NULL,
  upper_bound numeric(18,4),
  base_tax numeric(18,4) NOT NULL,
  rate numeric(18,4) NOT NULL
);

CREATE TABLE premium_rule (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  day_type varchar(255) NOT NULL,
  worked_multiplier numeric(18,4) NOT NULL,
  ot_multiplier numeric(18,4) NOT NULL,
  unworked_multiplier numeric(18,4) NOT NULL,
  base_included_monthly boolean NOT NULL,
  night_diff_rate numeric(18,4) NOT NULL,
  effective_from date NOT NULL
);

CREATE TABLE wage_order (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  region_code varchar(255) NOT NULL,
  sector varchar(255) NOT NULL,
  daily_rate numeric(18,4) NOT NULL,
  effective_from date NOT NULL,
  effective_to date,
  reference varchar(255)
);

CREATE TABLE gl_account (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  account_type varchar(255) NOT NULL,
  CONSTRAINT uq_gl_account_tenant_id_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_gl_account_tenant ON gl_account(tenant_id);

CREATE TABLE gl_mapping (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  item_code varchar(255) NOT NULL,
  debit_account_id uuid REFERENCES gl_account(id),
  credit_account_id uuid REFERENCES gl_account(id),
  CONSTRAINT uq_gl_mapping_tenant_id_item_code UNIQUE (tenant_id, item_code)
);
CREATE INDEX idx_gl_mapping_tenant ON gl_mapping(tenant_id);
CREATE INDEX idx_gl_mapping_debit_account_id ON gl_mapping(debit_account_id);
CREATE INDEX idx_gl_mapping_credit_account_id ON gl_mapping(credit_account_id);

CREATE TABLE bank_template (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  bank_code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  delimiter varchar(255) NOT NULL DEFAULT ',',
  include_header boolean NOT NULL DEFAULT true,
  column_spec varchar(255) NOT NULL DEFAULT 'ACCOUNT_NO,ACCOUNT_NAME,AMOUNT',
  CONSTRAINT uq_bank_template_tenant_id_bank_code UNIQUE (tenant_id, bank_code)
);
CREATE INDEX idx_bank_template_tenant ON bank_template(tenant_id);
