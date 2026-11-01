-- Generated from the HRM schema spec. Tenant-scoped tables carry tenant_id; G tables are global reference data.

CREATE TABLE employee (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  company_id uuid NOT NULL REFERENCES company(id),
  employee_no varchar(255) NOT NULL,
  first_name varchar(255) NOT NULL,
  middle_name varchar(255),
  last_name varchar(255) NOT NULL,
  suffix varchar(255),
  birth_date date,
  sex varchar(255),
  civil_status varchar(255),
  email varchar(255),
  mobile varchar(255),
  address text,
  tin varchar(255),
  sss_no varchar(255),
  philhealth_no varchar(255),
  pagibig_no varchar(255),
  hire_date date NOT NULL,
  separation_date date,
  minimum_wage_earner boolean NOT NULL DEFAULT false,
  status varchar(255) NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uq_employee_tenant_id_employee_no UNIQUE (tenant_id, employee_no)
);
CREATE INDEX idx_employee_tenant ON employee(tenant_id);
CREATE INDEX idx_employee_company_id ON employee(company_id);

CREATE TABLE employee_bank_account (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  bank_code varchar(255) NOT NULL,
  account_no varchar(255) NOT NULL,
  account_name varchar(255),
  primary_account boolean NOT NULL DEFAULT true
);
CREATE INDEX idx_employee_bank_account_tenant ON employee_bank_account(tenant_id);
CREATE INDEX idx_employee_bank_account_employee_id ON employee_bank_account(employee_id);

CREATE TABLE employee_dependent (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  full_name varchar(255) NOT NULL,
  relationship varchar(255),
  birth_date date
);
CREATE INDEX idx_employee_dependent_tenant ON employee_dependent(tenant_id);
CREATE INDEX idx_employee_dependent_employee_id ON employee_dependent(employee_id);

CREATE TABLE employee_document (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  document_type varchar(255) NOT NULL,
  file_ref varchar(255),
  expiry_date date
);
CREATE INDEX idx_employee_document_tenant ON employee_document(tenant_id);
CREATE INDEX idx_employee_document_employee_id ON employee_document(employee_id);

CREATE TABLE employment_record (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  effective_from date NOT NULL,
  effective_to date,
  employment_type varchar(255) NOT NULL,
  position_id uuid REFERENCES job_position(id),
  department_id uuid REFERENCES department(id),
  cost_center_id uuid REFERENCES cost_center(id),
  work_location_id uuid REFERENCES work_location(id),
  manager_id uuid REFERENCES employee(id),
  pay_group_id uuid REFERENCES pay_group(id),
  remarks text
);
CREATE INDEX idx_employment_record_tenant ON employment_record(tenant_id);
CREATE INDEX idx_employment_record_employee_id ON employment_record(employee_id);
CREATE INDEX idx_employment_record_position_id ON employment_record(position_id);
CREATE INDEX idx_employment_record_department_id ON employment_record(department_id);
CREATE INDEX idx_employment_record_cost_center_id ON employment_record(cost_center_id);
CREATE INDEX idx_employment_record_work_location_id ON employment_record(work_location_id);
CREATE INDEX idx_employment_record_manager_id ON employment_record(manager_id);
CREATE INDEX idx_employment_record_pay_group_id ON employment_record(pay_group_id);

CREATE TABLE compensation (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  effective_from date NOT NULL,
  effective_to date,
  pay_basis varchar(255) NOT NULL,
  base_rate numeric(18,4) NOT NULL,
  reason varchar(255)
);
CREATE INDEX idx_compensation_tenant ON compensation(tenant_id);
CREATE INDEX idx_compensation_employee_id ON compensation(employee_id);

CREATE TABLE recurring_item (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  item_code varchar(255) NOT NULL,
  category varchar(255) NOT NULL,
  description varchar(255),
  amount numeric(18,4) NOT NULL,
  taxable boolean NOT NULL DEFAULT true,
  effective_from date NOT NULL,
  effective_to date
);
CREATE INDEX idx_recurring_item_tenant ON recurring_item(tenant_id);
CREATE INDEX idx_recurring_item_employee_id ON recurring_item(employee_id);

CREATE TABLE loan (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  loan_type varchar(255) NOT NULL,
  reference_no varchar(255),
  principal numeric(18,4) NOT NULL,
  amortization numeric(18,4) NOT NULL,
  balance numeric(18,4) NOT NULL,
  start_date date NOT NULL,
  status varchar(255) NOT NULL DEFAULT 'ACTIVE'
);
CREATE INDEX idx_loan_tenant ON loan(tenant_id);
CREATE INDEX idx_loan_employee_id ON loan(employee_id);
